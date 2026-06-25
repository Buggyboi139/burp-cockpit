package com.buggyboi.burpcockpit.util;

import com.buggyboi.burpcockpit.state.CockpitState;
import com.buggyboi.burpcockpit.state.TrafficSnapshot;

import java.util.Objects;

public final class PromptBuilder {
    private static final int CHARS_PER_TOKEN = 4;
    public static final int REQUEST_HEAD_TOKENS = 4000;
    public static final int REQUEST_TAIL_TOKENS = 4000;
    public static final int RESPONSE_HEAD_TOKENS = 4000;
    public static final int RESPONSE_TAIL_TOKENS = 4000;
    public static final int NOTES_CONTEXT_TOKENS = 10000;
    public static final int RAG_HEAD_TOKENS = 4000;
    public static final int RAG_TAIL_TOKENS = 4000;

    private PromptBuilder() {}

    public static String systemPrompt(boolean thinkingEnabled) {
        return "You are Lumara Cockpit inside Burp Suite. Be concise, precise, and operational. "
                + "The user's latest message is the primary instruction. Use Burp request/response, notes, and RAG only when they are relevant to that message. "
                + "If the user asks a normal UI, workflow, or casual question, answer that question directly instead of forcing HTTP analysis. "
                + "Assume authorized manual web security research when the user asks about the captured traffic. "
                + "Do not claim to have sent traffic. Do not invent endpoints, parameters, responses, or program rules. "
                + "For traffic analysis, tie claims to visible method, path, host, headers, cookies, parameters, body values, status, and response metadata. "
                + "Format all final answers as plain text, not Markdown. Use short headings on their own lines and dash bullets on their own lines. "
                + "Do not use bold, italics, tables, or giant paragraphs. Keep each bullet to one idea. "
                + (thinkingEnabled
                ? "Reasoning mode is enabled. Think internally if useful, but keep the final answer terse and actionable. "
                : "Reasoning mode is disabled. Do not think step-by-step. Do not narrate reasoning. Start the final answer directly and keep it terse. ");
    }

    public static String analysisPrompt(CockpitState state, String userInstruction, String pinnedNote, String ragDump) {
        StringBuilder prompt = new StringBuilder();
        appendThinkingControl(prompt, state.settings().includeThinking());
        appendLayoutRules(prompt);
        prompt.append("Analyze this single captured HTTP exchange for high-value manual bug bounty tests.\n\n");
        appendContext(prompt, state, pinnedNote, ragDump);
        prompt.append("\nUser instruction:\n").append(blankDefault(userInstruction, "Analyze this exchange."));
        prompt.append("\n\nOutput format, plain text only:\n");
        prompt.append("What matters\n");
        prompt.append("- one concise observation per line\n\n");
        prompt.append("Highest-value angles\n");
        prompt.append("- one concise test angle per line\n\n");
        prompt.append("Exact fields\n");
        prompt.append("- one parameter, header, cookie, or body field per line\n\n");
        prompt.append("Response signals\n");
        prompt.append("- one expected signal per line\n\n");
        prompt.append("Skip\n");
        prompt.append("- one low-value item per line\n");
        return prompt.toString();
    }

    public static String chatPrompt(CockpitState state, String userInstruction, String pinnedNote, String ragDump) {
        StringBuilder prompt = new StringBuilder();
        appendThinkingControl(prompt, state.settings().includeThinking());
        appendLayoutRules(prompt);
        prompt.append("Answer the user's latest message first. Do not ignore it.\n");
        prompt.append("Use the Burp context only if it helps answer that exact message.\n\n");
        prompt.append("User message:\n").append(blankDefault(userInstruction, "What should I test next?")).append("\n\n");
        prompt.append("Optional Burp context follows. Treat it as supporting context, not the user's instruction.\n\n");
        appendContext(prompt, state, pinnedNote, ragDump);
        return prompt.toString();
    }

    public static String ragQuery(CockpitState state, String userInstruction) {
        TrafficSnapshot snapshot = state.current().orElse(null);
        if (snapshot == null) {
            return blankDefault(userInstruction, "current HTTP exchange");
        }
        StringBuilder query = new StringBuilder();
        String instruction = Objects.toString(userInstruction, "").trim();
        if (!instruction.isBlank()) {
            query.append(instruction).append(' ');
        }
        String methodPath = HttpText.methodAndPath(snapshot.requestText());
        if (!methodPath.isBlank()) {
            query.append(methodPath).append(' ');
        }
        query.append(snapshot.hostLabel()).append(' ');
        String body = HttpText.body(snapshot.requestText());
        if (!body.isBlank()) {
            query.append(headTail(body, 300, 300)).append(' ');
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

    private static void appendLayoutRules(StringBuilder prompt) {
        prompt.append("Final answer layout rules:\n");
        prompt.append("Use plain text only. No Markdown emphasis. No tables. No giant paragraphs.\n");
        prompt.append("Put every heading on its own line. Put every bullet on its own line using '- '.\n");
        prompt.append("Leave a blank line between sections. Keep each bullet short and operational.\n\n");
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
                    .append(headTail(DiffUtil.lineDiff(state.lastPromptRequest(), currentRequest, Integer.MAX_VALUE), 1000, 1000))
                    .append("\n````\n");
        }
        prompt.append("Current request:\n````http\n").append(requestContext(currentRequest)).append("\n````\n");
        if (!snapshot.responseText().isBlank()) {
            prompt.append("Current response:\n````http\n").append(responseContext(snapshot.responseText())).append("\n````\n");
        }
        if (pinnedNote != null && !pinnedNote.isBlank()) {
            prompt.append("Pinned notes:\n````markdown\n").append(notesContext(pinnedNote)).append("\n````\n");
        }
        if (ragDump != null && !ragDump.isBlank()) {
            prompt.append("Auto-injected RAG context:\n````text\n").append(ragContext(ragDump)).append("\n````\n");
        }
    }

    public static String requestContext(String value) {
        return headTail(value, REQUEST_HEAD_TOKENS, REQUEST_TAIL_TOKENS);
    }

    public static String responseContext(String value) {
        return headTail(value, RESPONSE_HEAD_TOKENS, RESPONSE_TAIL_TOKENS);
    }

    public static String notesContext(String value) {
        return firstTokens(value, NOTES_CONTEXT_TOKENS);
    }

    public static String ragContext(String value) {
        if (calledFromContextCounter()) {
            return "";
        }
        return headTail(value, RAG_HEAD_TOKENS, RAG_TAIL_TOKENS);
    }

    public static int estimatedTokens(String value) {
        return Math.max(0, Objects.toString(value, "").length() / CHARS_PER_TOKEN);
    }

    public static String firstTokens(String value, int tokens) {
        String text = Objects.toString(value, "");
        int maxChars = Math.max(0, tokens * CHARS_PER_TOKEN);
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n\n[... truncated at ~" + tokens + " tokens from " + text.length() + " chars ...]";
    }

    public static String headTail(String value, int headTokens, int tailTokens) {
        String text = Objects.toString(value, "");
        int headChars = Math.max(0, headTokens * CHARS_PER_TOKEN);
        int tailChars = Math.max(0, tailTokens * CHARS_PER_TOKEN);
        int maxChars = headChars + tailChars;
        if (text.length() <= maxChars) {
            return text;
        }
        String head = text.substring(0, Math.min(headChars, text.length()));
        String tail = text.substring(Math.max(0, text.length() - tailChars));
        return head + "\n\n[... middle truncated from " + text.length() + " chars; kept first ~" + headTokens + " tokens and last ~" + tailTokens + " tokens ...]\n\n" + tail;
    }

    public static String limit(String value, int maxChars) {
        String text = Objects.toString(value, "");
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, Math.max(0, maxChars)) + "\n[truncated from " + text.length() + " chars]";
    }

    private static boolean calledFromContextCounter() {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            if ("com.buggyboi.burpcockpit.ui.CockpitPanel".equals(frame.getClassName())
                    && "updateContextCounter".equals(frame.getMethodName())) {
                return true;
            }
        }
        return false;
    }

    private static String blankDefault(String value, String fallback) {
        String clean = Objects.toString(value, "").trim();
        return clean.isBlank() ? fallback : clean;
    }
}
