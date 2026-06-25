package com.buggyboi.burpcockpit.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;

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
        if (pairs.isEmpty()) {
            return List.of();
        }

        JMenu menu = new JMenu("Burp Cockpit");

        JMenuItem send = new JMenuItem("Send to Cockpit");
        send.addActionListener(e -> panel.loadFromBurp(pairs.getFirst(), "context menu"));
        menu.add(send);

        JMenuItem analyze = new JMenuItem("Send and Analyze");
        analyze.addActionListener(e -> {
            panel.loadFromBurp(pairs.getFirst(), "context menu analyze");
            panel.runAnalysis("Analyze the selected Burp message and propose the highest-value manual tests.");
        });
        menu.add(analyze);

        JMenuItem payloads = new JMenuItem("Payload Ideas");
        payloads.addActionListener(e -> {
            panel.loadFromBurp(pairs.getFirst(), "context menu payloads");
            panel.runPayloadIdeas();
        });
        menu.add(payloads);

        JMenuItem note = new JMenuItem("Create / Load Host Note");
        note.addActionListener(e -> {
            panel.loadFromBurp(pairs.getFirst(), "context menu note");
            panel.ensureCurrentHostNote();
        });
        menu.add(note);

        return List.of(menu);
    }

    private List<HttpRequestResponse> safeSelectedPairs(ContextMenuEvent event) {
        try {
            return new ArrayList<>(event.selectedRequestResponses());
        } catch (RuntimeException ex) {
            api.logging().logToError("Burp Cockpit failed to read selected messages", ex);
            return List.of();
        }
    }
}
