package com.buggyboi.burpcockpit.ui;

import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.text.JTextComponent;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class TextContextMenu {
    private TextContextMenu() {}

    public static void install(JTextComponent component) {
        if (component == null) return;
        component.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) { maybeShow(event); }
            @Override public void mouseReleased(MouseEvent event) { maybeShow(event); }

            private void maybeShow(MouseEvent event) {
                if (!event.isPopupTrigger()) return;
                JPopupMenu menu = new JPopupMenu();
                JMenuItem cut = new JMenuItem("Cut");
                cut.addActionListener(e -> component.cut());
                cut.setEnabled(component.isEditable() && component.isEnabled());
                JMenuItem copy = new JMenuItem("Copy");
                copy.addActionListener(e -> component.copy());
                copy.setEnabled(component.isEnabled());
                JMenuItem paste = new JMenuItem("Paste");
                paste.addActionListener(e -> component.paste());
                paste.setEnabled(component.isEditable() && component.isEnabled());
                JMenuItem selectAll = new JMenuItem("Select all");
                selectAll.addActionListener(e -> component.selectAll());
                menu.add(cut);
                menu.add(copy);
                menu.add(paste);
                menu.addSeparator();
                menu.add(selectAll);
                menu.show(event.getComponent(), event.getX(), event.getY());
            }
        });
    }

    public static JTextArea area(int rows, int cols, boolean editable) {
        JTextArea area;
        if (isPromptInput(rows, cols, editable)) {
            area = new PromptArea(rows, cols);
        } else {
            area = new JTextArea(rows, cols);
        }
        area.setEditable(editable);
        boolean visualWrap = isResponseViewer(rows, cols, editable);
        area.setLineWrap(visualWrap);
        area.setWrapStyleWord(visualWrap);
        install(area);
        return area;
    }

    public static ChatTranscriptPane transcript(int rows, int cols) {
        ChatTranscriptPane pane = new ChatTranscriptPane(rows, cols);
        install(pane);
        return pane;
    }

    public static JTextField field(String text) {
        JTextField field = new JTextField(text == null ? "" : text);
        install(field);
        return field;
    }

    public static void later(Runnable runnable) {
        if (SwingUtilities.isEventDispatchThread()) runnable.run();
        else SwingUtilities.invokeLater(runnable);
    }

    private static boolean isPromptInput(int rows, int cols, boolean editable) {
        return editable && rows == 4 && cols == 70;
    }

    private static boolean isResponseViewer(int rows, int cols, boolean editable) {
        return !editable && rows == 12 && cols == 90;
    }

    public static final class ChatTranscriptPane extends JEditorPane {
        private static final int TARGET_PARAGRAPH_CHARS = 260;
        private static final int HARD_WRAP_CHARS = 48;

        private final List<ChatCard> cards = new ArrayList<>();
        private int activeAssistantIndex = -1;
        private boolean rendering;

        private ChatTranscriptPane(int rows, int cols) {
            setContentType("text/html");
            setEditable(false);
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
            setPreferredSize(new Dimension(cols * 8, rows * 18));
            render();
        }

        @Override public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        public void startTurn(String userRole, String userPrompt, boolean analysis) {
            Instant now = Instant.now();
            activeAssistantIndex = -1;
            cards.add(new ChatCard(userRole, Objects.toString(userPrompt, "").trim(), analysis, now));
            cards.add(new ChatCard(analysis ? "Analyze" : "Assistant", "", analysis, now));
            activeAssistantIndex = cards.size() - 1;
            render();
        }

        public void appendAssistantText(String text) {
            if (activeAssistantIndex < 0 || activeAssistantIndex >= cards.size()) {
                cards.add(new ChatCard("Assistant", "", false, Instant.now()));
                activeAssistantIndex = cards.size() - 1;
            }
            ChatCard card = cards.get(activeAssistantIndex);
            cards.set(activeAssistantIndex, new ChatCard(card.role(), card.content() + Objects.toString(text, ""), card.analysis(), card.timestamp()));
            render();
        }

        public void replaceActiveAssistantText(String text) {
            if (activeAssistantIndex < 0 || activeAssistantIndex >= cards.size()) {
                cards.add(new ChatCard("Assistant", Objects.toString(text, ""), false, Instant.now()));
                activeAssistantIndex = cards.size() - 1;
            } else {
                ChatCard card = cards.get(activeAssistantIndex);
                cards.set(activeAssistantIndex, new ChatCard(card.role(), Objects.toString(text, ""), card.analysis(), card.timestamp()));
            }
            render();
        }

        public void clearTranscript() {
            cards.clear();
            activeAssistantIndex = -1;
            render();
        }

        @Override public void setText(String text) {
            if (!rendering && (text == null || text.isBlank())) {
                clearTranscript();
                return;
            }
            super.setText(text);
        }

        @Override public String getText() {
            if (cards.isEmpty()) return "";
            StringBuilder out = new StringBuilder();
            for (ChatCard card : cards) {
                if (!out.isEmpty()) out.append("\n\n");
                out.append(card.role()).append("\n").append(card.content());
            }
            return out.toString();
        }

        private void render() {
            String background = uiColor("Panel.background", "#202124");
            String foreground = uiColor("Label.foreground", "#d7d7d7");
            StringBuilder html = new StringBuilder(4096);
            html.append("<html><head><style>")
                    .append("body{font-family:sans-serif;font-size:12px;background:").append(background).append(";color:").append(foreground).append(";margin:8px;}")
                    .append(".card{border:1px solid #3d4148;border-left:4px solid #547aa5;margin:0 0 10px 0;padding:10px;background:#25272b;max-width:100%;}")
                    .append(".user{border-left-color:#5291cc;background:#252932;}")
                    .append(".assistant{border-left-color:#3d9b75;background:#242c29;}")
                    .append(".analyze{border-left-color:#c58a24;background:#302c22;}")
                    .append(".role{font-size:11px;color:#c9ccd2;font-weight:bold;margin:0 0 7px 0;padding:0 0 5px 0;border-bottom:1px solid #3d4148;}")
                    .append(".p{font-size:12px;line-height:1.35;margin:0 0 9px 0;overflow-wrap:anywhere;word-wrap:break-word;}")
                    .append(".h{font-size:12px;font-weight:bold;color:#f0f2f5;margin:10px 0 6px 0;overflow-wrap:anywhere;word-wrap:break-word;}")
                    .append(".li{font-size:12px;line-height:1.35;margin:0 0 6px 14px;overflow-wrap:anywhere;word-wrap:break-word;}")
                    .append(".quote{font-size:12px;border-left:3px solid #6b7280;margin:6px 0;padding:2px 0 2px 8px;color:#c9ccd2;}")
                    .append("pre{font-family:monospaced;font-size:12px;background:#17191c;border:1px solid #3d4148;padding:8px;white-space:pre-wrap;margin:7px 0 8px 0;overflow-wrap:anywhere;word-wrap:break-word;}")
                    .append("code{font-family:monospaced;font-size:12px;background:#1b1e22;padding:1px 3px;overflow-wrap:anywhere;word-wrap:break-word;}")
                    .append("a{color:#80bfff;}")
                    .append("</style></head><body>");
            for (ChatCard card : cards) {
                String kind = card.analysis() ? "card analyze" : "Assistant".equals(card.role()) ? "card assistant" : "card user";
                html.append("<div class='").append(kind).append("'>");
                html.append("<div class='role'>").append(escape(card.role())).append(" | ").append(escape(card.timestamp().toString())).append("</div>");
                html.append(markdownToHtml(card.content()));
                html.append("</div>");
            }
            html.append("</body></html>");
            rendering = true;
            try {
                super.setText(html.toString());
                setCaretPosition(getDocument().getLength());
            } finally {
                rendering = false;
            }
        }

        private static String markdownToHtml(String content) {
            String text = Objects.toString(content, "").replace("\r\n", "\n").replace('\r', '\n');
            if (text.isBlank()) return "<div>&nbsp;</div>";
            StringBuilder out = new StringBuilder(text.length() + 128);
            boolean inFence = false;
            for (String raw : text.split("\n", -1)) {
                String line = raw.trim();
                if (line.startsWith("```") || line.startsWith("~~~")) {
                    if (inFence) out.append("</code></pre>");
                    else out.append("<pre><code>");
                    inFence = !inFence;
                    continue;
                }
                if (inFence) {
                    out.append(breakableHtml(escape(raw))).append("\n");
                } else if (line.isBlank()) {
                    out.append("<div class='p'>&nbsp;</div>");
                } else if (line.matches("^#{1,6}\\s+.*")) {
                    out.append("<div class='h'>").append(inlineMarkdown(line.replaceFirst("^#{1,6}\\s*", ""))).append("</div>");
                } else if (line.startsWith("- ") || line.startsWith("* ")) {
                    out.append("<div class='li'>&bull; ").append(inlineMarkdown(line.substring(2))).append("</div>");
                } else if (line.matches("^\\d+[.)]\\s+.*")) {
                    out.append("<div class='li'>").append(inlineMarkdown(line)).append("</div>");
                } else if (line.startsWith(">")) {
                    out.append("<div class='quote'>").append(inlineMarkdown(line.replaceFirst("^>\\s?", ""))).append("</div>");
                } else {
                    for (String paragraph : readableParagraphs(raw)) {
                        out.append("<div class='p'>").append(inlineMarkdown(paragraph)).append("</div>");
                    }
                }
            }
            if (inFence) out.append("</code></pre>");
            return out.toString();
        }

        private static List<String> readableParagraphs(String raw) {
            String text = Objects.toString(raw, "").trim();
            if (text.length() <= TARGET_PARAGRAPH_CHARS) return List.of(raw);

            List<String> sentences = splitSentences(text);
            if (sentences.size() <= 1) return List.of(raw);

            List<String> paragraphs = new ArrayList<>();
            StringBuilder paragraph = new StringBuilder();
            for (String sentence : sentences) {
                if (!paragraph.isEmpty() && paragraph.length() + sentence.length() + 1 > TARGET_PARAGRAPH_CHARS) {
                    paragraphs.add(paragraph.toString());
                    paragraph.setLength(0);
                }
                if (!paragraph.isEmpty()) paragraph.append(' ');
                paragraph.append(sentence);
            }
            if (!paragraph.isEmpty()) paragraphs.add(paragraph.toString());
            return paragraphs;
        }

        private static List<String> splitSentences(String text) {
            List<String> sentences = new ArrayList<>();
            int start = 0;
            for (int i = 0; i < text.length() - 1; i++) {
                char ch = text.charAt(i);
                if (ch != '.' && ch != '!' && ch != '?') continue;
                int next = i + 1;
                while (next < text.length() && Character.isWhitespace(text.charAt(next))) next++;
                if (next >= text.length() || !Character.isUpperCase(text.charAt(next))) continue;
                sentences.add(text.substring(start, i + 1).trim());
                start = next;
                i = next;
            }
            if (start < text.length()) sentences.add(text.substring(start).trim());
            return sentences;
        }

        private static String inlineMarkdown(String value) {
            String escaped = escape(value);
            escaped = escaped.replaceAll("`([^`]+)`", "<code>$1</code>");
            escaped = escaped.replaceAll("\\*\\*([^*]+)\\*\\*", "<b>$1</b>");
            escaped = escaped.replaceAll("__([^_]+)__", "<b>$1</b>");
            escaped = escaped.replaceAll("(?<!\\*)\\*([^*]+)\\*(?!\\*)", "<i>$1</i>");
            escaped = escaped.replaceAll("\\[([^\\]]+)]\\((https?://[^\\s)]+|mailto:[^\\s)]+)\\)", "<a href='$2'>$1</a>");
            return breakableHtml(escaped);
        }

        private static String breakableHtml(String html) {
            StringBuilder out = new StringBuilder(html.length() + html.length() / HARD_WRAP_CHARS);
            int runLength = 0;
            boolean inTag = false;
            boolean inEntity = false;
            for (int i = 0; i < html.length(); i++) {
                char ch = html.charAt(i);
                out.append(ch);

                if (inTag) {
                    if (ch == '>') inTag = false;
                    continue;
                }
                if (inEntity) {
                    if (ch == ';') {
                        inEntity = false;
                        runLength++;
                    }
                    continue;
                }
                if (ch == '<') {
                    inTag = true;
                    continue;
                }
                if (ch == '&') {
                    inEntity = true;
                    continue;
                }
                if (Character.isWhitespace(ch)) {
                    runLength = 0;
                    continue;
                }

                runLength++;
                if (isSoftBreakCharacter(ch) || runLength >= HARD_WRAP_CHARS) {
                    out.append("&#8203;");
                    runLength = 0;
                }
            }
            return out.toString();
        }

        private static boolean isSoftBreakCharacter(char ch) {
            return ch == '/' || ch == '\\' || ch == '?' || ch == '&' || ch == '=' || ch == ','
                    || ch == ':' || ch == ';' || ch == '.' || ch == '-' || ch == '_' || ch == ')'
                    || ch == ']' || ch == '}';
        }

        private static String escape(String value) {
            return Objects.toString(value, "")
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;");
        }

        private static String uiColor(String key, String fallback) {
            Color color = UIManager.getColor(key);
            if (color == null) return fallback;
            return "#" + String.format("%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
        }
    }

    private record ChatCard(String role, String content, boolean analysis, Instant timestamp) {}

    private static final class PromptArea extends JTextArea {
        private String submittedPrompt = "";
        private boolean submittedPromptAvailable;
        private boolean autoClearing;
        private boolean submitHooksInstalled;

        private PromptArea(int rows, int cols) {
            super(rows, cols);
            installEnterBindings();
        }

        @Override public void addNotify() {
            super.addNotify();
            SwingUtilities.invokeLater(this::installSubmitHooks);
        }

        @Override public String getText() {
            String current = super.getText();
            if (SwingUtilities.isEventDispatchThread()) {
                if (current.isBlank()) {
                    submittedPrompt = "";
                    submittedPromptAvailable = false;
                    return current;
                }
                submittedPrompt = current;
                submittedPromptAvailable = true;
                return current;
            }
            return submittedPromptAvailable ? submittedPrompt : current;
        }

        @Override public void setText(String text) {
            if (!autoClearing) {
                submittedPrompt = "";
                submittedPromptAvailable = false;
            }
            super.setText(text);
        }

        private void installEnterBindings() {
            getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "burp-cockpit-send-chat");
            getActionMap().put("burp-cockpit-send-chat", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent event) {
                    captureSubmittedPrompt();
                    JButton sendChat = findButton(PromptArea.this, "Send Chat");
                    if (sendChat != null && sendChat.isEnabled()) sendChat.doClick();
                }
            });
            getInputMap().put(KeyStroke.getKeyStroke("shift ENTER"), "burp-cockpit-insert-break");
            getActionMap().put("burp-cockpit-insert-break", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent event) { replaceSelection("\n"); }
            });
        }

        private void installSubmitHooks() {
            if (submitHooksInstalled) return;
            JButton sendChat = findButton(this, "Send Chat");
            JButton analyze = findButton(this, "Analyze");
            if (sendChat == null && analyze == null) return;
            if (sendChat != null) sendChat.addActionListener(e -> captureSubmittedPrompt());
            if (analyze != null) analyze.addActionListener(e -> captureSubmittedPrompt());
            submitHooksInstalled = true;
        }

        private void captureSubmittedPrompt() {
            String submitted = super.getText();
            if (submitted.isBlank()) {
                submittedPrompt = "";
                submittedPromptAvailable = false;
                return;
            }
            submittedPrompt = submitted;
            submittedPromptAvailable = true;
            SwingUtilities.invokeLater(() -> {
                if (!super.getText().equals(submitted)) return;
                autoClearing = true;
                try { super.setText(""); }
                finally { autoClearing = false; }
            });
        }
    }

    private static Container rootContainer(Component start) {
        Container root = start.getParent();
        while (root != null && root.getParent() != null) root = root.getParent();
        return root;
    }

    private static JButton findButton(Component start, String text) {
        Container root = rootContainer(start);
        return root == null ? null : findButtonRecursive(root, text);
    }

    private static JButton findButtonRecursive(Component component, String text) {
        if (component instanceof JButton button && text.equals(button.getText())) return button;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                JButton found = findButtonRecursive(child, text);
                if (found != null) return found;
            }
        }
        return null;
    }
}
