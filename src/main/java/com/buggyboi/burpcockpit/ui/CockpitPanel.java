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
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
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

    private final JTextArea requestArea = TextContextMenu.area(24, 90, true);
    private final JTextArea responseArea = TextContextMenu.area(12, 90, false);
    private final JTextArea transcriptArea = TextContextMenu.area(22, 70, false);
    private final JTextArea promptArea = TextContextMenu.area(4, 70, true);
    private final JTextArea notesArea = TextContextMenu.area(24, 70, true);

    private final JTextField chatEndpointField;
    private final JTextField modelField;
    private final JTextField ragEndpointField;
    private final JTextField notesDirField;

    private final JComboBox<String> tokenSelector = new JComboBox<>(new String[]{"1k", "2k", "20k", "96k"});
    private final JComboBox<String> noteSelector = new JComboBox<>();
    private final JCheckBox streamCheck;
    private final JCheckBox thinkingCheck;
    private final JCheckBox deltaCheck;
    private final JCheckBox ragCheck;
    private final JCheckBox injectPinnedNoteCheck;
    private final JLabel statusLabel = new JLabel("Analysis ready.");
    private final JLabel historyLabel = new JLabel("0/0");
    private final JLabel currentLabel = new JLabel("No request loaded");
    private final JLabel contextLabel = new JLabel("Chat: 0.0k | Resp: 0.0k | Notes 0.0k | RAG 0.0k | Total 0.0k");

    private JSplitPane mainSplitPane;
    private JTabbedPane rightTabs;
    private Component rightPane;
    private boolean rightPaneVisible = true;
    private Thread currentAiThread;
    private String lastRagDump = "";

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
        this.notesDirField = TextContextMenu.field(settings.notesDirectory().toString());
        this.streamCheck = new JCheckBox("Stream", settings.streamChat());
        this.thinkingCheck = new JCheckBox("Thinking", settings.includeThinking());
        this.deltaCheck = new JCheckBox("Delta only", settings.deltaOnly());
        this.ragCheck = new JCheckBox("RAG", settings.injectRag());
        this.injectPinnedNoteCheck = new JCheckBox("Notes", settings.injectPinnedNote());
        buildUi();
        applySettingsToControls();
        refreshNoteList();
        api.userInterface().applyThemeToComponent(this);
    }

    public void loadFromBurp(HttpRequestResponse pair, String source) {
        if (pair == null) return;
        TextContextMenu.later(() -> loadSnapshot(TrafficSnapshot.from(pair, source)));
    }

    public void considerAutoCapture(HttpRequest request, HttpResponse response, String source) {
        if (!settings.autoCaptureLatest()) return;
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
        setPreferredSize(new Dimension(1300, 880));
        add(buildToolbar(), BorderLayout.NORTH);

        JSplitPane left = new JSplitPane(JSplitPane.VERTICAL_SPLIT, wrap("Request", requestArea), wrap("Response from last sent or opened exchange", responseArea));
        left.setResizeWeight(0.68);

        rightTabs = new JTabbedPane();
        rightTabs.addTab("Analysis", buildAnalysisPanel());
        rightTabs.addTab("Notes", buildNotesPanel());
        rightPane = rightTabs;

        mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, rightPane);
        mainSplitPane.setResizeWeight(0.55);
        add(mainSplitPane, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        bottom.add(statusLabel, BorderLayout.CENTER);
        bottom.add(historyLabel, BorderLayout.EAST);
        add(bottom, BorderLayout.SOUTH);
    }

    private Component buildToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);

        JButton newButton = new JButton("New");
        newButton.addActionListener(e -> openStarterRequest());
        bar.add(newButton);

        JButton importRaw = new JButton("Import Raw");
        importRaw.addActionListener(e -> importRawRequest());
        bar.add(importRaw);

        JButton previous = new JButton("←");
        previous.addActionListener(e -> state.previous().ifPresent(this::loadSnapshotWithoutPush));
        bar.add(previous);

        JButton next = new JButton("→");
        next.addActionListener(e -> state.next().ifPresent(this::loadSnapshotWithoutPush));
        bar.add(next);

        JButton send = new JButton("Send");
        send.addActionListener(e -> sendCurrentRequest());
        bar.add(send);

        JButton analyze = new JButton("Analyze");
        analyze.addActionListener(e -> runAnalysisFromUi());
        bar.add(analyze);

        JButton exportCurl = new JButton("Export curl");
        exportCurl.addActionListener(e -> exportCurrent("curl"));
        bar.add(exportCurl);

        JButton exportPython = new JButton("Export Python");
        exportPython.addActionListener(e -> exportCurrent("python"));
        bar.add(exportPython);

        JButton toggleRight = new JButton("Hide Right Pane");
        toggleRight.addActionListener(e -> {
            toggleRightPane();
            toggleRight.setText(rightPaneVisible ? "Hide Right Pane" : "Show Right Pane");
        });
        bar.add(toggleRight);

        JButton settingsButton = new JButton("Settings");
        settingsButton.addActionListener(e -> showSettingsDialog());
        bar.add(settingsButton);

        bar.addSeparator();
        bar.add(new JLabel("Tokens "));
        bar.add(tokenSelector);
        bar.add(streamCheck);
        bar.add(thinkingCheck);
        bar.add(deltaCheck);
        bar.add(ragCheck);
        bar.add(injectPinnedNoteCheck);
        bar.addSeparator();
        bar.add(currentLabel);
        return bar;
    }

    private Component buildAnalysisPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JPanel top = new JPanel(new BorderLayout(4, 4));
        promptArea.setText("What are the highest-value bug bounty tests for this request/response?");
        top.add(wrap("Prompt / mutation instruction", promptArea), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton chat = new JButton("Send Chat");
        chat.addActionListener(e -> runChatFromUi());
        buttons.add(chat);

        JButton analyze = new JButton("Analyze");
        analyze.addActionListener(e -> runAnalysisFromUi());
        buttons.add(analyze);

        JButton stop = new JButton("Stop");
        stop.addActionListener(e -> stopCurrentAiWorker());
        buttons.add(stop);

        top.add(buttons, BorderLayout.SOUTH);
        panel.add(top, BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout());
        center.add(contextLabel, BorderLayout.NORTH);
        center.add(wrap("Analysis", transcriptArea), BorderLayout.CENTER);
        panel.add(center, BorderLayout.CENTER);
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
            updateContextCounter();
        });
        top.add(pin);

        JButton ingest = new JButton("Ingest Notes");
        ingest.addActionListener(e -> ingestNotes());
        top.add(ingest);

        panel.add(top, BorderLayout.NORTH);
        panel.add(wrap("Shared notes", notesArea), BorderLayout.CENTER);
        return panel;
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
        ragCheck.addActionListener(e -> settings.injectRag(ragCheck.isSelected()));
        injectPinnedNoteCheck.addActionListener(e -> settings.injectPinnedNote(injectPinnedNoteCheck.isSelected()));
        tokenSelector.addActionListener(e -> settings.tokenBudget(parseTokenSelection()));
    }

    private void openStarterRequest() {
        String starter = "GET / HTTP/1.1\r\nHost: example.com\r\nUser-Agent: Burp-Cockpit\r\nAccept: */*\r\n\r\n";
        loadSnapshot(new TrafficSnapshot(null, starter, "", Instant.now(), "new"));
    }

    private void importRawRequest() {
        JTextArea raw = TextContextMenu.area(22, 90, true);
        raw.setText(requestArea.getText());
        int choice = JOptionPane.showConfirmDialog(this, new JScrollPane(raw), "Import Raw Request", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice == JOptionPane.OK_OPTION) {
            HttpService service = HttpText.inferService(raw.getText(), true).orElse(null);
            loadSnapshot(new TrafficSnapshot(service, raw.getText(), "", Instant.now(), "import raw"));
        }
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
        updateContextCounter();
    }

    private void syncSnapshotFromEditors(String source) {
        HttpService service = state.currentService();
        if (service == null) service = HttpText.inferService(requestArea.getText(), true).orElse(null);
        TrafficSnapshot snapshot = new TrafficSnapshot(service, requestArea.getText(), responseArea.getText(), Instant.now(), source);
        state.pushSnapshot(snapshot);
        currentLabel.setText(HttpText.shortSummary(snapshot.requestText(), snapshot.service()));
        historyLabel.setText(state.historyLabel());
        updateContextCounter();
    }

    private void sendCurrentRequest() {
        syncSettingsFromControls();
        syncSnapshotFromEditors("pre-send");
        String raw = HttpText.normalizeLineEndings(requestArea.getText());
        if (looksLikeBurpErrorHtml(raw)) {
            showError("The request editor contains Burp's HTML proxy error page, not the target request. Reload the original target request from Burp history/repeater.", null);
            return;
        }
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

    private void runChatFromUi() {
        runAi(false);
    }

    private void runAnalysisFromUi() {
        runAi(true);
    }

    private void runAi(boolean analysis) {
        syncSettingsFromControls();
        syncSnapshotFromEditors(analysis ? "analysis context" : "chat context");
        if (looksLikeBurpErrorHtml(requestArea.getText())) {
            showError("The current request is Burp's HTML proxy error page. Your Lumara endpoint was pointed at Burp. Reload the original request, then run analysis again.", null);
            return;
        }
        if (currentAiThread != null && currentAiThread.isAlive()) {
            setStatus("AI is already working. Stop it first.");
            return;
        }
        transcriptArea.setText("");
        lastRagDump = "";
        updateContextCounter();
        setStatus(settings.injectRag() ? "Preparing RAG context..." : "Calling Lumara...");

        Thread worker = new Thread(() -> {
            String ragDump = "";
            if (settings.injectRag()) {
                try {
                    String query = PromptBuilder.ragQuery(state, promptArea.getText());
                    ragDump = lumaraClient.ragSearch(settings, query, 8, "both");
                } catch (Throwable throwable) {
                    ragDump = "RAG injection failed: " + throwable.getMessage();
                }
            }
            String pinned = pinnedNoteContent();
            String prompt = analysis
                    ? PromptBuilder.analysisPrompt(state, promptArea.getText(), pinned, ragDump)
                    : PromptBuilder.chatPrompt(state, promptArea.getText(), pinned, ragDump);
            String finalRagDump = ragDump;
            TextContextMenu.later(() -> {
                lastRagDump = finalRagDump;
                updateContextCounter();
                setStatus("Calling Lumara...");
            });
            AtomicReference<StringBuilder> buffer = new AtomicReference<>(new StringBuilder());
            String system = PromptBuilder.systemPrompt(settings.includeThinking());
            Thread streamThread = lumaraClient.streamChat(settings, system, prompt,
                    token -> TextContextMenu.later(() -> {
                        buffer.get().append(token);
                        transcriptArea.append(token);
                        transcriptArea.setCaretPosition(transcriptArea.getDocument().getLength());
                    }),
                    throwable -> TextContextMenu.later(() -> showError("Lumara call failed", throwable)),
                    () -> TextContextMenu.later(() -> {
                        state.lastPromptRequest(requestArea.getText());
                        maybeAppendAnalysisToNotes(buffer.get().toString(), analysis);
                        setStatus("AI response ready.");
                    }));
            currentAiThread = streamThread;
        }, "burp-cockpit-ai-prep");
        currentAiThread = worker;
        worker.setDaemon(true);
        worker.start();
    }

    private void maybeAppendAnalysisToNotes(String output, boolean analysis) {
        if (!analysis || output == null || output.isBlank()) return;
        if (notesArea.getText().isBlank()) loadSelectedNote();
        String append = "\n\n## Analysis " + Instant.now() + "\n\n" + output.trim() + "\n";
        notesArea.append(append);
        saveSelectedNote();
    }

    private String pinnedNoteContent() {
        if (!settings.injectPinnedNote()) return "";
        String pinned = state.pinnedNoteName();
        if (pinned.isBlank()) pinned = selectedNoteName();
        return pinned.isBlank() ? "" : notesStore.read(pinned);
    }

    private void stopCurrentAiWorker() {
        if (currentAiThread != null && currentAiThread.isAlive()) {
            currentAiThread.interrupt();
            setStatus("AI request cancelled.");
        }
    }

    private void refreshNoteList() {
        String selected = selectedNoteName();
        noteSelector.removeAllItems();
        List<String> names = notesStore.listNoteNames();
        if (names.isEmpty()) names = List.of(notesStore.ensureNote("DEFAULT"));
        for (String name : names) noteSelector.addItem(name);
        if (!selected.isBlank()) selectNote(selected);
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
        if (!found) noteSelector.addItem(clean);
        noteSelector.setSelectedItem(clean);
    }

    private void loadSelectedNote() {
        String name = selectedNoteName();
        if (name.isBlank()) return;
        notesStore.ensureNote(name);
        notesArea.setText(notesStore.read(name));
        notesArea.setCaretPosition(0);
        setStatus("Loaded note: " + name);
        updateContextCounter();
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
            updateContextCounter();
            if (settings.injectRag()) saveNoteToRag(name);
        } catch (Throwable throwable) {
            showError("Failed to save note", throwable);
        }
    }

    private void saveNoteToRag(String name) {
        String body = notesArea.getText();
        String target = state.current().map(TrafficSnapshot::hostLabel).orElse("");
        Thread thread = new Thread(() -> {
            try {
                lumaraClient.saveNote(settings, name, body, target, name + ".md", false);
            } catch (Throwable throwable) {
                api.logging().logToError("Burp Cockpit failed to save note to RAG", throwable);
            }
        }, "burp-cockpit-save-note-rag");
        thread.setDaemon(true);
        thread.start();
    }

    private void ingestNotes() {
        syncSettingsFromControls();
        setStatus("Asking Lumara RAG to ingest notes/library...");
        Thread thread = new Thread(() -> {
            try {
                lumaraClient.ingest(settings, "both");
                TextContextMenu.later(() -> setStatus("Lumara RAG ingest requested."));
            } catch (Throwable throwable) {
                TextContextMenu.later(() -> showError("Lumara RAG ingest failed", throwable));
            }
        }, "burp-cockpit-ingest");
        thread.setDaemon(true);
        thread.start();
    }

    private String selectedNoteName() {
        Object selected = noteSelector.getEditor().getItem();
        if (selected == null) selected = noteSelector.getSelectedItem();
        return NotesStore.sanitizeName(Objects.toString(selected, ""));
    }

    private void showSettingsDialog() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1;
        int row = 0;
        row = addSettingRow(panel, gbc, row, "Chat endpoint", chatEndpointField);
        row = addSettingRow(panel, gbc, row, "Model", modelField);
        row = addSettingRow(panel, gbc, row, "RAG search endpoint", ragEndpointField);
        row = addSettingRow(panel, gbc, row, "Notes directory", notesDirField);
        int choice = JOptionPane.showConfirmDialog(this, panel, "Burp Cockpit Settings", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice == JOptionPane.OK_OPTION) {
            syncSettingsFromControls();
            notesStore.root(settings.notesDirectory());
            refreshNoteList();
            setStatus("Settings saved.");
        }
    }

    private int addSettingRow(JPanel panel, GridBagConstraints gbc, int row, String label, Component field) {
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0;
        panel.add(new JLabel(label), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1;
        panel.add(field, gbc);
        return row + 1;
    }

    private void syncSettingsFromControls() {
        settings.chatEndpoint(chatEndpointField.getText());
        settings.model(modelField.getText());
        settings.ragSearchEndpoint(ragEndpointField.getText());
        settings.notesDirectory(notesDirField.getText());
        settings.streamChat(streamCheck.isSelected());
        settings.includeThinking(thinkingCheck.isSelected());
        settings.deltaOnly(deltaCheck.isSelected());
        settings.injectRag(ragCheck.isSelected());
        settings.injectPinnedNote(injectPinnedNoteCheck.isSelected());
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

    private void updateContextCounter() {
        int chat = PromptBuilder.estimatedTokens(requestArea.getText());
        int resp = PromptBuilder.estimatedTokens(responseArea.getText());
        int notes = PromptBuilder.estimatedTokens(pinnedNoteContent());
        int rag = PromptBuilder.estimatedTokens(lastRagDump);
        int total = chat + resp + notes + rag;
        contextLabel.setText("Chat: " + fmt(chat) + " | Resp: " + fmt(resp) + " | Notes " + fmt(notes) + " | RAG " + fmt(rag) + " | Total " + fmt(total));
    }

    private static String fmt(int tokens) {
        return String.format("%.1fk", tokens / 1000.0D);
    }

    private void toggleRightPane() {
        if (rightPaneVisible) {
            mainSplitPane.setRightComponent(new JPanel());
            mainSplitPane.setDividerLocation(1.0D);
            rightPaneVisible = false;
        } else {
            mainSplitPane.setRightComponent(rightPane);
            mainSplitPane.setResizeWeight(0.55D);
            rightPaneVisible = true;
        }
    }

    private void exportCurrent(String kind) {
        String raw = requestArea.getText();
        String export = "python".equals(kind) ? exportPython(raw) : exportCurl(raw);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(export), null);
        showExportPopup(kind, export);
        setStatus("Exported " + kind + " to clipboard.");
    }

    private void showExportPopup(String kind, String export) {
        JTextArea area = TextContextMenu.area(20, 90, false);
        area.setText(export);
        area.setCaretPosition(0);
        JOptionPane.showMessageDialog(this, new JScrollPane(area), "Exported " + kind, JOptionPane.INFORMATION_MESSAGE);
    }

    private static String exportCurl(String raw) {
        String[] lines = raw.replace("\r\n", "\n").split("\n");
        if (lines.length == 0) return "";
        String[] first = lines[0].split(" ");
        String method = first.length > 0 ? first[0] : "GET";
        String path = first.length > 1 ? first[1] : "/";
        String host = HttpText.hostHeader(raw);
        String body = HttpText.body(raw);
        StringBuilder out = new StringBuilder("curl -i -X ").append(shell(method)).append(' ');
        for (String line : HttpText.headers(raw).replace("\r\n", "\n").split("\n")) {
            if (line.toLowerCase().startsWith("host:")) continue;
            if (line.contains(":")) out.append("-H ").append(shell(line)).append(' ');
        }
        if (!body.isBlank()) out.append("--data-binary ").append(shell(body)).append(' ');
        out.append(shell("https://" + host + path));
        return out.toString();
    }

    private static String exportPython(String raw) {
        String[] lines = raw.replace("\r\n", "\n").split("\n");
        String[] first = lines.length == 0 ? new String[0] : lines[0].split(" ");
        String method = first.length > 0 ? first[0] : "GET";
        String path = first.length > 1 ? first[1] : "/";
        String host = HttpText.hostHeader(raw);
        String body = HttpText.body(raw);
        StringBuilder headers = new StringBuilder();
        for (String line : HttpText.headers(raw).replace("\r\n", "\n").split("\n")) {
            if (line.toLowerCase().startsWith("host:")) continue;
            int colon = line.indexOf(':');
            if (colon > 0) headers.append("    ").append(JsonUtil.quote(line.substring(0, colon).trim())).append(": ").append(JsonUtil.quote(line.substring(colon + 1).trim())).append(",\n");
        }
        return "import requests\n\nurl = " + JsonUtil.quote("https://" + host + path) + "\nheaders = {\n" + headers + "}\nbody = " + JsonUtil.quote(body) + "\n\nr = requests.request(" + JsonUtil.quote(method) + ", url, headers=headers, data=body)\nprint(r.status_code)\nprint(r.text)\n";
    }

    private static String shell(String value) {
        return "'" + Objects.toString(value, "").replace("'", "'\\''") + "'";
    }

    private void setStatus(String status) {
        statusLabel.setText(status);
        api.logging().logToOutput("Burp Cockpit: " + status);
    }

    private void showError(String message, Throwable throwable) {
        String detail = throwable == null ? "" : throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
        setStatus(message + (detail.isBlank() ? "" : " - " + detail));
        if (throwable != null) api.logging().logToError(message, throwable);
        JOptionPane.showMessageDialog(this, message + (detail.isBlank() ? "" : "\n" + detail), "Burp Cockpit", JOptionPane.ERROR_MESSAGE);
    }

    private static boolean looksLikeBurpErrorHtml(String text) {
        String value = Objects.toString(text, "");
        return value.contains("Burp Suite") && value.contains("Invalid client request received");
    }
}
