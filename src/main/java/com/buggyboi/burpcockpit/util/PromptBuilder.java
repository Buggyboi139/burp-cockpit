package com.buggyboi.burpcockpit.util;

import com.buggyboi.burpcockpit.state.CockpitState;
import com.buggyboi.burpcockpit.state.TrafficSnapshot;

import java.util.Objects;

public final class PromptBuilder {
    private PromptBuilder() {}

    public static String systemPrompt(boolean thinkingEnabled) {
        return "You are Lumara Cockpit inside Burp Suite. Be concise, precise, and operational. "
                + "Assume authorized manual web security research. Use only supplied request/response evidence, notes, and RAG context. "
                + "Do not claim to have sent traffic. Do not invent endpoints, parameters, responses, or program rules. "
                + "Prioritize concrete tests tied to visible method, path, host, headers, cookies, parameters, body values, status, and response metadata. "
                + (thinkingEnabled
                        ? "Reasoning mode is enabled. Think internally if useful, but keep the final answer terse and actionable. "
                        : "Reasoning mode is disabled. Do not think step-by-step. Do not narrate reasoning. Start the final answer directly and keep it terse. ");
    }

    public static String analysisPrompt(CockpitState state, String userInstruction, String pinnedNote, String ragDump) {
        StringBuilder prompt = new StringBuilder();
        appendThinkingControl(prompt, state.settings().includeThinking());
        prompt.append("Analyze this single captured HTTP exchange for high-value manual bug bounty tests.\n\n");
        appendContext(prompt, state, pinnedNote, ragDump);
        prompt.append("\nUser instruction:\n").append(blankDefault(userInstruction, "Analyze this exchange."));
        prompt.append("\n\nOutput format:\n");
        prompt.append("1. What matters in this exchange\n");
        prompt.append("2. Highest-value bug angles\n");
        prompt.append("3. Exact parameters/headers/body fields to mutate\n");
        prompt.append("4. Expected response signals\n");
        prompt.append("5. Low-value tests to skip\n");
        return prompt.toString();
    }

    public static String chatPrompt(CockpitState state, String userInstruction, String pinnedNote, String ragDump) {
        StringBuilder prompt = new StringBuilder();
        appendThinkingControl(prompt, state.settings().includeThinking());
        prompt.append("Answer as a security teammate using the current Burp context.\n\n");
        appendContext(prompt, state, pinnedNote, ragDump);
        prompt.append("\nUser message:\n").append(blankDefault(userInstruction, "What should I test next?"));
        return prompt.toString();
    }

    public static String ragQuery(CockpitState state, String userInstruction) {
        TrafficSnapshot snapshot = state.current().orElse(null);
        if (snapshot == null) {
            return blankDefault(userInstruction, "current HTTP exchange");
        }
        StringBuilder query = new StringBuilder();
        String methodPath = HttpText.methodAndPath(snapshot.requestText());
        if (!methodPath.isBlank()) {
            query.append(methodPath).append(' ');
        }
        query.append(snapshot.hostLabel()).append(' ');
        String body = HttpText.body(snapshot.requestText());
        if (!body.isBlank()) {
            query.append(limit(body, 1200)).append(' ');
        }
        String instruction = Objects.toString(userInstruction, "").trim();
        if (!instruction.isBlank()) {
            query.append(instruction);
        }
        return query.toString().trim();
    }

    private static void appendThinkingControl(StringBuilder prompt, boolean thinkingEnabled) {
        if (thinkingEnabled) {
            prompt.append("/think\n");
            prompt.append("Reasoning mode is enabled. Use it internally if helpful, but do not print raw reasoning.\n\n");
        } else {
            prompt.append("/no_think\n");
            prompt.append("Reasoning mode is disabled. Do not think step-by-step. Do not print reasoning. Answer directly.\n\n");
        }
    }

    private static void appendContext(StringBuilder prompt, CockpitState state, String pinnedNote, String ragDump) {
        TrafficSnapshot snapshot = state.current().orElse(null);
        if (snapshot == null) {
            prompt.append("No current request loaded.\n");
            return;
        }
        String currentRequest = snapshot.requestText();
        prompt.append("Current target: ").append(HttpText.shortSummary(currentRequest, snapshot.service())).append("\n");
        if (state.settings().deltaOnly()) {
            prompt.append("Request delta from last prompt:\n````diff\n")
                    .append(DiffUtil.lineDiff(state.lastPromptRequest(), currentRequest, Integer.MAX_VALUE))
                    .append("\n````\n");
        }
        prompt.append("Current request:\n````http\n").append(currentRequest).append("\n````\n");
        if (!snapshot.responseText().isBlank()) {
            prompt.append("Current response:\n````http\n").append(snapshot.responseText()).append("\n````\n");
        }
        if (pinnedNote != null && !pinnedNote.isBlank()) {
            prompt.append("Pinned notes:\n````markdown\n").append(pinnedNote).append("\n````\n");
        }
        if (ragDump != null && !ragDump.isBlank()) {
            prompt.append("Auto-injected RAG context:\n````text\n").append(limit(ragDump, 18000)).append("\n````\n");
        }
    }

    public static int estimatedTokens(String value) {
        return Math.max(0, Objects.toString(value, "").length() / 4);
    }

    public static String limit(String value, int maxChars) {
        String text = Objects.toString(value, "");
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, Math.max(0, maxChars)) + "\n[truncated]";
    }

    private static String blankDefault(String value, String fallback) {
        String clean = Objects.toString(value, "").trim();
        return clean.isBlank() ? fallback : clean;
    }
}
