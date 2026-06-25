package com.buggyboi.burpcockpit.lumara;

import com.buggyboi.burpcockpit.state.CockpitSettings;
import com.buggyboi.burpcockpit.util.JsonUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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
                .build();
    }

    public void streamChat(CockpitSettings settings, String systemPrompt, String userPrompt, Consumer<String> onToken, Consumer<Throwable> onError, Runnable onDone) {
        Thread thread = new Thread(() -> {
            try {
                if (settings.streamChat()) {
                    streamChatInternal(settings, systemPrompt, userPrompt, onToken);
                } else {
                    onToken.accept(sendChat(settings, systemPrompt, userPrompt));
                }
                onDone.run();
            } catch (Throwable throwable) {
                onError.accept(throwable);
            }
        }, "burp-cockpit-lumara-chat");
        thread.setDaemon(true);
        thread.start();
    }

    public String sendChat(CockpitSettings settings, String systemPrompt, String userPrompt) throws IOException, InterruptedException {
        String body = chatBody(settings, systemPrompt, userPrompt, false);
        HttpRequest request = HttpRequest.newBuilder(URI.create(settings.chatEndpoint()))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Chat endpoint returned HTTP " + response.statusCode() + ": " + response.body());
        }
        String content = JsonUtil.extractStringField(response.body(), "content");
        return content.isBlank() ? response.body() : content;
    }

    public String ragSearch(CockpitSettings settings, String query, int limit) throws IOException, InterruptedException {
        String body = "{\"query\":" + JsonUtil.quote(query) + ",\"limit\":" + Math.max(1, limit) + "}";
        return postJson(settings.ragSearchEndpoint(), body);
    }

    public String payloadIdeas(CockpitSettings settings, String requestText, String responseText, int limit) throws IOException, InterruptedException {
        String body = "{"
                + "\"request\":" + JsonUtil.quote(requestText) + ","
                + "\"response\":" + JsonUtil.quote(responseText) + ","
                + "\"limit\":" + Math.max(1, limit)
                + "}";
        return postJson(settings.payloadIdeasEndpoint(), body);
    }

    private String postJson(String endpoint, String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofMinutes(3))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(endpoint + " returned HTTP " + response.statusCode() + ": " + response.body());
        }
        return response.body() == null ? "" : response.body();
    }

    private void streamChatInternal(CockpitSettings settings, String systemPrompt, String userPrompt, Consumer<String> onToken) throws IOException, InterruptedException {
        String body = chatBody(settings, systemPrompt, userPrompt, true);
        HttpRequest request = HttpRequest.newBuilder(URI.create(settings.chatEndpoint()))
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream, application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
            throw new IOException("Chat endpoint returned HTTP " + response.statusCode() + ": " + errorBody);
        }

        AtomicBoolean gotStreamToken = new AtomicBoolean(false);
        StringBuilder nonSseBody = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
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
            String content = JsonUtil.extractStringField(nonSseBody.toString(), "content");
            onToken.accept(content.isBlank() ? nonSseBody.toString() : content);
        }
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
}
