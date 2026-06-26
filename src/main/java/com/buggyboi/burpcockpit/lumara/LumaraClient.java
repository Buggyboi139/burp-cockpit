package com.buggyboi.burpcockpit.lumara;

import com.buggyboi.burpcockpit.state.CockpitSettings;
import com.buggyboi.burpcockpit.state.TrafficSnapshot;
import com.buggyboi.burpcockpit.util.HttpText;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

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
            Consumer<String> onContent)
            throws IOException, InterruptedException {
        streamChatInternal(settings, systemPrompt, userPrompt, onContent);
    }

    public String ragSearch(CockpitSettings settings, String query, int limit, String scope) throws IOException, InterruptedException {
        return ragSearch(settings, query, limit, scope, null);
    }

    public String ragSearch(CockpitSettings settings, String query, int limit, String scope, TrafficSnapshot snapshot) throws IOException, InterruptedException {
        String safeScope = safeScope(scope, "both");
        String body = "{"
                + "\"query\":" + JsonUtil.quote(query) + ","
                + "\"q\":" + JsonUtil.quote(query) + ","
                + "\"n_results\":" + Math.max(1, limit) + ","
                + "\"nResults\":" + Math.max(1, limit) + ","
                + "\"scope\":" + JsonUtil.quote(safeScope) + ","
                + "\"response_format\":\"context\","
                + "\"client\":\"burp-cockpit\","
                + "\"context\":" + ragContextBody(snapshot)
                + "}";
        return ragContextFromResponse(postJson(settings.ragSearchEndpoint(), body, settings.ragApiKey()));
    }

    private String postJson(String endpoint, String body) throws IOException, InterruptedException {
        return postJson(endpoint, body, "");
    }

    private String postJson(String endpoint, String body, String apiKey) throws IOException, InterruptedException {
        HttpRequest.Builder builder = baseRequest(endpoint)
                .header("Content-Type", "application/json");
        addAuthHeaders(builder, apiKey);
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
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
        ThinkStripper stripper = new ThinkStripper();
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
                    String content = stripper.filter(extractContent(data));
                    if (!content.isEmpty()) {
                        gotAnyStreamToken = true;
                        onContent.accept(content);
                    }
                } else if (!line.isBlank()) {
                    nonSseBody.append(line).append('\n');
                }
            }
        }

        if (!gotAnyStreamToken && !nonSseBody.isEmpty()) {
            String fallback = nonSseBody.toString();
            throwIfBurpProxyError(fallback);
            String content = stripThinkBlocks(extractContent(fallback));
            onContent.accept(content.isBlank() ? stripThinkBlocks(fallback) : content);
        }
    }

    private HttpRequest.Builder baseRequest(String endpoint) {
        return HttpRequest.newBuilder(URI.create(endpoint))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofMinutes(10));
    }

    private static void addAuthHeaders(HttpRequest.Builder builder, String apiKey) {
        String clean = Objects.toString(apiKey, "").trim();
        if (clean.isBlank()) {
            return;
        }
        builder.header("Authorization", "Bearer " + clean);
        builder.header("X-API-Key", clean);
    }

    private static String extractContent(String json) {
        return firstNonBlank(
                JsonUtil.extractStringField(json, "content"),
                JsonUtil.extractStringField(json, "text"),
                JsonUtil.extractStringField(json, "response"));
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

    private static String ragContextFromResponse(String responseBody) {
        String context = firstNonBlank(
                JsonUtil.extractStringField(responseBody, "context"),
                JsonUtil.extractStringField(responseBody, "text_context"));
        if (!context.isBlank()) {
            return context;
        }
        return JsonUtil.pretty(responseBody);
    }

    private static String ragContextBody(TrafficSnapshot snapshot) {
        if (snapshot == null) {
            return "{}";
        }
        String request = snapshot.requestText();
        String response = snapshot.responseText();
        String methodPath = HttpText.methodAndPath(request);
        String method = "";
        String path = "";
        int space = methodPath.indexOf(' ');
        if (space > 0) {
            method = methodPath.substring(0, space);
            path = methodPath.substring(space + 1);
        }
        String target = snapshot.hostLabel();
        if (!path.isBlank()) {
            target += path.startsWith("/") ? path : "/" + path;
        }
        return "{"
                + "\"method\":" + JsonUtil.quote(method) + ","
                + "\"path\":" + JsonUtil.quote(path) + ","
                + "\"target_uri\":" + JsonUtil.quote(target) + ","
                + "\"headers\":" + jsonArray(headerNames(request)) + ","
                + "\"query_params\":" + jsonArray(queryParamNames(path)) + ","
                + "\"body_params\":" + jsonArray(bodyParamNames(request)) + ","
                + "\"cookie_names\":" + jsonArray(cookieNames(request)) + ","
                + "\"signals\":" + jsonArray(responseSignals(response))
                + "}";
    }

    private static List<String> headerNames(String message) {
        List<String> names = new ArrayList<>();
        String[] lines = HttpText.headers(message).replace("\r\n", "\n").replace('\r', '\n').split("\n");
        for (int i = 1; i < lines.length && names.size() < 40; i++) {
            String line = lines[i];
            int colon = line.indexOf(':');
            if (colon > 0) {
                names.add(line.substring(0, colon).trim());
            }
        }
        return names;
    }

    private static List<String> queryParamNames(String path) {
        String safePath = Objects.toString(path, "");
        int query = safePath.indexOf('?');
        if (query < 0 || query + 1 >= safePath.length()) {
            return List.of();
        }
        return paramNames(safePath.substring(query + 1));
    }

    private static List<String> bodyParamNames(String request) {
        String body = HttpText.body(request);
        if (body.isBlank() || body.length() > 12000) {
            return List.of();
        }
        String lowerHeaders = HttpText.headers(request).toLowerCase(Locale.ROOT);
        if (lowerHeaders.contains("application/x-www-form-urlencoded")) {
            return paramNames(body);
        }
        if (!HttpText.looksJson(body)) {
            return List.of();
        }
        Set<String> names = new LinkedHashSet<>();
        java.util.regex.Matcher matcher = Pattern.compile("\"([A-Za-z0-9_.-]{1,80})\"\\s*:").matcher(body);
        while (matcher.find() && names.size() < 40) {
            names.add(matcher.group(1));
        }
        return new ArrayList<>(names);
    }

    private static List<String> paramNames(String encoded) {
        Set<String> names = new LinkedHashSet<>();
        for (String part : Objects.toString(encoded, "").split("&")) {
            if (names.size() >= 40) {
                break;
            }
            int equals = part.indexOf('=');
            String name = equals >= 0 ? part.substring(0, equals) : part;
            name = name.trim();
            if (!name.isBlank()) {
                names.add(name);
            }
        }
        return new ArrayList<>(names);
    }

    private static List<String> cookieNames(String request) {
        String cookie = "";
        for (String line : HttpText.headers(request).replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith("cookie:")) {
                cookie = line.substring(line.indexOf(':') + 1).trim();
                break;
            }
        }
        Set<String> names = new LinkedHashSet<>();
        for (String part : cookie.split(";")) {
            if (names.size() >= 40) {
                break;
            }
            int equals = part.indexOf('=');
            String name = equals >= 0 ? part.substring(0, equals) : part;
            name = name.trim();
            if (!name.isBlank()) {
                names.add(name);
            }
        }
        return new ArrayList<>(names);
    }

    private static List<String> responseSignals(String response) {
        List<String> signals = new ArrayList<>();
        String firstLine = firstResponseLine(response);
        if (!firstLine.isBlank()) {
            signals.add(firstLine);
        }
        for (String name : headerNames(response)) {
            if (signals.size() >= 20) {
                break;
            }
            signals.add("response-header:" + name);
        }
        return signals;
    }

    private static String firstResponseLine(String value) {
        String text = Objects.toString(value, "").replace("\r\n", "\n").replace('\r', '\n').trim();
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline).trim();
    }

    private static String jsonArray(List<String> values) {
        StringBuilder out = new StringBuilder("[");
        int count = 0;
        for (String value : values) {
            String clean = Objects.toString(value, "").trim();
            if (clean.isBlank()) {
                continue;
            }
            if (count++ > 0) {
                out.append(',');
            }
            out.append(JsonUtil.quote(clean));
        }
        return out.append(']').toString();
    }

    private static String chatBody(CockpitSettings settings, String systemPrompt, String userPrompt, boolean stream) {
        int maxTokens = Math.max(256, settings.tokenBudget());
        boolean thinking = settings.includeThinking();
        String system = (thinking ? "/think\n" : "/no_think\n") + systemPrompt;
        String user = (thinking ? "/think\n" : "/no_think\n") + userPrompt;
        return "{"
                + "\"model\":" + JsonUtil.quote(settings.model()) + ","
                + "\"temperature\":0.2,"
                + "\"top_p\":0.85,"
                + "\"max_tokens\":" + maxTokens + ","
                + "\"stream\":" + stream + ","
                + "\"chat_template_kwargs\":{\"enable_thinking\":" + thinking + "},"
                + "\"messages\":["
                + "{\"role\":\"system\",\"content\":" + JsonUtil.quote(system) + "},"
                + "{\"role\":\"user\",\"content\":" + JsonUtil.quote(user) + "}"
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

    private static final Pattern THINK_BLOCK = Pattern.compile("(?is)<think>.*?</think>");

    private static String stripThinkBlocks(String value) {
        return THINK_BLOCK.matcher(Objects.toString(value, "")).replaceAll("");
    }

    private static final class ThinkStripper {
        private boolean insideThink;
        private String carry = "";

        String filter(String token) {
            String input = carry + Objects.toString(token, "");
            carry = "";
            StringBuilder out = new StringBuilder();
            int i = 0;
            while (i < input.length()) {
                String lower = input.substring(i).toLowerCase();
                if (insideThink) {
                    int end = lower.indexOf("</think>");
                    if (end < 0) {
                        carry = tailPossibleTag(input.substring(Math.max(i, input.length() - 16)));
                        return out.toString();
                    }
                    i += end + "</think>".length();
                    insideThink = false;
                } else {
                    int start = lower.indexOf("<think>");
                    if (start < 0) {
                        String safe = input.substring(i);
                        String possible = tailPossibleTag(safe);
                        if (!possible.isEmpty()) {
                            out.append(safe, 0, safe.length() - possible.length());
                            carry = possible;
                        } else {
                            out.append(safe);
                        }
                        return out.toString();
                    }
                    out.append(input, i, i + start);
                    i += start + "<think>".length();
                    insideThink = true;
                }
            }
            return out.toString();
        }

        private static String tailPossibleTag(String value) {
            String text = Objects.toString(value, "");
            int start = Math.max(0, text.length() - 16);
            String tail = text.substring(start);
            String lower = tail.toLowerCase();
            String[] tags = {"<think>", "</think>"};
            for (String tag : tags) {
                for (int len = Math.min(tag.length() - 1, lower.length()); len > 0; len--) {
                    if (tag.startsWith(lower.substring(lower.length() - len))) {
                        return tail.substring(tail.length() - len);
                    }
                }
            }
            return "";
        }
    }
}
