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

    public void streamChat(
            CockpitSettings settings,
            String systemPrompt,
            String userPrompt,
            Consumer<String> onThinking,
            Consumer<String> onContent)
            throws IOException, InterruptedException {
        streamChatInternal(settings, systemPrompt, userPrompt, onThinking, onContent);
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

    private void streamChatInternal(
            CockpitSettings settings,
            String systemPrompt,
            String userPrompt,
            Consumer<String> onThinking,
            Consumer<String> onContent)
            throws IOException, InterruptedException {
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

        boolean gotAnyStreamToken = false;
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
                    TokenParts parts = extractTokenParts(data, settings.includeThinking());
                    if (!parts.thinking().isEmpty()) {
                        gotAnyStreamToken = true;
                        onThinking.accept(parts.thinking());
                    }
                    if (!parts.content().isEmpty()) {
                        gotAnyStreamToken = true;
                        onContent.accept(parts.content());
                    }
                } else if (!line.isBlank()) {
                    nonSseBody.append(line).append('\n');
                }
            }
        }

        if (!gotAnyStreamToken && !nonSseBody.isEmpty()) {
            String fallback = nonSseBody.toString();
            throwIfBurpProxyError(fallback);
            TokenParts parts = extractTokenParts(fallback, settings.includeThinking());
            if (!parts.thinking().isEmpty()) {
                onThinking.accept(parts.thinking());
            }
            if (!parts.content().isEmpty()) {
                onContent.accept(parts.content());
            } else {
                String content = JsonUtil.extractStringField(fallback, "content");
                onContent.accept(content.isBlank() ? fallback : content);
            }
        }
    }

    private HttpRequest.Builder baseRequest(String endpoint) {
        return HttpRequest.newBuilder(URI.create(endpoint))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofMinutes(10));
    }

    private static TokenParts extractTokenParts(String json, boolean includeThinking) {
        String thinking = "";
        if (includeThinking) {
            thinking = firstNonBlank(
                    JsonUtil.extractStringField(json, "reasoning_content"),
                    JsonUtil.extractStringField(json, "reasoning"),
                    JsonUtil.extractStringField(json, "thinking"));
        }
        String content = firstNonBlank(
                JsonUtil.extractStringField(json, "content"),
                JsonUtil.extractStringField(json, "text"));
        return new TokenParts(thinking, content);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            String clean = Objects.toString(value, "");
            if (!clean.isBlank()) {
                return clean;
            }
        }
        return "";
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
            throw new IOException("Lumara endpoint is pointing at Burp's proxy listener. In the Kali VM use http://10.0.2.2:8080/v1/chat/completions, or move Burp/listeners off that port. The extension forces HTTP/1.1 and bypasses JVM proxy settings.");
        }
    }

    private record TokenParts(String thinking, String content) {}
}
