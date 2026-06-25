package com.buggyboi.burpcockpit.lumara;

import com.buggyboi.burpcockpit.state.CockpitSettings;
import com.buggyboi.burpcockpit.util.JsonUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class LumaraClient {
    private final HttpClient client;

    public LumaraClient() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .proxy(ProxySelector.of(null))
                .build();
    }

    public Thread streamChat(CockpitSettings settings, String systemPrompt, String userPrompt, Consumer<String> onToken, Consumer<Throwable> onError, Runnable onDone) {
        Thread thread = new Thread(() -> {
            try {
                if (settings.streamChat()) {
                    streamChatInternal(settings, systemPrompt, userPrompt, onToken);
                } else {
                    onToken.accept(sendChat(settings, systemPrompt, userPrompt));
                }
                if (!Thread.currentThread().isInterrupted()) {
                    onDone.run();
                }
            } catch (Throwable throwable) {
                if (!Thread.currentThread().isInterrupted()) {
                    onError.accept(throwable);
                }
            }
        }, "burp-cockpit-lumara-chat");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    public String sendChat(CockpitSettings settings, String systemPrompt, String userPrompt) throws IOException, InterruptedException {
        String body = chatBody(settings, systemPrompt, userPrompt, false);
        HttpRequest request = baseRequest(settings.chatEndpoint())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String responseBody = response.body() == null ? "" : response.body();
        throwIfBurpProxyError(responseBody);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Chat endpoint returned HTTP " + response.statusCode() + ": " + responseBody);
        }
        String content = JsonUtil.extractStringField(responseBody, "content");
        return content.isBlank() ? responseBody : content;
    }

    public String ragSearch(CockpitSettings settings, String query, int limit, String scope) throws IOException, InterruptedException {
        String safeScope = safeScope(scope, "both");
        String body = "{"
                + "\"query\":" + JsonUtil.quote(query) + ","
                + "\"q\":" + JsonUtil.quote(query) + ","
                + "\"n_results\":" + Math.max(1, limit) + ","
                + "\"nResults\":" + Math.max(1, limit) + ","
                + "\"scope\":" + JsonUtil.quote(safeScope)
                + "}";
        return postJson(settings.ragSearchEndpoint(), body);
    }

    public String saveNote(CockpitSettings settings, String title, String body, String targetUri, String notePath, boolean autoRename) throws IOException, InterruptedException {
        String payload = "{"
                + "\"title\":" + JsonUtil.quote(title == null || title.isBlank() ? "DEFAULT" : title) + ","
                + "\"body\":" + JsonUtil.quote(body) + ","
                + "\"tags\":[\"burp-cockpit\",\"notes\"],"
                + "\"scope\":\"notes\","
                + "\"path\":" + JsonUtil.quote(notePath) + ","
                + "\"note_path\":" + JsonUtil.quote(notePath) + ","
                + "\"target_uri\":" + JsonUtil.quote(targetUri) + ","
                + "\"auto_rename\":" + autoRename + ","
                + "\"ingest\":true"
                + "}";
        String endpoint = settings.ragSearchEndpoint().replace("/rag/search", "/rag/save-note").replace("/api/rag/search", "/api/rag/save-note");
        return postJson(endpoint, payload);
    }

    public String ingest(CockpitSettings settings, String scope) throws IOException, InterruptedException {
        String endpoint = settings.ragSearchEndpoint().replace("/rag/search", "/rag/ingest").replace("/api/rag/search", "/api/rag/ingest");
        String body = "{\"scope\":" + JsonUtil.quote(safeScope(scope, "both")) + "}";
        return postJson(endpoint, body);
    }

    private String postJson(String endpoint, String body) throws IOException, InterruptedException {
        HttpRequest request = baseRequest(endpoint)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String responseBody = response.body() == null ? "" : response.body();
        throwIfBurpProxyError(responseBody);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(endpoint + " returned HTTP " + response.statusCode() + ": " + responseBody);
        }
        return responseBody;
    }

    private void streamChatInternal(CockpitSettings settings, String systemPrompt, String userPrompt, Consumer<String> onToken) throws IOException, InterruptedException {
        String body = chatBody(settings, systemPrompt, userPrompt, true);
        HttpRequest request = baseRequest(settings.chatEndpoint())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream, application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Lumara request cancelled.");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            throwIfBurpProxyError(errorBody);
            throw new IOException("Chat endpoint returned HTTP " + response.statusCode() + ": " + errorBody);
        }

        AtomicBoolean gotStreamToken = new AtomicBoolean(false);
        StringBuilder nonSseBody = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Lumara request cancelled.");
                }
                if (line.startsWith("data:")) {
                    String data = line.substring(5).trim();
                    if (data.equals("[DONE]")) {
                        break;
                    }
                    String token = extractAssistantToken(data, settings.includeThinking());
                    if (!token.isEmpty()) {
                        gotStreamToken.set(true);
                        onToken.accept(token);
                    }
                } else if (!line.isBlank()) {
                    nonSseBody.append(line).append('\n');
                }
            }
        }
        if (!gotStreamToken.get() && !nonSseBody.isEmpty()) {
            String fallback = nonSseBody.toString();
            throwIfBurpProxyError(fallback);
            String content = JsonUtil.extractStringField(fallback, "content");
            onToken.accept(content.isBlank() ? fallback : content);
        }
    }

    private HttpRequest.Builder baseRequest(String endpoint) {
        return HttpRequest.newBuilder(URI.create(endpoint))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofMinutes(10));
    }

    private static String extractAssistantToken(String json, boolean includeThinking) {
        if (includeThinking) {
            String reasoning = JsonUtil.extractStringField(json, "reasoning_content");
            if (!reasoning.isEmpty()) {
                return reasoning;
            }
        }
        String content = JsonUtil.extractStringField(json, "content");
        if (!content.isEmpty()) {
            return content;
        }
        String text = JsonUtil.extractStringField(json, "text");
        return Objects.toString(text, "");
    }

    private static String chatBody(CockpitSettings settings, String systemPrompt, String userPrompt, boolean stream) {
        int maxTokens = Math.max(256, settings.tokenBudget());
        return "{"
                + "\"model\":" + JsonUtil.quote(settings.model()) + ","
                + "\"temperature\":0.2,"
                + "\"top_p\":0.85,"
                + "\"max_tokens\":" + maxTokens + ","
                + "\"stream\":" + stream + ","
                + "\"messages\":["
                + "{\"role\":\"system\",\"content\":" + JsonUtil.quote(systemPrompt) + "},"
                + "{\"role\":\"user\",\"content\":" + JsonUtil.quote(userPrompt) + "}"
                + "]}"
                ;
    }

    private static String safeScope(String scope, String fallback) {
        String normalized = scope == null ? "" : scope.trim().toLowerCase();
        if (normalized.equals("library") || normalized.equals("notes") || normalized.equals("both")) {
            return normalized;
        }
        return fallback;
    }

    private static void throwIfBurpProxyError(String responseBody) throws IOException {
        String body = Objects.toString(responseBody, "");
        if (body.contains("Burp Suite") && body.contains("Invalid client request received")) {
            throw new IOException("Lumara endpoint is pointing at Burp's proxy listener. In the Kali VM use http://10.0.2.2:8080/v1/chat/completions, or move Burp/listeners off that port. The extension now forces HTTP/1.1 and bypasses JVM proxy settings, because apparently even localhost needs a chaperone.");
        }
    }
}
