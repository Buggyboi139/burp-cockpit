package com.buggyboi.burpcockpit.ui;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
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
        JTextArea area = isPromptInput(rows, cols, editable)
                ? new AutoClearingPromptArea(rows, cols)
                : new JTextArea(rows, cols);
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
