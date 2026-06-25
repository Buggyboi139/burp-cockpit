package com.buggyboi.burpcockpit.util;

import burp.api.montoya.http.HttpService;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static burp.api.montoya.http.HttpService.httpService;

public final class HttpText {
    private static final Pattern HOST_HEADER = Pattern.compile("(?im)^Host\\s*:\\s*([^\\r\\n]+)\\s*$");
    private static final Pattern FIRST_LINE = Pattern.compile("^([A-Z]+)\\s+([^\\s]+)\\s+HTTP/", Pattern.CASE_INSENSITIVE);

    private HttpText() {}

    public static String normalizeLineEndings(String text) {
        return Objects.toString(text, "").replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\r\n");
    }

    public static Optional<HttpService> inferService(String requestText, boolean secureDefault) {
        String hostHeader = hostHeader(requestText);
        if (hostHeader.isBlank()) {
            return Optional.empty();
        }
        String host = hostHeader;
        int port = secureDefault ? 443 : 80;
        int colon = hostHeader.lastIndexOf(':');
        if (colon > 0 && colon < hostHeader.length() - 1 && hostHeader.indexOf(']') < colon) {
            host = hostHeader.substring(0, colon).trim();
            try {
                port = Integer.parseInt(hostHeader.substring(colon + 1).trim());
            } catch (NumberFormatException ignored) {
                port = secureDefault ? 443 : 80;
            }
        }
        boolean secure = port == 443 || secureDefault;
        return Optional.of(httpService(host, port, secure));
    }

    public static String hostHeader(String requestText) {
        Matcher matcher = HOST_HEADER.matcher(Objects.toString(requestText, ""));
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    public static String methodAndPath(String requestText) {
        Matcher matcher = FIRST_LINE.matcher(Objects.toString(requestText, ""));
        if (!matcher.find()) {
            return "";
        }
        return matcher.group(1).toUpperCase(Locale.ROOT) + " " + matcher.group(2);
    }

    public static String body(String message) {
        String text = Objects.toString(message, "");
        int crlf = text.indexOf("\r\n\r\n");
        if (crlf >= 0) {
            return text.substring(crlf + 4);
        }
        int lf = text.indexOf("\n\n");
        if (lf >= 0) {
            return text.substring(lf + 2);
        }
        return "";
    }

    public static String headers(String message) {
        String text = Objects.toString(message, "");
        int crlf = text.indexOf("\r\n\r\n");
        if (crlf >= 0) {
            return text.substring(0, crlf);
        }
        int lf = text.indexOf("\n\n");
        if (lf >= 0) {
            return text.substring(0, lf);
        }
        return text;
    }

    public static String withBody(String message, String newBody) {
        String normalized = Objects.toString(message, "");
        String split = normalized.contains("\r\n\r\n") ? "\r\n\r\n" : "\n\n";
        int idx = normalized.indexOf(split);
        if (idx < 0) {
            return normalized + split + Objects.toString(newBody, "");
        }
        return normalized.substring(0, idx + split.length()) + Objects.toString(newBody, "");
    }

    public static boolean looksJson(String body) {
        String trimmed = Objects.toString(body, "").trim();
        return (trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"));
    }

    public static String shortSummary(String requestText, HttpService service) {
        String methodPath = methodAndPath(requestText);
        String host = service == null ? hostHeader(requestText) : service.host();
        if (host == null || host.isBlank()) {
            host = "unknown-host";
        }
        if (methodPath.isBlank()) {
            return host;
        }
        String truncated = methodPath.length() > 96 ? methodPath.substring(0, 96) + "..." : methodPath;
        return truncated + " @ " + host;
    }
}
