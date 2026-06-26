package com.buggyboi.burpcockpit.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.core.Range;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.MessageEditorHttpRequestResponse;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import java.awt.Component;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class CockpitContextMenuProvider implements ContextMenuItemsProvider {
    private final MontoyaApi api;
    private final CockpitPanel panel;

    public CockpitContextMenuProvider(MontoyaApi api, CockpitPanel panel) {
        this.api = api;
        this.panel = panel;
    }

    @Override
    public List<Component> provideMenuItems(ContextMenuEvent event) {
        List<HttpRequestResponse> pairs = safeSelectedPairs(event);
        Optional<MessageEditorHttpRequestResponse> editor = safeMessageEditor(event);
        String selectedText = editor.map(this::selectedEditorText).orElse("");
        if (pairs.isEmpty() && editor.isEmpty() && selectedText.isBlank()) {
            return List.of();
        }

        JMenu menu = new JMenu("Burp Cockpit");

        Optional<HttpRequestResponse> selectedPair = firstPair(pairs, editor);
        if (selectedPair.isPresent()) {
            HttpRequestResponse pair = selectedPair.get();

            JMenuItem send = new JMenuItem("Open in Cockpit");
            send.addActionListener(e -> panel.loadFromBurp(pair, "context menu"));
            menu.add(send);

            JMenuItem analyze = new JMenuItem("Open and Analyze");
            analyze.addActionListener(e -> {
                panel.loadFromBurp(pair, "context menu analyze");
                panel.runAnalysis("Analyze the selected Burp message and propose the highest-value manual tests.");
            });
            menu.add(analyze);
        }

        if (!selectedText.isBlank()) {
            if (menu.getItemCount() > 0) menu.addSeparator();

            JMenuItem summarizeForNotes = new JMenuItem("Summarize for notes");
            summarizeForNotes.addActionListener(e -> panel.summarizeSelectionForNotes(selectedText));
            menu.add(summarizeForNotes);

            JMenuItem sendToNotes = new JMenuItem("Send to notes");
            sendToNotes.addActionListener(e -> panel.sendSelectionToNotes(selectedText));
            menu.add(sendToNotes);
        }

        return List.of(menu);
    }

    private Optional<HttpRequestResponse> firstPair(List<HttpRequestResponse> pairs, Optional<MessageEditorHttpRequestResponse> editor) {
        if (!pairs.isEmpty()) return Optional.of(pairs.get(0));
        return editor.map(MessageEditorHttpRequestResponse::requestResponse);
    }

    private List<HttpRequestResponse> safeSelectedPairs(ContextMenuEvent event) {
        try {
            return new ArrayList<>(event.selectedRequestResponses());
        } catch (RuntimeException ex) {
            api.logging().logToError("Burp Cockpit failed to read selected messages", ex);
            return List.of();
        }
    }

    private Optional<MessageEditorHttpRequestResponse> safeMessageEditor(ContextMenuEvent event) {
        try {
            return event.messageEditorRequestResponse();
        } catch (RuntimeException ex) {
            api.logging().logToError("Burp Cockpit failed to read message editor context", ex);
            return Optional.empty();
        }
    }

    private String selectedEditorText(MessageEditorHttpRequestResponse editor) {
        try {
            Optional<Range> maybeRange = editor.selectionOffsets();
            if (maybeRange.isEmpty()) return "";

            Range range = maybeRange.get();
            if (range.startIndexInclusive() >= range.endIndexExclusive()) return "";

            HttpRequestResponse pair = editor.requestResponse();
            ByteArray message = switch (editor.selectionContext()) {
                case REQUEST -> pair.request() == null ? null : pair.request().toByteArray();
                case RESPONSE -> !pair.hasResponse() || pair.response() == null ? null : pair.response().toByteArray();
            };
            if (message == null || range.startIndexInclusive() < 0 || range.endIndexExclusive() > message.length()) {
                return "";
            }
            return new String(message.subArray(range).getBytes(), StandardCharsets.UTF_8);
        } catch (RuntimeException ex) {
            api.logging().logToError("Burp Cockpit failed to read selected editor text", ex);
            return "";
        }
    }
}
