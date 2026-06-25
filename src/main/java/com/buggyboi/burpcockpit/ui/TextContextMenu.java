package com.buggyboi.burpcockpit.ui;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Objects;

public final class TextContextMenu {
    private TextContextMenu() {}

    public static void install(JTextComponent component) {
        if (component == null) return;
        component.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                maybeShow(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                maybeShow(event);
            }

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
            area = new AutoClearingPromptArea(rows, cols);
        } else if (isChatTranscript(rows, cols, editable)) {
            area = new ChatTranscriptArea(rows, cols);
        } else {
            area = new JTextArea(rows, cols);
        }
        area.setEditable(editable);
        if (isChatTranscript(rows, cols, editable)) {
            area.setLineWrap(true);
            area.setWrapStyleWord(true);
        } else {
            area.setLineWrap(false);
            area.setWrapStyleWord(false);
        }
        install(area);
        return area;
    }

    public static JTextField field(String text) {
        JTextField field = new JTextField(text == null ? "" : text);
        install(field);
        return field;
    }

    public static void later(Runnable runnable) {
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
        } else {
            SwingUtilities.invokeLater(runnable);
        }
    }

    private static boolean isPromptInput(int rows, int cols, boolean editable) {
        return editable && rows == 4 && cols == 70;
    }

    private static boolean isChatTranscript(int rows, int cols, boolean editable) {
        return !editable && rows == 24 && cols == 70;
    }

    private static final class ChatTranscriptArea extends JTextArea {
        private static final String CLEAR_CHAT_BUTTON_NAME = "burp-cockpit-clear-chat";
        private boolean clearButtonInstalled;

        private ChatTranscriptArea(int rows, int cols) {
            super(rows, cols);
            setLineWrap(true);
            setWrapStyleWord(true);
        }

        @Override
        public void addNotify() {
            super.addNotify();
            SwingUtilities.invokeLater(this::installClearChatButton);
        }

        @Override
        public void replaceRange(String str, int start, int end) {
            super.replaceRange(cleanAssistantText(str), start, end);
        }

        private void installClearChatButton() {
            if (clearButtonInstalled) {
                return;
            }
            Container viewport = getParent();
            if (viewport == null) {
                return;
            }
            Container scroll = viewport.getParent();
            if (scroll == null) {
                return;
            }
            Container center = scroll.getParent();
            if (!(center instanceof JPanel centerPanel) || !(centerPanel.getLayout() instanceof BorderLayout centerLayout)) {
                return;
            }
            Container analysis = centerPanel.getParent();
            if (!(analysis instanceof JPanel analysisPanel) || !(analysisPanel.getLayout() instanceof BorderLayout analysisLayout)) {
                return;
            }
            Component top = analysisLayout.getLayoutComponent(BorderLayout.NORTH);
            if (!(top instanceof JPanel topPanel) || !(topPanel.getLayout() instanceof BorderLayout topLayout)) {
                return;
            }
            Component buttonsComponent = topLayout.getLayoutComponent(BorderLayout.SOUTH);
            if (!(buttonsComponent instanceof JPanel buttons)) {
                return;
            }
            for (Component component : buttons.getComponents()) {
                if (CLEAR_CHAT_BUTTON_NAME.equals(component.getName())) {
                    clearButtonInstalled = true;
                    return;
                }
            }
            JButton clearChat = new JButton("Clear Chat");
            clearChat.setName(CLEAR_CHAT_BUTTON_NAME);
            clearChat.addActionListener(e -> {
                setText("");
                Component statusComponent = centerLayout.getLayoutComponent(BorderLayout.SOUTH);
                if (statusComponent instanceof JLabel label) {
                    label.setText("Chat cleared.");
                }
            });
            buttons.add(clearChat);
            buttons.revalidate();
            buttons.repaint();
            clearButtonInstalled = true;
        }

        private static String cleanAssistantText(String value) {
            String text = Objects.toString(value, "").replace("\r\n", "\n").replace('\r', '\n');
            if (text.isBlank()) {
                return text;
            }
            StringBuilder out = new StringBuilder(text.length() + 128);
            boolean inFence = false;
            String[] lines = text.split("\n", -1);
            for (String originalLine : lines) {
                String line = originalLine;
                String trimmed = line.trim();
                if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                    inFence = !inFence;
                    out.append(line).append('\n');
                    continue;
                }
                if (!inFence) {
                    line = cleanMarkdownLine(line);
                    line = shapePlainLine(line);
                    if (isPlainHeading(line)) {
                        appendBlankLineIfNeeded(out);
                    }
                }
                out.append(line).append('\n');
            }
            String cleaned = out.toString()
                    .replaceAll("\\n{4,}", "\n\n\n")
                    .replaceAll("(?m)^\\s*[-*]\\s*$\\n", "")
                    .stripTrailing();
            return cleaned.isEmpty() ? "" : cleaned;
        }

        private static String cleanMarkdownLine(String line) {
            String leading = leadingWhitespace(line);
            String body = line.substring(leading.length());
            body = body.replaceFirst("^#{1,6}\\s+", "");
            body = body.replaceFirst("^[-*+]\\s+", "- ");
            body = body.replaceAll("\\*\\*\\*(.+?)\\*\\*\\*", "$1");
            body = body.replaceAll("___(.+?)___", "$1");
            body = body.replaceAll("\\*\\*(.+?)\\*\\*", "$1");
            body = body.replaceAll("__(.+?)__", "$1");
            body = body.replaceAll("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)", "$1");
            body = body.replaceAll("(?<!_)_([^_\\n]+)_(?!_)", "$1");
            body = body.replaceAll("`([^`\\n]+)`", "$1");
            return leading + body;
        }

        private static String shapePlainLine(String line) {
            String leading = leadingWhitespace(line);
            String body = line.substring(leading.length()).trim();
            if (body.isBlank()) {
                return "";
            }
            if (looksRaw(body) || body.length() < 180) {
                return leading + body;
            }

            body = body.replaceAll("\\s+(?=(What matters|Best next|Exact fields|Expected response|Low-value|Notes?|Findings?|Signal|Signals|Next steps?|Recommendation|Summary|Risk|Target|Request|Response|Parameters?):)", "\n\n");
            body = body.replaceAll("\\s+(?=\\d+[.)]\\s+)", "\n");
            body = body.replaceAll("\\s+(?=-\\s+)", "\n");
            body = body.replaceAll("(?<=[.!?])\\s+(?=[A-Z0-9])", "\n");
            body = body.replaceAll(";\\s+(?=[A-Z0-9])", ";\n");

            StringBuilder shaped = new StringBuilder(body.length() + 64);
            String[] chunks = body.split("\\n", -1);
            for (String chunk : chunks) {
                String clean = chunk.trim();
                if (clean.isEmpty()) {
                    appendBlankLineIfNeeded(shaped);
                    continue;
                }
                if (isSectionLabel(clean)) {
                    appendBlankLineIfNeeded(shaped);
                    shaped.append(clean).append('\n');
                } else if (clean.startsWith("- ")) {
                    shaped.append("  ").append(clean).append('\n');
                } else if (clean.matches("^\\d+[.)]\\s+.*")) {
                    shaped.append(clean).append('\n');
                } else {
                    shaped.append(wrapChunk(clean, 110)).append('\n');
                }
            }
            return leading + shaped.toString().stripTrailing();
        }

        private static String wrapChunk(String value, int width) {
            String text = Objects.toString(value, "").trim();
            if (text.length() <= width) {
                return text;
            }
            StringBuilder out = new StringBuilder(text.length() + 32);
            int lineLen = 0;
            for (String word : text.split("\\s+")) {
                if (lineLen > 0 && lineLen + 1 + word.length() > width) {
                    out.append('\n');
                    lineLen = 0;
                } else if (lineLen > 0) {
                    out.append(' ');
                    lineLen++;
                }
                out.append(word);
                lineLen += word.length();
            }
            return out.toString();
        }

        private static boolean looksRaw(String body) {
            String text = body.trim();
            return text.startsWith("GET ")
                    || text.startsWith("POST ")
                    || text.startsWith("PUT ")
                    || text.startsWith("PATCH ")
                    || text.startsWith("DELETE ")
                    || text.startsWith("HTTP/")
                    || text.contains("\r\n")
                    || text.matches("^[A-Za-z0-9_-]+: .*")
                    || text.startsWith("{")
                    || text.startsWith("[");
        }

        private static boolean isSectionLabel(String value) {
            String trimmed = value.trim();
            return trimmed.endsWith(":") && trimmed.length() <= 80;
        }

        private static boolean isPlainHeading(String line) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("-") || trimmed.matches("^\\d+[.)].*")) {
                return false;
            }
            return trimmed.endsWith(":") || trimmed.length() <= 64 && !trimmed.contains(".") && !trimmed.contains(";");
        }

        private static void appendBlankLineIfNeeded(StringBuilder out) {
            int len = out.length();
            if (len == 0) {
                return;
            }
            if (len >= 2 && out.charAt(len - 1) == '\n' && out.charAt(len - 2) == '\n') {
                return;
            }
            if (out.charAt(len - 1) != '\n') {
                out.append('\n');
            }
            out.append('\n');
        }

        private static String leadingWhitespace(String value) {
            int i = 0;
            while (i < value.length() && Character.isWhitespace(value.charAt(i))) {
                i++;
            }
            return value.substring(0, i);
        }
    }

    private static final class AutoClearingPromptArea extends JTextArea {
        private String submittedPrompt = "";
        private boolean submittedPromptAvailable;
        private boolean autoClearing;

        private AutoClearingPromptArea(int rows, int cols) {
            super(rows, cols);
        }

        @Override
        public String getText() {
            String current = super.getText();
            if (SwingUtilities.isEventDispatchThread()) {
                if (current.isBlank()) {
                    submittedPrompt = "";
                    submittedPromptAvailable = false;
                    return current;
                }
                submittedPrompt = current;
                submittedPromptAvailable = true;
                scheduleClearIfUnchanged(current);
                return current;
            }
            if (current.isBlank() && submittedPromptAvailable) {
                return submittedPrompt;
            }
            return current;
        }

        @Override
        public void setText(String text) {
            if (!autoClearing) {
                submittedPrompt = "";
                submittedPromptAvailable = false;
            }
            super.setText(text);
        }

        private void scheduleClearIfUnchanged(String submitted) {
            SwingUtilities.invokeLater(() -> {
                if (!super.getText().equals(submitted)) {
                    return;
                }
                autoClearing = true;
                try {
                    super.setText("");
                } finally {
                    autoClearing = false;
                }
            });
        }
    }
}
