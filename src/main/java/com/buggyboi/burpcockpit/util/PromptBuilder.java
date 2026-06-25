package com.buggyboi.burpcockpit.util;

import com.buggyboi.burpcockpit.state.CockpitState;
import com.buggyboi.burpcockpit.state.TrafficSnapshot;

import java.util.Objects;

public final class PromptBuilder {
    private PromptBuilder() {}

    public static String systemPrompt(boolean thinkingEnabled) {
        return "You are Lumara Cockpit inside Burp Suite. Be concise, precise, and operational. "
                + "Assume authorized security research. Focus on concrete request/response evidence, attack surface, and next tests. "
                + "Do not invent results. If evidence is missing, say what is missing. "
                + (thinkingEnabled ? "Reason carefully, but keep final output terse. " : "Do not include hidden reasoning or chain-of-thought. ");
    }

    public static String analysisPrompt(CockpitState state, String userInstruction, String pinnedNote) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Run a structured web security analysis on the current Burp message. ");
        prompt.append("Prioritize bugs worth manually testing, not scanner noise.\n\n");
        appendContext(prompt, state, pinnedNote);
        prompt.append("\nUser instruction:\n").append(blankDefault(userInstruction, "Analyze this exchange."));
        prompt.append("\n\nOutput format:\n");
        prompt.append("1. What changed / what matters\n");
        prompt.append("2. Highest-value bug angles\n");
        prompt.append("3. Exact requests or parameters to mutate\n");
        prompt.append("4. Evidence to look for in responses\n");
        prompt.append("5. Dead ends / low-value tests\n");
        return prompt.toString();
    }

    public static String chatPrompt(CockpitState state, String userInstruction, String pinnedNote) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Answer as a security teammate using the current Burp context.\n\n");
        appendContext(prompt, state, pinnedNote);
        prompt.append("\nUser message:\n").append(blankDefault(userInstruction, "What should I test next?"));
        return prompt.toString();
    }

    public static String editPrompt(CockpitState state, String userInstruction, String draftText, String pinnedNote) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Edit the raw HTTP request below. Return only the complete raw HTTP request. No markdown. No explanation.\n\n");
        appendContext(prompt, state, pinnedNote);
        prompt.append("\nEdit instruction:\n").append(blankDefault(userInstruction, "Make a useful security-test mutation."));
        prompt.append("\n\nDraft request to edit:\n````http\n").append(Objects.toString(draftText, "")).append("\n````\n");
        return prompt.toString();
    }

    private static void appendContext(StringBuilder prompt, CockpitState state, String pinnedNote) {
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
    }

    private static String blankDefault(String value, String fallback) {
        String clean = Objects.toString(value, "").trim();
        return clean.isBlank() ? fallback : clean;
    }
}
