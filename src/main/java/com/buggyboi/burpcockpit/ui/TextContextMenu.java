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
        area.setLineWrap(false);
        area.setWrapStyleWord(false);
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
        }

        @Override
        public void addNotify() {
            super.addNotify();
            SwingUtilities.invokeLater(this::installClearChatButton);
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
