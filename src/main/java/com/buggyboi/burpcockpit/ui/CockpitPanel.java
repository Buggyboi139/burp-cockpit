package com.buggyboi.burpcockpit.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import com.buggyboi.burpcockpit.lumara.LumaraClient;
import com.buggyboi.burpcockpit.notes.NotesStore;
import com.buggyboi.burpcockpit.state.CockpitSettings;
import com.buggyboi.burpcockpit.state.CockpitState;
import com.buggyboi.burpcockpit.state.TrafficSnapshot;
import com.buggyboi.burpcockpit.util.HttpText;
import com.buggyboi.burpcockpit.util.JsonUtil;
import com.buggyboi.burpcockpit.util.PromptBuilder;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static burp.api.montoya.http.message.requests.HttpRequest.httpRequest;

public final class CockpitPanel extends JPanel {
    private final MontoyaApi api;
    private final CockpitState state;
    private final CockpitSettings settings;
    private final NotesStore notesStore;
    private final LumaraClient lumaraClient;

    private final JTextArea requestArea = TextContextMenu.area(18, 80, true);
    private final JTextArea responseArea = TextContextMenu.area(14, 80, false);
    private final JTextArea outputArea = TextContextMenu.area(14, 80, false);
    private final JTextArea promptArea = TextContextMenu.area(4, 80, true);
    private final JTextArea notesArea = TextContextMenu.area(20, 80, true);
    private final JTextField ragQueryField = TextContextMenu.field("");
    private final JTextField chatEndpointField;
    private final JTextField modelField;
    private final JTextField ragEndpointField;
    private final JTextField payloadEndpointField;
    private final JTextField notesDirField;

    private final JComboBox<String> tokenSelector = new JComboBox<>(new String[]{"1k", "2k", "20k", "96k"});
    private final JComboBox<String> noteSelector = new JComboBox<>();
    private final JCheckBox streamCheck;
    private final JCheckBox thinkingCheck;
    private final JCheckBox deltaCheck;
    private final JCheckBox injectPinnedNoteCheck;
    private final JCheckBox autoCaptureCheck;
    private final JLabel statusLabel = new JLabel("Idle");
    private final JLabel historyLabel = new JLabel("0/0");
    private final JLabel currentLabel = new JLabel("No request loaded");

    public CockpitPanel(MontoyaApi api, CockpitState state, LumaraClient lumaraClient) {
        super(new BorderLayout());
        this.api = api;
        this.state = state;
        this.settings = state.settings();
        this.notesStore = state.notesStore();
        this.lumaraClient = lumaraClient;
        this.chatEndpointField = TextContextMenu.field(settings.chatEndpoint());
        this.modelField = TextContextMenu.field(settings.model());
        this.ragEndpointField = TextContextMenu.field(settings.ragSearchEndpoint());
        this.payloadEndpointField = TextContextMenu.field(settings.payloadIdeasEndpoint());
        this.notesDirField = TextContextMenu.field(settings.notesDirectory().toString());
        this.streamCheck = new JCheckBox("Stream", settings.streamChat());
        this.thinkingCheck = new JCheckBox("Thinking", settings.includeThinking());
        this.deltaCheck = new JCheckBox("Delta only", settings.deltaOnly());
        this.injectPinnedNoteCheck = new JCheckBox("Inject pinned note", settings.injectPinnedNote());
        this.autoCaptureCheck = new JCheckBox("Auto-capture latest", settings.autoCaptureLatest());
        buildUi();
        applySettingsToControls();
        refreshNoteList();
        api.userInterface().applyThemeToComponent(this);
    }

    public void loadFromBurp(HttpRequestResponse pair, String source) {
        if (pair == null) {
            return;
        }
        TextContextMenu.later(() -> loadSnapshot(TrafficSnapshot.from(pair, source)));
    }

    public void considerAutoCapture(HttpRequest request, HttpResponse response, String source) {
        if (!settings.autoCaptureLatest()) {
            return;
        }
        String requestText = request == null ? "" : request.toString();
        String responseText = response == null ? "" : response.toString();
        HttpService service = request == null ? null : request.httpService();
        TextContextMenu.later(() -> loadSnapshot(new TrafficSnapshot(service, requestText, responseText, Instant.now(), source)));
    }

    public void runAnalysis(String instruction) {
        TextContextMenu.later(() -> {
            promptArea.setText(Objects.toString(instruction, ""));
            runAnalysisFromUi();
        });
    }

    public void runPayloadIdeas() {
        TextContextMenu.later(this::runPayloadIdeasFromUi);
    }

    public void ensureCurrentHostNote() {
        TextContextMenu.later(() -> {
            syncSnapshotFromEditors("note sync");
            String name = notesStore.defaultNoteNameForHost(state.current().map(TrafficSnapshot::hostLabel).orElse("DEFAULT"));
            notesStore.ensureNote(name);
            selectNote(name);
            loadSelectedNote();
            state.pinnedNoteName(name);
            setStatus("Loaded host note: " + name);
        });
    }

    private void buildUi() {
        setPreferredSize(new Dimension(1200, 850));
        add(buildTopToolbar(), BorderLayout.NORTH);

        JSplitPane left = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                wrap("Request", requestArea),
                wrap("Response", responseArea));
        left.setResizeWeight(0.58);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Analysis", buildAnalysisPanel());
        tabs.addTab("Notes", buildNotesPanel());
        tabs.addTab("Settings", buildSettingsPanel());

        JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, tabs);
        main.setResizeWeight(0.55);
        add(main, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        bottom.add(statusLabel, BorderLayout.CENTER);
        bottom.add(historyLabel, BorderLayout.EAST);
        add(bottom, BorderLayout.SOUTH);
    }

    private Component buildTopToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);

        JButton send = new JButton("Send");
        send.addActionListener(e -> sendCurrentRequest());
        bar.add(send);

        JButton previous = new JButton("←");
        previous.addActionListener(e -> state.previous().ifPresent(this::loadSnapshotWithoutPush));
        bar.add(previous);

        JButton next = new JButton("→");
        next.addActionListener(e -> state.next().ifPresent(this::loadSnapshotWithoutPush));
        bar.add(next);

        JButton sync = new JButton("Snapshot Draft");
        sync.addActionListener(e -> syncSnapshotFromEditors("manual snapshot"));
        bar.add(sync);

        JButton prettyReq = new JButton("Pretty Body");
        prettyReq.addActionListener(e -> prettyPrintRequestBody());
        bar.add(prettyReq);

        bar.addSeparator();
        bar.add(new JLabel("Tokens "));
        bar.add(tokenSelector);
        bar.add(streamCheck);
        bar.add(thinkingCheck);
        bar.add(deltaCheck);
        bar.add(injectPinnedNoteCheck);
        bar.add(autoCaptureCheck);
        bar.addSeparator();
        bar.add(currentLabel);
        return bar;
    }

    private Component buildAnalysisPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JPanel controls = new JPanel(new BorderLayout(4, 4));
        promptArea.setText("What are the highest-value bug bounty tests for this request/response?");
        controls.add(wrap("Prompt / mutation instruction", promptArea), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton chat = new JButton("Chat");
        chat.addActionListener(e -> runChatFromUi());
        buttons.add(chat);

        JButton analyze = new JButton("Analyze");
        analyze.addActionListener(e -> runAnalysisFromUi());
        buttons.add(analyze);

        JButton edit = new JButton("AI Edit Draft");
        edit.addActionListener(e -> runAiEditFromUi());
        buttons.add(edit);

        JButton rag = new JButton("Search RAG");
        rag.addActionListener(e -> runRagSearchFromUi());
        buttons.add(rag);

        JButton payloads = new JButton("Payload Ideas");
        payloads.addActionListener(e -> runPayloadIdeasFromUi());
        buttons.add(payloads);

        ragQueryField.setColumns(24);
        buttons.add(new JLabel("RAG query:"));
        buttons.add(ragQueryField);

        JButton clear = new JButton("Clear Output");
        clear.addActionListener(e -> outputArea.setText(""));
        buttons.add(clear);

        controls.add(buttons, BorderLayout.SOUTH);
        panel.add(controls, BorderLayout.NORTH);
        panel.add(wrap("Output", outputArea), BorderLayout.CENTER);
        return panel;
    }

    private Component buildNotesPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(new JLabel("Note:"));
        noteSelector.setEditable(true);
        noteSelector.setPrototypeDisplayValue("calendar.google.com........................");
        top.add(noteSelector);

        JButton load = new JButton("Load");
        load.addActionListener(e -> loadSelectedNote());
        top.add(load);

        JButton save = new JButton("Save");
        save.addActionListener(e -> saveSelectedNote());
        top.add(save);

        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(e -> refreshNoteList());
        top.add(refresh);

        JButton hostNote = new JButton("Host Note");
        hostNote.addActionListener(e -> ensureCurrentHostNote());
        top.add(hostNote);

        JButton pin = new JButton("Pin");
        pin.addActionListener(e -> {
            String name = selectedNoteName();
            state.pinnedNoteName(name);
            setStatus("Pinned note: " + name);
        });
        top.add(pin);

        panel.add(top, BorderLayout.NORTH);
        panel.add(wrap("Notes", notesArea), BorderLayout.CENTER);
        return panel;
    }

    private Component buildSettingsPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1;
        int row = 0;
        row = addSettingRow(panel, gbc, row, "Chat endpoint", chatEndpointField);
        row = addSettingRow(panel, gbc, row, "Model", modelField);
        row = addSettingRow(panel, gbc, row, "RAG search endpoint", ragEndpointField);
        row = addSettingRow(panel, gbc, row, "Payload ideas endpoint", payloadEndpointField);
        row = addSettingRow(panel, gbc, row, "Notes directory", notesDirField);

        JButton save = new JButton("Save Settings");
        save.addActionListener(e -> saveSettings());
        gbc.gridx = 1;
        gbc.gridy = row;
        gbc.weightx = 1;
        panel.add(save, gbc);

        gbc.gridx = 0;
        gbc.gridy = row + 1;
        gbc.gridwidth = 2;
        gbc.weighty = 1;
        panel.add(new JLabel("Settings are stored in Java Preferences. Because obviously the JVM needed a tiny attic."), gbc);
        return panel;
    }

    private int addSettingRow(JPanel panel, GridBagConstraints gbc, int row, String label, Component field) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        panel.add(new JLabel(label), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        panel.add(field, gbc);
        return row + 1;
    }

    private JScrollPane wrap(String title, JTextArea area) {
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scroll = new JScrollPane(area);
        scroll.setBorder(BorderFactory.createTitledBorder(title));
        return scroll;
    }

    private void applySettingsToControls() {
        selectToken(settings.tokenBudget());
        streamCheck.addActionListener(e -> settings.streamChat(streamCheck.isSelected()));
        thinkingCheck.addActionListener(e -> settings.includeThinking(thinkingCheck.isSelected()));
        deltaCheck.addActionListener(e -> settings.deltaOnly(deltaCheck.isSelected()));
        injectPinnedNoteCheck.addActionListener(e -> settings.injectPinnedNote(injectPinnedNoteCheck.isSelected()));
        autoCaptureCheck.addActionListener(e -> settings.autoCaptureLatest(autoCaptureCheck.isSelected()));
        tokenSelector.addActionListener(e -> settings.tokenBudget(parseTokenSelection()));
    }

    private void loadSnapshot(TrafficSnapshot snapshot) {
        state.pushSnapshot(snapshot);
        loadSnapshotWithoutPush(snapshot);
        String name = notesStore.defaultNoteNameForHost(snapshot.hostLabel());
        if (state.pinnedNoteName().isBlank()) {
            notesStore.ensureNote(name);
            state.pinnedNoteName(name);
            refreshNoteList();
            selectNote(name);
        }
    }

    private void loadSnapshotWithoutPush(TrafficSnapshot snapshot) {
        requestArea.setText(snapshot.requestText());
        requestArea.setCaretPosition(0);
        responseArea.setText(snapshot.responseText());
        responseArea.setCaretPosition(0);
        currentLabel.setText(HttpText.shortSummary(snapshot.requestText(), snapshot.service()));
        historyLabel.setText(state.historyLabel());
        setStatus("Loaded " + snapshot.source() + " at " + snapshot.capturedAt());
    }

    private void syncSnapshotFromEditors(String source) {
        HttpService service = state.currentService();
        if (service == null) {
            service = HttpText.inferService(requestArea.getText(), true).orElse(null);
        }
        TrafficSnapshot snapshot = new TrafficSnapshot(service, requestArea.getText(), responseArea.getText(), Instant.now(), source);
        state.pushSnapshot(snapshot);
        currentLabel.setText(HttpText.shortSummary(snapshot.requestText(), snapshot.service()));
        historyLabel.setText(state.historyLabel());
    }

    private void sendCurrentRequest() {
        syncSettingsFromControls();
        syncSnapshotFromEditors("pre-send");
        String raw = HttpText.normalizeLineEndings(requestArea.getText());
        HttpService service = state.currentService();
        if (service == null) {
            Optional<HttpService> inferred = HttpText.inferService(raw, true);
            if (inferred.isEmpty()) {
                showError("Cannot infer HTTP service. Load a Burp request first or include a Host header.", null);
                return;
            }
            service = inferred.get();
        }
        HttpService finalService = service;
        setStatus("Sending request...");
        Thread thread = new Thread(() -> {
            try {
                HttpRequest request = httpRequest(finalService, raw);
                HttpRequestResponse result = api.http().sendRequest(request);
                TextContextMenu.later(() -> loadSnapshot(TrafficSnapshot.from(result, "manual send")));
            } catch (Throwable throwable) {
                TextContextMenu.later(() -> showError("Send failed", throwable));
            }
        }, "burp-cockpit-send");
        thread.setDaemon(true);
        thread.start();
    }

    private void prettyPrintRequestBody() {
        String body = HttpText.body(requestArea.getText());
        if (!HttpText.looksJson(body)) {
            setStatus("Body does not look like JSON. Refusing to beautify soup.");
            return;
        }
        requestArea.setText(HttpText.withBody(requestArea.getText(), JsonUtil.pretty(body)));
    }

    private void runChatFromUi() {
        syncSettingsFromControls();
        syncSnapshotFromEditors("chat context");
        String pinned = pinnedNoteContent();
        String prompt = PromptBuilder.chatPrompt(state, promptArea.getText(), pinned);
        runChatLikeOperation(prompt, false);
    }

    private void runAnalysisFromUi() {
        syncSettingsFromControls();
        syncSnapshotFromEditors("analysis context");
        String pinned = pinnedNoteContent();
        String prompt = PromptBuilder.analysisPrompt(state, promptArea.getText(), pinned);
        runChatLikeOperation(prompt, false);
    }

    private void runAiEditFromUi() {
        syncSettingsFromControls();
        syncSnapshotFromEditors("edit context");
        String pinned = pinnedNoteContent();
        String prompt = PromptBuilder.editPrompt(state, promptArea.getText(), requestArea.getText(), pinned);
        runChatLikeOperation(prompt, true);
    }

    private void runChatLikeOperation(String prompt, boolean replaceRequestOnDone) {
        outputArea.setText("");
        setStatus("Calling Lumara...");
        AtomicReference<StringBuilder> buffer = new AtomicReference<>(new StringBuilder());
        String system = PromptBuilder.systemPrompt(settings.includeThinking());
        lumaraClient.streamChat(settings, system, prompt,
                token -> TextContextMenu.later(() -> {
                    buffer.get().append(token);
                    outputArea.append(token);
                    outputArea.setCaretPosition(outputArea.getDocument().getLength());
                }),
                throwable -> TextContextMenu.later(() -> showError("Lumara call failed", throwable)),
                () -> TextContextMenu.later(() -> {
                    if (replaceRequestOnDone) {
                        String edited = stripCodeFences(buffer.get().toString()).trim();
                        if (!edited.isBlank()) {
                            requestArea.setText(edited);
                            requestArea.setCaretPosition(0);
                            syncSnapshotFromEditors("ai edit");
                        }
                    }
                    state.lastPromptRequest(requestArea.getText());
                    setStatus("Lumara finished");
                }));
    }

    private void runRagSearchFromUi() {
        syncSettingsFromControls();
        String query = ragQueryField.getText().trim();
        if (query.isBlank()) {
            query = promptArea.getText().trim();
        }
        if (query.isBlank()) {
            query = HttpText.methodAndPath(requestArea.getText());
        }
        if (query.isBlank()) {
            setStatus("No RAG query available.");
            return;
        }
        outputArea.setText("");
        setStatus("Searching RAG...");
        String finalQuery = query;
        Thread thread = new Thread(() -> {
            try {
                String result = lumaraClient.ragSearch(settings, finalQuery, 8);
                TextContextMenu.later(() -> {
                    outputArea.setText(JsonUtil.pretty(result));
                    outputArea.setCaretPosition(0);
                    setStatus("RAG search complete");
                });
            } catch (Throwable throwable) {
                TextContextMenu.later(() -> showError("RAG search failed", throwable));
            }
        }, "burp-cockpit-rag");
        thread.setDaemon(true);
        thread.start();
    }

    private void runPayloadIdeasFromUi() {
        syncSettingsFromControls();
        syncSnapshotFromEditors("payload context");
        outputArea.setText("");
        setStatus("Asking payload endpoint...");
        String req = requestArea.getText();
        String resp = responseArea.getText();
        Thread thread = new Thread(() -> {
            try {
                String result = lumaraClient.payloadIdeas(settings, req, resp, 25);
                TextContextMenu.later(() -> {
                    outputArea.setText(JsonUtil.pretty(result));
                    outputArea.setCaretPosition(0);
                    setStatus("Payload ideas complete");
                });
            } catch (Throwable throwable) {
                TextContextMenu.later(() -> showError("Payload ideas failed", throwable));
            }
        }, "burp-cockpit-payloads");
        thread.setDaemon(true);
        thread.start();
    }

    private String pinnedNoteContent() {
        if (!settings.injectPinnedNote()) {
            return "";
        }
        String pinned = state.pinnedNoteName();
        if (pinned.isBlank()) {
            pinned = selectedNoteName();
        }
        return pinned.isBlank() ? "" : notesStore.read(pinned);
    }

    private void refreshNoteList() {
        String selected = selectedNoteName();
        noteSelector.removeAllItems();
        List<String> names = notesStore.listNoteNames();
        if (names.isEmpty()) {
            names = List.of(notesStore.ensureNote("DEFAULT"));
        }
        for (String name : names) {
            noteSelector.addItem(name);
        }
        if (!selected.isBlank()) {
            selectNote(selected);
        }
    }

    private void selectNote(String name) {
        String clean = NotesStore.sanitizeName(name);
        boolean found = false;
        for (int i = 0; i < noteSelector.getItemCount(); i++) {
            if (clean.equals(noteSelector.getItemAt(i))) {
                found = true;
                break;
            }
        }
        if (!found) {
            noteSelector.addItem(clean);
        }
        noteSelector.setSelectedItem(clean);
    }

    private void loadSelectedNote() {
        String name = selectedNoteName();
        if (name.isBlank()) {
            return;
        }
        notesStore.ensureNote(name);
        notesArea.setText(notesStore.read(name));
        notesArea.setCaretPosition(0);
        setStatus("Loaded note: " + name);
    }

    private void saveSelectedNote() {
        String name = selectedNoteName();
        if (name.isBlank()) {
            name = "DEFAULT";
            selectNote(name);
        }
        try {
            notesStore.write(name, notesArea.getText());
            refreshNoteList();
            selectNote(name);
            setStatus("Saved note: " + name);
        } catch (Throwable throwable) {
            showError("Failed to save note", throwable);
        }
    }

    private String selectedNoteName() {
        Object selected = noteSelector.getEditor().getItem();
        if (selected == null) {
            selected = noteSelector.getSelectedItem();
        }
        return NotesStore.sanitizeName(Objects.toString(selected, ""));
    }

    private void saveSettings() {
        syncSettingsFromControls();
        notesStore.root(settings.notesDirectory());
        refreshNoteList();
        setStatus("Settings saved");
    }

    private void syncSettingsFromControls() {
        settings.chatEndpoint(chatEndpointField.getText());
        settings.model(modelField.getText());
        settings.ragSearchEndpoint(ragEndpointField.getText());
        settings.payloadIdeasEndpoint(payloadEndpointField.getText());
        settings.notesDirectory(notesDirField.getText());
        settings.streamChat(streamCheck.isSelected());
        settings.includeThinking(thinkingCheck.isSelected());
        settings.deltaOnly(deltaCheck.isSelected());
        settings.injectPinnedNote(injectPinnedNoteCheck.isSelected());
        settings.autoCaptureLatest(autoCaptureCheck.isSelected());
        settings.tokenBudget(parseTokenSelection());
    }

    private int parseTokenSelection() {
        String selected = Objects.toString(tokenSelector.getSelectedItem(), "20k").trim().toLowerCase();
        return switch (selected) {
            case "1k" -> 1024;
            case "2k" -> 2048;
            case "96k" -> 98304;
            default -> 20000;
        };
    }

    private void selectToken(int value) {
        if (value <= 1100) tokenSelector.setSelectedItem("1k");
        else if (value <= 2200) tokenSelector.setSelectedItem("2k");
        else if (value >= 90000) tokenSelector.setSelectedItem("96k");
        else tokenSelector.setSelectedItem("20k");
    }

    private void setStatus(String status) {
        statusLabel.setText(status);
        api.logging().logToOutput("Burp Cockpit: " + status);
    }

    private void showError(String message, Throwable throwable) {
        String detail = throwable == null ? "" : throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
        setStatus(message + (detail.isBlank() ? "" : " - " + detail));
        if (throwable != null) {
            api.logging().logToError(message, throwable);
        }
        JOptionPane.showMessageDialog(this, message + (detail.isBlank() ? "" : "\n" + detail), "Burp Cockpit", JOptionPane.ERROR_MESSAGE);
    }

    private static String stripCodeFences(String text) {
        String cleaned = Objects.toString(text, "").trim();
        if (cleaned.startsWith("````")) {
            int firstNewline = cleaned.indexOf('\n');
            int lastFence = cleaned.lastIndexOf("````");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                return cleaned.substring(firstNewline + 1, lastFence).trim();
            }
        }
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            int lastFence = cleaned.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                return cleaned.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return cleaned;
    }
}
