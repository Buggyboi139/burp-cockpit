package com.buggyboi.burpcockpit.util;

import com.buggyboi.burpcockpit.state.CockpitSettings;
import com.buggyboi.burpcockpit.state.CockpitState;
import com.buggyboi.burpcockpit.state.TrafficSnapshot;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

public final class PromptBuilderTest {
    public static void main(String[] args) throws Exception {
        defaultsPreserveCurrentTruncation();
        customCapsChangeChatAndAnalyzeTruncation();
        blankSystemPromptsUseDefaults();
        customSystemPromptsAreModeSpecific();
    }

    private static void defaultsPreserveCurrentTruncation() throws Exception {
        TestPrefs prefs = testSettings();
        CockpitSettings settings = prefs.settings();
        String request = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n" + repeat("a", 40000);
        String response = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\n" + repeat("b", 40000);
        TrafficSnapshot snapshot = new TrafficSnapshot(null, request, response, Instant.now(), "test");

        String analyze = PromptBuilder.buildAnalyzeContext(snapshot, settings, false, "", "");
        assertContains(analyze, "kept first ~4000 tokens and last ~4000 tokens", "default analyze caps");

        String chat = PromptBuilder.buildChatContext(snapshot, settings, false, "", "");
        assertContains(chat, "kept first ~1200 tokens and last ~1200 tokens", "default chat request caps");
        assertContains(chat, "kept first ~800 tokens and last ~800 tokens", "default chat response caps");
    }

    private static void customCapsChangeChatAndAnalyzeTruncation() throws Exception {
        TestPrefs prefs = testSettings();
        CockpitSettings settings = prefs.settings();
        settings.chatRequestHeadTokens(1);
        settings.chatRequestTailTokens(2);
        settings.chatResponseHeadTokens(3);
        settings.chatResponseTailTokens(4);
        settings.chatResponseExcerptChars(6);
        settings.chatNotesTokens(1);
        settings.chatRagTokens(1);
        settings.analyzeRequestHeadTokens(5);
        settings.analyzeRequestTailTokens(6);
        settings.analyzeResponseHeadTokens(7);
        settings.analyzeResponseTailTokens(8);
        settings.analyzeNotesTokens(1);
        settings.analyzeRagHeadTokens(2);
        settings.analyzeRagTailTokens(3);

        String request = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n" + repeat("r", 120);
        String response = "HTTP/1.1 200 OK\r\n\r\n" + repeat("s", 120);
        TrafficSnapshot snapshot = new TrafficSnapshot(null, request, response, Instant.now(), "test");

        String chatContext = PromptBuilder.buildChatContext(snapshot, settings, false, "", "");
        assertContains(chatContext, "kept first ~1 tokens and last ~2 tokens", "custom chat request caps");
        assertContains(chatContext, "kept first ~3 tokens and last ~4 tokens", "custom chat response caps");
        assertContains(chatContext, "[truncated from 120 chars]", "custom chat response excerpt cap");

        String analyzeContext = PromptBuilder.buildAnalyzeContext(snapshot, settings, false, "", "");
        assertContains(analyzeContext, "kept first ~5 tokens and last ~6 tokens", "custom analyze request caps");
        assertContains(analyzeContext, "kept first ~7 tokens and last ~8 tokens", "custom analyze response caps");

        CockpitState state = new CockpitState(null, settings, null);
        state.pushSnapshot(snapshot);
        String chatPrompt = PromptBuilder.chatPrompt(state, snapshot, "test", repeat("n", 20), repeat("g", 20));
        assertContains(chatPrompt, "truncated at ~1 tokens", "custom chat note/rag caps");
        String analyzePrompt = PromptBuilder.analysisPrompt(state, snapshot, "test", repeat("n", 20), repeat("g", 40));
        assertContains(analyzePrompt, "truncated at ~1 tokens", "custom analyze notes cap");
        assertContains(analyzePrompt, "kept first ~2 tokens and last ~3 tokens", "custom analyze rag caps");
    }

    private static void blankSystemPromptsUseDefaults() throws Exception {
        TestPrefs prefs = testSettings();
        CockpitSettings settings = prefs.settings();
        settings.chatSystemPrompt(" ");
        settings.analyzeSystemPrompt("");
        assertEquals(PromptBuilder.defaultChatSystemPrompt(), PromptBuilder.effectiveChatSystemPrompt(settings), "blank chat system prompt fallback");
        assertEquals(PromptBuilder.defaultAnalyzeSystemPrompt(), PromptBuilder.effectiveAnalyzeSystemPrompt(settings), "blank analyze system prompt fallback");
    }

    private static void customSystemPromptsAreModeSpecific() throws Exception {
        TestPrefs prefs = testSettings();
        CockpitSettings settings = prefs.settings();
        settings.chatSystemPrompt("CHAT CUSTOM");
        settings.analyzeSystemPrompt("ANALYZE CUSTOM");
        assertContains(PromptBuilder.systemPrompt(settings, false, false), "CHAT CUSTOM", "custom chat system prompt");
        assertContains(PromptBuilder.systemPrompt(settings, false, true), "ANALYZE CUSTOM", "custom analyze system prompt");
        assertNotContains(PromptBuilder.systemPrompt(settings, false, false), "ANALYZE CUSTOM", "chat prompt does not use analyze prompt");
        assertNotContains(PromptBuilder.systemPrompt(settings, false, true), "CHAT CUSTOM", "analyze prompt does not use chat prompt");
    }

    private static TestPrefs testSettings() {
        Preferences prefs = new MemoryPreferences();
        return new TestPrefs(new CockpitSettings(prefs), prefs);
    }

    private record TestPrefs(CockpitSettings settings, Preferences node) {}

    private static final class MemoryPreferences extends AbstractPreferences {
        private final Map<String, String> values = new HashMap<>();
        private final Map<String, MemoryPreferences> children = new HashMap<>();

        MemoryPreferences() {
            super(null, "");
        }

        private MemoryPreferences(AbstractPreferences parent, String name) {
            super(parent, name);
        }

        @Override
        protected void putSpi(String key, String value) {
            values.put(key, value);
        }

        @Override
        protected String getSpi(String key) {
            return values.get(key);
        }

        @Override
        protected void removeSpi(String key) {
            values.remove(key);
        }

        @Override
        protected void removeNodeSpi() {
            values.clear();
            children.clear();
        }

        @Override
        protected String[] keysSpi() {
            return values.keySet().toArray(String[]::new);
        }

        @Override
        protected String[] childrenNamesSpi() {
            return children.keySet().toArray(String[]::new);
        }

        @Override
        protected AbstractPreferences childSpi(String name) {
            return children.computeIfAbsent(name, child -> new MemoryPreferences(this, child));
        }

        @Override
        protected void syncSpi() throws BackingStoreException {}

        @Override
        protected void flushSpi() throws BackingStoreException {}
    }

    private static String repeat(String value, int count) {
        return value.repeat(count);
    }

    private static void assertContains(String value, String expected, String label) {
        if (!value.contains(expected)) {
            throw new AssertionError(label + ": expected to contain [" + expected + "] but got [" + value + "]");
        }
    }

    private static void assertNotContains(String value, String unexpected, String label) {
        if (value.contains(unexpected)) {
            throw new AssertionError(label + ": expected not to contain [" + unexpected + "] but got [" + value + "]");
        }
    }

    private static void assertEquals(String expected, String actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected [" + expected + "] but got [" + actual + "]");
        }
    }
}
