package com.buggyboi.burpcockpit.state;

import java.nio.file.Path;
import java.util.Objects;
import java.util.prefs.Preferences;

public final class CockpitSettings {
    private static final String NODE = "com.buggyboi.burpcockpit";
    public static final int DEFAULT_ANALYZE_REQUEST_HEAD_TOKENS = 4000;
    public static final int DEFAULT_ANALYZE_REQUEST_TAIL_TOKENS = 4000;
    public static final int DEFAULT_ANALYZE_RESPONSE_HEAD_TOKENS = 4000;
    public static final int DEFAULT_ANALYZE_RESPONSE_TAIL_TOKENS = 4000;
    public static final int DEFAULT_ANALYZE_NOTES_TOKENS = 10000;
    public static final int DEFAULT_ANALYZE_RAG_HEAD_TOKENS = 4000;
    public static final int DEFAULT_ANALYZE_RAG_TAIL_TOKENS = 4000;
    public static final int DEFAULT_CHAT_REQUEST_HEAD_TOKENS = 1200;
    public static final int DEFAULT_CHAT_REQUEST_TAIL_TOKENS = 1200;
    public static final int DEFAULT_CHAT_RESPONSE_HEAD_TOKENS = 800;
    public static final int DEFAULT_CHAT_RESPONSE_TAIL_TOKENS = 800;
    public static final int DEFAULT_CHAT_RESPONSE_EXCERPT_CHARS = 500;
    public static final int DEFAULT_CHAT_NOTES_TOKENS = 1200;
    public static final int DEFAULT_CHAT_RAG_TOKENS = 1400;
    public static final int DEFAULT_DELTA_HEAD_TOKENS = 1000;
    public static final int DEFAULT_DELTA_TAIL_TOKENS = 1000;

    private static final String OLD_CHAT_ENDPOINT = "http://127.0.0.1:8080/v1/chat/completions";
    private static final String OLD_RAG_SEARCH_ENDPOINT = "http://127.0.0.1:8765/rag/search";
    private static final String OLD_VM_RAG_SEARCH_ENDPOINT = "http://10.0.2.2:8765/rag/search";

    // In the Kali VM, 127.0.0.1:8080 is usually Burp itself. The host-side llama.cpp/OpenLumara
    // endpoint is normally reachable through VirtualBox NAT at 10.0.2.2. Users running everything
    // on one desktop can still change this from settings.
    private static final String DEFAULT_CHAT_ENDPOINT = "http://10.0.2.2:8080/v1/chat/completions";
    private static final String DEFAULT_MODEL = "default";
    private static final String DEFAULT_RAG_SEARCH_ENDPOINT = "http://10.0.2.2:5000/rag/search";
    private static final String DEFAULT_RAG_API_KEY = "";
    private static final String DEFAULT_NOTES_DIR = Path.of(System.getProperty("user.home"), ".burp-cockpit", "notes").toString();

    private final Preferences prefs;

    public CockpitSettings() {
        this(Preferences.userRoot().node(NODE));
    }

    public CockpitSettings(Preferences prefs) {
        this.prefs = prefs;
    }

    public String chatEndpoint() { return migratedEndpoint("chatEndpoint", OLD_CHAT_ENDPOINT, DEFAULT_CHAT_ENDPOINT); }
    public void chatEndpoint(String value) { prefs.put("chatEndpoint", clean(value, DEFAULT_CHAT_ENDPOINT)); }

    public String model() { return prefs.get("model", DEFAULT_MODEL); }
    public void model(String value) { prefs.put("model", clean(value, DEFAULT_MODEL)); }

    public String ragSearchEndpoint() {
        String value = migratedEndpoint("ragSearchEndpoint", OLD_RAG_SEARCH_ENDPOINT, DEFAULT_RAG_SEARCH_ENDPOINT);
        if (value.equals(OLD_VM_RAG_SEARCH_ENDPOINT)) {
            prefs.put("ragSearchEndpoint", DEFAULT_RAG_SEARCH_ENDPOINT);
            return DEFAULT_RAG_SEARCH_ENDPOINT;
        }
        return value;
    }
    public void ragSearchEndpoint(String value) { prefs.put("ragSearchEndpoint", clean(value, DEFAULT_RAG_SEARCH_ENDPOINT)); }

    public String ragApiKey() { return prefs.get("ragApiKey", DEFAULT_RAG_API_KEY); }
    public void ragApiKey(String value) { prefs.put("ragApiKey", clean(value, DEFAULT_RAG_API_KEY)); }

    public Path notesDirectory() { return Path.of(prefs.get("notesDirectory", DEFAULT_NOTES_DIR)); }
    public void notesDirectory(String value) { prefs.put("notesDirectory", clean(value, DEFAULT_NOTES_DIR)); }

    public boolean streamChat() { return true; }
    public void streamChat(boolean value) { prefs.putBoolean("streamChat", true); }

    public boolean includeThinking() { return prefs.getBoolean("includeThinking", false); }
    public void includeThinking(boolean value) { prefs.putBoolean("includeThinking", value); }

    public int tokenBudget() { return prefs.getInt("tokenBudget", 20000); }
    public void tokenBudget(int value) { prefs.putInt("tokenBudget", Math.max(256, value)); }

    public int analyzeRequestHeadTokens() { return intPref("analyzeRequestHeadTokens", DEFAULT_ANALYZE_REQUEST_HEAD_TOKENS, 0); }
    public void analyzeRequestHeadTokens(int value) { intPref("analyzeRequestHeadTokens", value, DEFAULT_ANALYZE_REQUEST_HEAD_TOKENS, 0); }

    public int analyzeRequestTailTokens() { return intPref("analyzeRequestTailTokens", DEFAULT_ANALYZE_REQUEST_TAIL_TOKENS, 0); }
    public void analyzeRequestTailTokens(int value) { intPref("analyzeRequestTailTokens", value, DEFAULT_ANALYZE_REQUEST_TAIL_TOKENS, 0); }

    public int analyzeResponseHeadTokens() { return intPref("analyzeResponseHeadTokens", DEFAULT_ANALYZE_RESPONSE_HEAD_TOKENS, 0); }
    public void analyzeResponseHeadTokens(int value) { intPref("analyzeResponseHeadTokens", value, DEFAULT_ANALYZE_RESPONSE_HEAD_TOKENS, 0); }

    public int analyzeResponseTailTokens() { return intPref("analyzeResponseTailTokens", DEFAULT_ANALYZE_RESPONSE_TAIL_TOKENS, 0); }
    public void analyzeResponseTailTokens(int value) { intPref("analyzeResponseTailTokens", value, DEFAULT_ANALYZE_RESPONSE_TAIL_TOKENS, 0); }

    public int analyzeNotesTokens() { return intPref("analyzeNotesTokens", DEFAULT_ANALYZE_NOTES_TOKENS, 0); }
    public void analyzeNotesTokens(int value) { intPref("analyzeNotesTokens", value, DEFAULT_ANALYZE_NOTES_TOKENS, 0); }

    public int analyzeRagHeadTokens() { return intPref("analyzeRagHeadTokens", DEFAULT_ANALYZE_RAG_HEAD_TOKENS, 0); }
    public void analyzeRagHeadTokens(int value) { intPref("analyzeRagHeadTokens", value, DEFAULT_ANALYZE_RAG_HEAD_TOKENS, 0); }

    public int analyzeRagTailTokens() { return intPref("analyzeRagTailTokens", DEFAULT_ANALYZE_RAG_TAIL_TOKENS, 0); }
    public void analyzeRagTailTokens(int value) { intPref("analyzeRagTailTokens", value, DEFAULT_ANALYZE_RAG_TAIL_TOKENS, 0); }

    public int chatRequestHeadTokens() { return intPref("chatRequestHeadTokens", DEFAULT_CHAT_REQUEST_HEAD_TOKENS, 0); }
    public void chatRequestHeadTokens(int value) { intPref("chatRequestHeadTokens", value, DEFAULT_CHAT_REQUEST_HEAD_TOKENS, 0); }

    public int chatRequestTailTokens() { return intPref("chatRequestTailTokens", DEFAULT_CHAT_REQUEST_TAIL_TOKENS, 0); }
    public void chatRequestTailTokens(int value) { intPref("chatRequestTailTokens", value, DEFAULT_CHAT_REQUEST_TAIL_TOKENS, 0); }

    public int chatResponseHeadTokens() { return intPref("chatResponseHeadTokens", DEFAULT_CHAT_RESPONSE_HEAD_TOKENS, 0); }
    public void chatResponseHeadTokens(int value) { intPref("chatResponseHeadTokens", value, DEFAULT_CHAT_RESPONSE_HEAD_TOKENS, 0); }

    public int chatResponseTailTokens() { return intPref("chatResponseTailTokens", DEFAULT_CHAT_RESPONSE_TAIL_TOKENS, 0); }
    public void chatResponseTailTokens(int value) { intPref("chatResponseTailTokens", value, DEFAULT_CHAT_RESPONSE_TAIL_TOKENS, 0); }

    public int chatResponseExcerptChars() { return intPref("chatResponseExcerptChars", DEFAULT_CHAT_RESPONSE_EXCERPT_CHARS, 0); }
    public void chatResponseExcerptChars(int value) { intPref("chatResponseExcerptChars", value, DEFAULT_CHAT_RESPONSE_EXCERPT_CHARS, 0); }

    public int chatNotesTokens() { return intPref("chatNotesTokens", DEFAULT_CHAT_NOTES_TOKENS, 0); }
    public void chatNotesTokens(int value) { intPref("chatNotesTokens", value, DEFAULT_CHAT_NOTES_TOKENS, 0); }

    public int chatRagTokens() { return intPref("chatRagTokens", DEFAULT_CHAT_RAG_TOKENS, 0); }
    public void chatRagTokens(int value) { intPref("chatRagTokens", value, DEFAULT_CHAT_RAG_TOKENS, 0); }

    public int deltaHeadTokens() { return intPref("deltaHeadTokens", DEFAULT_DELTA_HEAD_TOKENS, 0); }
    public void deltaHeadTokens(int value) { intPref("deltaHeadTokens", value, DEFAULT_DELTA_HEAD_TOKENS, 0); }

    public int deltaTailTokens() { return intPref("deltaTailTokens", DEFAULT_DELTA_TAIL_TOKENS, 0); }
    public void deltaTailTokens(int value) { intPref("deltaTailTokens", value, DEFAULT_DELTA_TAIL_TOKENS, 0); }

    public String chatSystemPrompt() { return prefs.get("chatSystemPrompt", ""); }
    public void chatSystemPrompt(String value) { putOptional("chatSystemPrompt", value); }

    public String analyzeSystemPrompt() { return prefs.get("analyzeSystemPrompt", ""); }
    public void analyzeSystemPrompt(String value) { putOptional("analyzeSystemPrompt", value); }

    public boolean injectPinnedNote() { return true; }
    public void injectPinnedNote(boolean value) { prefs.putBoolean("injectPinnedNote", true); }

    public boolean injectRag() { return prefs.getBoolean("injectRag", true); }
    public void injectRag(boolean value) { prefs.putBoolean("injectRag", value); }

    public boolean deltaOnly() { return prefs.getBoolean("deltaOnly", true); }
    public void deltaOnly(boolean value) { prefs.putBoolean("deltaOnly", value); }

    public boolean autoCaptureLatest() { return prefs.getBoolean("autoCaptureLatest", false); }
    public void autoCaptureLatest(boolean value) { prefs.putBoolean("autoCaptureLatest", value); }

    private String migratedEndpoint(String key, String oldValue, String newValue) {
        String value = prefs.get(key, newValue).trim();
        if (value.equals(oldValue)) {
            prefs.put(key, newValue);
            return newValue;
        }
        return value.isBlank() ? newValue : value;
    }

    private static String clean(String value, String fallback) {
        String cleaned = Objects.toString(value, "").trim();
        return cleaned.isBlank() ? fallback : cleaned;
    }

    private int intPref(String key, int fallback, int min) {
        return Math.max(min, prefs.getInt(key, fallback));
    }

    private void intPref(String key, int value, int fallback, int min) {
        prefs.putInt(key, value < min ? fallback : value);
    }

    private void putOptional(String key, String value) {
        String clean = Objects.toString(value, "").trim();
        if (clean.isBlank()) {
            prefs.remove(key);
        } else {
            prefs.put(key, clean);
        }
    }
}
