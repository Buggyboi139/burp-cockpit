package com.buggyboi.burpcockpit.state;

import java.nio.file.Path;
import java.util.Objects;
import java.util.prefs.Preferences;

public final class CockpitSettings {
    private static final String NODE = "com.buggyboi.burpcockpit";

    private static final String OLD_CHAT_ENDPOINT = "http://127.0.0.1:8080/v1/chat/completions";
    private static final String OLD_RAG_SEARCH_ENDPOINT = "http://127.0.0.1:8765/rag/search";

    // In the Kali VM, 127.0.0.1:8080 is usually Burp itself. The host-side llama.cpp/OpenLumara
    // endpoint is normally reachable through VirtualBox NAT at 10.0.2.2. Users running everything
    // on one desktop can still change this from settings.
    private static final String DEFAULT_CHAT_ENDPOINT = "http://10.0.2.2:8080/v1/chat/completions";
    private static final String DEFAULT_MODEL = "default";
    private static final String DEFAULT_RAG_SEARCH_ENDPOINT = "http://10.0.2.2:8765/rag/search";
    private static final String DEFAULT_NOTES_DIR = Path.of(System.getProperty("user.home"), ".burp-cockpit", "notes").toString();

    private final Preferences prefs = Preferences.userRoot().node(NODE);

    public String chatEndpoint() { return migratedEndpoint("chatEndpoint", OLD_CHAT_ENDPOINT, DEFAULT_CHAT_ENDPOINT); }
    public void chatEndpoint(String value) { prefs.put("chatEndpoint", clean(value, DEFAULT_CHAT_ENDPOINT)); }

    public String model() { return prefs.get("model", DEFAULT_MODEL); }
    public void model(String value) { prefs.put("model", clean(value, DEFAULT_MODEL)); }

    public String ragSearchEndpoint() { return migratedEndpoint("ragSearchEndpoint", OLD_RAG_SEARCH_ENDPOINT, DEFAULT_RAG_SEARCH_ENDPOINT); }
    public void ragSearchEndpoint(String value) { prefs.put("ragSearchEndpoint", clean(value, DEFAULT_RAG_SEARCH_ENDPOINT)); }

    public Path notesDirectory() { return Path.of(prefs.get("notesDirectory", DEFAULT_NOTES_DIR)); }
    public void notesDirectory(String value) { prefs.put("notesDirectory", clean(value, DEFAULT_NOTES_DIR)); }

    public boolean streamChat() { return prefs.getBoolean("streamChat", true); }
    public void streamChat(boolean value) { prefs.putBoolean("streamChat", value); }

    public boolean includeThinking() { return prefs.getBoolean("includeThinking", false); }
    public void includeThinking(boolean value) { prefs.putBoolean("includeThinking", value); }

    public int tokenBudget() { return prefs.getInt("tokenBudget", 20000); }
    public void tokenBudget(int value) { prefs.putInt("tokenBudget", Math.max(256, value)); }

    public boolean injectPinnedNote() { return prefs.getBoolean("injectPinnedNote", true); }
    public void injectPinnedNote(boolean value) { prefs.putBoolean("injectPinnedNote", value); }

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
}
