package com.buggyboi.burpcockpit.util;

public final class JsonUtil {
    private JsonUtil() {}

    public static String quote(String value) {
        return "\"" + escape(value) + "\"";
    }

    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 32);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        return out.toString();
    }

    public static String unescapeJsonString(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch != '\\' || i + 1 >= value.length()) {
                out.append(ch);
                continue;
            }
            char next = value.charAt(++i);
            switch (next) {
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (i + 4 < value.length()) {
                        String hex = value.substring(i + 1, i + 5);
                        try {
                            out.append((char) Integer.parseInt(hex, 16));
                            i += 4;
                        } catch (NumberFormatException ignored) {
                            out.append("\\u").append(hex);
                            i += 4;
                        }
                    } else {
                        out.append("\\u");
                    }
                }
                default -> out.append(next);
            }
        }
        return out.toString();
    }

    public static String extractStringField(String json, String field) {
        if (json == null || field == null || field.isBlank()) {
            return "";
        }
        String needle = "\"" + field + "\"";
        int idx = json.indexOf(needle);
        while (idx >= 0) {
            int colon = json.indexOf(':', idx + needle.length());
            if (colon < 0) {
                return "";
            }
            int start = colon + 1;
            while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
                start++;
            }
            if (start >= json.length() || json.charAt(start) != '"') {
                idx = json.indexOf(needle, start);
                continue;
            }
            start++;
            StringBuilder raw = new StringBuilder();
            boolean escaped = false;
            for (int i = start; i < json.length(); i++) {
                char ch = json.charAt(i);
                if (escaped) {
                    raw.append('\\').append(ch);
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    return unescapeJsonString(raw.toString());
                } else {
                    raw.append(ch);
                }
            }
            return "";
        }
        return "";
    }

    public static String pretty(String json) {
        if (json == null || json.isBlank()) {
            return json == null ? "" : json;
        }
        StringBuilder out = new StringBuilder(json.length() * 2);
        int indent = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (escaped) {
                out.append(ch);
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                out.append(ch);
                escaped = inString;
                continue;
            }
            if (ch == '"') {
                out.append(ch);
                inString = !inString;
                continue;
            }
            if (inString) {
                out.append(ch);
                continue;
            }
            switch (ch) {
                case '{', '[' -> {
                    out.append(ch).append('\n');
                    indent++;
                    appendIndent(out, indent);
                }
                case '}', ']' -> {
                    out.append('\n');
                    indent = Math.max(0, indent - 1);
                    appendIndent(out, indent);
                    out.append(ch);
                }
                case ',' -> {
                    out.append(ch).append('\n');
                    appendIndent(out, indent);
                }
                case ':' -> out.append(": ");
                default -> {
                    if (!Character.isWhitespace(ch)) {
                        out.append(ch);
                    }
                }
            }
        }
        return out.toString();
    }

    private static void appendIndent(StringBuilder out, int indent) {
        out.append("  ".repeat(Math.max(0, indent)));
    }
}
