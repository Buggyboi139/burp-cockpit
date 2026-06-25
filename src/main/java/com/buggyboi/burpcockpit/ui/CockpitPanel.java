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
import javax.swing.Timer;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static burp.api.montoya.http.message.requests.HttpRequest.httpRequest;

public final class CockpitPanel extends JPanel {
    private static final Dimension TOKEN_SELECTOR_SIZE = new Dimension(58, 24);
    private static final Dimension NOTE_SELECTOR_SIZE = new Dimension(230, 24);
    private static final Dimension NOTE_NAME_SIZE = new Dimension(230, 24);

    private final MontoyaApi api;
    private final CockpitState state;
    private final CockpitSettings settings;
    private final NotesStore notesStore;
    private final LumaraClient lumaraClient;

    private final JTextArea requestArea = TextContextMenu.area(24, 90, true);
    private final JTextArea responseArea = TextContextMenu.area(12, 90, false);
    private final JTextArea transcriptArea = TextContextMenu.area(24, 70, false);
    private final JTextArea promptArea = TextContextMenu.area(4, 70, true);
    private final JTextArea notesArea = TextContextMenu.area(24, 70, true);

    private final JTextField chatEndpointField;
    private final JTextField modelField;
    private final JTextField ragEndpointField;
    private final JTextField notesDirField;
    private final JTextField noteNameField = TextContextMenu.field("");

    private final JComboBox<String> tokenSelector = new JComboBox<>(new String[]{"1k", "2k", "20k", "96k"});
    private final JComboBox<String> noteSelector = new JComboBox<>();
    private final JCheckBox thinkingCheck;
    private final JCheckBox deltaCheck;
    private final JCheckBox ragCheck;
    private final JLabel statusLabel = new JLabel("Analysis ready.");
    private final JLabel historyLabel = new JLabel("0/0");
    private final JLabel contextLabel = new JLabel("Ctx req 0.0k | resp 0.0k | notes 0.0k | rag 0.0k | total 0.0k");

    private JSplitPane mainSplitPane;
    private JTabbedPane rightTabs;
    private Component rightPane;
    private boolean rightPaneVisible = true;
    private Thread currentAiThread;
    private String lastRagDump = "";
    private Timer busyTimer;
    private int busyTick;
    private boolean suppressNoteEvents;

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
        this.thinkingCheck = new JCheckBox("Thinking", settings.includeThinking());
        this.deltaCheck = new JCheckBox("Delta only", settings.deltaOnly());
        this.ragCheck = new JCheckBox("RAG", settings.injectRag());
        configureControls();
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

    public void ensureCurrentHostNote() {
        TextContextMenu.later(() -> autoLoadHostNote(state.current().map(TrafficSnapshot::hostLabel).orElse("DEFAULT")));
    }

    private void configureControls() {
        promptArea.setLineWrap(true);
        promptArea.setWrapStyleWord(true);
        transcriptArea.setLineWrap(true);
        transcriptArea.setWrapStyleWord(true);
        notesArea.setLineWrap(true);
        notesArea.setWrapStyleWord(true);

        tokenSelector.setPrototypeDisplayValue("20k");
        tokenSelector.setPreferredSize(TOKEN_SELECTOR_SIZE);
        tokenSelector.setMaximumSize(TOKEN_SELECTOR_SIZE);
        tokenSelector.setMinimumSize(TOKEN_SELECTOR_SIZE);

        noteSelector.setEditable(true);
        noteSelector.setPreferredSize(NOTE_SELECTOR_SIZE);
        noteSelector.setMaximumSize(NOTE_SELECTOR_SIZE);
        noteSelector.setPrototypeDisplayValue("script.google.com...........");
        Component editor = noteSelector.getEditor().getEditorComponent();
        if (editor instanceof JTextField textField) {
            TextContextMenu.install(textField);
        }
        noteSelector.addActionListener(e -> {
            if (suppressNoteEvents) {
                return;
            }
            Object item = noteSelector.getEditor().getItem();
            if (item == null) {
                item = noteSelector.getSelectedItem();
            }
            String name = NotesStore.sanitizeName(Objects.toString(item, ""));
            if (!name.isBlank()) {
                noteNameField.setText(name);
            }
        });

        noteNameField.setPreferredSize(NOTE_NAME_SIZE);
        noteNameField.setMaximumSize(NOTE_NAME_SIZE);
        contextLabel.setMaximumSize(new Dimension(540, 22));
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

        JButton previous = new JButton("←");
        previous.addActionListener(e -> state.previous().ifPresent(this::loadSnapshotWithoutPush));
        bar.add(previous);

        JButton next = new JButton("→");
        next.addActionListener(e -> state.next().ifPresent(this::loadSnapshotWithoutPush));
        bar.add(next);

        JButton send = new JButton("Send");
        send.addActionListener(e -> sendCurrentRequest());
        bar.add(send);

        JButton clearCache = new JButton("Clear Cache");
        clearCache.addActionListener(e -> clearContextCache());
        bar.add(clearCache);

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
        bar.add(thinkingCheck);
        bar.add(deltaCheck);
        bar.add(ragCheck);
        return bar;
    }

    private Component buildAnalysisPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JPanel top = new JPanel(new BorderLayout(4, 4));
        promptArea.setText("What are the highest-value bug bounty tests for this request/response?");
        top.add(wrap("Prompt", promptArea), BorderLayout.CENTER);

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

        JPanel center = new JPanel(new BorderLayout(4, 4));
        center.add(contextLabel, BorderLayout.NORTH);
        center.add(wrap("Chat", transcriptArea), BorderLayout.CENTER);
        panel.add(center, BorderLayout.CENTER);
        return panel;
    }

    private Component buildNotesPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(new JLabel("Open:"));
        top.add(noteSelector);

        JButton load = new JButton("Load");
        load.addActionListener(e -> loadSelectedNote());
        top.add(load);

        JButton newNote = new JButton("New Note");
        newNote.addActionListener(e -> createNewNote());
        top.add(newNote);

        top.add(new JLabel("Name:"));
        top.add(noteNameField);

        JButton save = new JButton("Save");
        save.addActionListener(e -> saveSelectedNote());
        top.add(save);

        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(e -> refreshNoteList());
        top.add(refresh);

        panel.add(top, BorderLayout.NORTH);
        panel.add(wrap("Local Markdown notes", notesArea), BorderLayout.CENTER);
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
        thinkingCheck.addActionListener(e -> settings.includeThinking(thinkingCheck.isSelected()));
        deltaCheck.addActionListener(e -> settings.deltaOnly(deltaCheck.isSelected()));
        ragCheck.addActionListener(e -> settings.injectRag(ragCheck.isSelected()));
        tokenSelector.addActionListener(e -> settings.tokenBudget(parseTokenSelection()));
    }

    private void openStarterRequest() {
        String starter = "GET / HTTP/1.1\r\nHost: example.com\r\nUser-Agent: Burp-Cockpit\r\nAccept: */*\r\n\r\n";
        loadSnapshot(new TrafficSnapshot(null, starter, "", Instant.now(), "new"));
    }

    private void loadSnapshot(TrafficSnapshot snapshot) {
        quietSaveActiveNote();
        state.pushSnapshot(snapshot);
        loadSnapshotWithoutPush(snapshot);
        autoLoadHostNote(snapshot.hostLabel());
    }

    private void loadSnapshotWithoutPush(TrafficSnapshot snapshot) {
        requestArea.setText(snapshot.requestText());
        requestArea.setCaretPosition(0);
        responseArea.setText(snapshot.responseText());
        responseArea.setCaretPosition(0);
        historyLabel.setText(state.historyLabel());
        setStatus("Loaded " + HttpText.shortSummary(snapshot.requestText(), snapshot.service()));
        updateContextCounter();
    }

    private void syncSnapshotFromEditors(String source) {
        HttpService service = state.currentService();
        if (service == null) {
            service = HttpText.inferService(requestArea.getText(), true).orElse(null);
        }
        TrafficSnapshot snapshot = new TrafficSnapshot(service, requestArea.getText(), responseArea.getText(), Instant.now(), source);
        state.pushSnapshot(snapshot);
        historyLabel.setText(state.historyLabel());
        updateContextCounter();
    }

    private void clearContextCache() {
        quietSaveActiveNote();
        stopBusyIndicator();
        lastRagDump = "";
        HttpService service = state.currentService();
        if (service == null) {
            service = HttpText.inferService(requestArea.getText(), true).orElse(null);
        }
        TrafficSnapshot snapshot = new TrafficSnapshot(service, requestArea.getText(), responseArea.getText(), Instant.now(), "cache reset");
        state.resetToCurrentSnapshot(snapshot);
        historyLabel.setText(state.historyLabel());
        updateContextCounter();
        setStatus("Cache cleared. Context reset to current request, response, and note.");
    }

    private void sendCurrentRequest() {
        syncSettingsFromControls();
        quietSaveActiveNote();
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
        quietSaveActiveNote();
        syncSnapshotFromEditors(analysis ? "analysis context" : "chat context");
        if (looksLikeBurpErrorHtml(requestArea.getText())) {
            showError("The current request is Burp's HTML proxy error page. Reload the original request, then run analysis again.", null);
            return;
        }
        if (currentAiThread != null && currentAiThread.isAlive()) {
            setStatus("AI is already working. Stop it first.");
            return;
        }
        transcriptArea.setText("");
        lastRagDump = "";
        updateContextCounter();
        startBusyIndicator();
        setStatus(settings.injectRag() ? "Preparing RAG context..." : "Streaming response...");

        Thread worker = new Thread(() -> {
            AtomicBoolean contentStarted = new AtomicBoolean(false);
            try {
                String ragDump = "";
                if (settings.injectRag()) {
                    try {
                        String query = PromptBuilder.ragQuery(state, promptArea.getText());
                        ragDump = lumaraClient.ragSearch(settings, query, 8, "both");
                    } catch (Throwable throwable) {
                        ragDump = "RAG injection failed: " + Objects.toString(throwable.getMessage(), throwable.getClass().getSimpleName());
                    }
                }

                String finalRagDump = ragDump;
                TextContextMenu.later(() -> {
                    lastRagDump = finalRagDump;
                    updateContextCounter();
                    setStatus("Streaming response...");
                });

                String prompt = analysis
                        ? PromptBuilder.analysisPrompt(state, promptArea.getText(), activeNoteContent(), ragDump)
                        : PromptBuilder.chatPrompt(state, promptArea.getText(), activeNoteContent(), ragDump);
                String system = PromptBuilder.systemPrompt(settings.includeThinking());

                lumaraClient.streamChat(settings, system, prompt, contentToken -> TextContextMenu.later(() -> {
                    if (contentStarted.compareAndSet(false, true)) {
                        stopBusyIndicator();
                        transcriptArea.setText("");
                    }
                    transcriptArea.append(contentToken);
                    transcriptArea.setCaretPosition(transcriptArea.getDocument().getLength());
                }));

                TextContextMenu.later(() -> {
                    stopBusyIndicator();
                    if (!contentStarted.get() && transcriptArea.getText().startsWith("Working")) {
                        transcriptArea.setText("No streamed content was returned by the model.");
                    }
                    state.lastPromptRequest(requestArea.getText());
                    if (analysis) {
                        appendAnalysisToNotes(transcriptArea.getText());
                    }
                    setStatus("AI response ready.");
                });
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                TextContextMenu.later(() -> {
                    stopBusyIndicator();
                    setStatus("AI request cancelled.");
                });
            } catch (Throwable throwable) {
                TextContextMenu.later(() -> {
                    stopBusyIndicator();
                    showError("Lumara call failed", throwable);
                });
            } finally {
                currentAiThread = null;
            }
        }, "burp-cockpit-ai");
        currentAiThread = worker;
        worker.setDaemon(true);
        worker.start();
    }

    private void startBusyIndicator() {
        stopBusyIndicator();
        busyTick = 0;
        transcriptArea.setText("Working...");
        busyTimer = new Timer(450, e -> {
            String[] states = {"Working", "Working.", "Working..", "Working..."};
            transcriptArea.setText(states[busyTick++ % states.length]);
        });
        busyTimer.start();
    }

    private void stopBusyIndicator() {
        if (busyTimer != null) {
            busyTimer.stop();
            busyTimer = null;
        }
    }

    private void appendAnalysisToNotes(String output) {
        if (output == null || output.isBlank() || output.startsWith("Working")) {
            return;
        }
        String append = "\n\n## Analysis " + Instant.now() + "\n\n" + output.trim() + "\n";
        notesArea.append(append);
        quietSaveActiveNote();
    }

    private String activeNoteContent() {
        String text = notesArea.getText();
        if (!text.isBlank()) {
            return text;
        }
        String name = currentNoteName();
        return name.isBlank() ? "" : notesStore.read(name);
    }

    private void stopCurrentAiWorker() {
        if (currentAiThread != null && currentAiThread.isAlive()) {
            currentAiThread.interrupt();
            stopBusyIndicator();
            setStatus("AI request cancelled.");
        }
    }

    private void refreshNoteList() {
        String selected = currentNoteName();
        suppressNoteEvents = true;
        noteSelector.removeAllItems();
        List<String> names = notesStore.listNoteNames();
        if (names.isEmpty()) {
            names = List.of(notesStore.ensureNote("DEFAULT"));
        }
        for (String name : names) {
            noteSelector.addItem(name);
        }
        suppressNoteEvents = false;
        if (!selected.isBlank()) {
            selectNote(selected);
        } else if (!names.isEmpty()) {
            selectNote(names.get(0));
        }
    }

    private void autoLoadHostNote(String hostLabel) {
        String name = notesStore.defaultNoteNameForHost(hostLabel);
        notesStore.ensureNote(name);
        refreshNoteList();
        selectNote(name);
        notesArea.setText(notesStore.read(name));
        notesArea.setCaretPosition(0);
        state.pinnedNoteName(name);
        updateContextCounter();
    }

    private void selectNote(String name) {
        String clean = NotesStore.sanitizeName(name);
        suppressNoteEvents = true;
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
        noteSelector.getEditor().setItem(clean);
        noteNameField.setText(clean);
        suppressNoteEvents = false;
    }

    private void createNewNote() {
        String defaultName = state.current().map(s -> notesStore.defaultNoteNameForHost(s.hostLabel())).orElse("DEFAULT");
        String entered = JOptionPane.showInputDialog(this, "New note name", defaultName);
        String name = NotesStore.sanitizeName(entered);
        if (name.isBlank()) {
            return;
        }
        quietSaveActiveNote();
        notesStore.ensureNote(name);
        refreshNoteList();
        selectNote(name);
        notesArea.setText(notesStore.read(name));
        notesArea.setCaretPosition(0);
        state.pinnedNoteName(name);
        setStatus("Created note: " + name);
        updateContextCounter();
    }

    private void loadSelectedNote() {
        quietSaveActiveNote();
        String name = selectedComboNoteName();
        if (name.isBlank()) {
            name = currentNoteName();
        }
        if (name.isBlank()) {
            return;
        }
        notesStore.ensureNote(name);
        selectNote(name);
        notesArea.setText(notesStore.read(name));
        notesArea.setCaretPosition(0);
        state.pinnedNoteName(name);
        setStatus("Loaded note: " + name);
        updateContextCounter();
    }

    private void saveSelectedNote() {
        String name = currentNoteName();
        if (name.isBlank()) {
            name = "DEFAULT";
        }
        try {
            notesStore.write(name, notesArea.getText());
            refreshNoteList();
            selectNote(name);
            state.pinnedNoteName(name);
            setStatus("Saved note locally: " + name);
            updateContextCounter();
        } catch (Throwable throwable) {
            showError("Failed to save note", throwable);
        }
    }

    private void quietSaveActiveNote() {
        String name = currentNoteName();
        if (name.isBlank()) {
            return;
        }
        try {
            notesStore.write(name, notesArea.getText());
            state.pinnedNoteName(name);
        } catch (Throwable throwable) {
            api.logging().logToError("Burp Cockpit failed to auto-save note", throwable);
        }
    }

    private String currentNoteName() {
        String typedName = NotesStore.sanitizeName(noteNameField.getText());
        if (!typedName.isBlank()) {
            return typedName;
        }
        return selectedComboNoteName();
    }

    private String selectedComboNoteName() {
        Object selected = noteSelector.getEditor().getItem();
        if (selected == null) {
            selected = noteSelector.getSelectedItem();
        }
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
        settings.includeThinking(thinkingCheck.isSelected());
        settings.deltaOnly(deltaCheck.isSelected());
        settings.injectRag(ragCheck.isSelected());
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
        if (value <= 1100) {
            tokenSelector.setSelectedItem("1k");
        } else if (value <= 2200) {
            tokenSelector.setSelectedItem("2k");
        } else if (value >= 90000) {
            tokenSelector.setSelectedItem("96k");
        } else {
            tokenSelector.setSelectedItem("20k");
        }
    }

    private void updateContextCounter() {
        int req = PromptBuilder.estimatedTokens(PromptBuilder.requestContext(requestArea.getText()));
        int resp = PromptBuilder.estimatedTokens(PromptBuilder.responseContext(responseArea.getText()));
        int notes = PromptBuilder.estimatedTokens(PromptBuilder.notesContext(activeNoteContent()));
        int rag = PromptBuilder.estimatedTokens(PromptBuilder.ragContext(lastRagDump));
        int total = req + resp + notes + rag;
        contextLabel.setText("Ctx req " + fmt(req) + " | resp " + fmt(resp) + " | notes " + fmt(notes) + " | rag " + fmt(rag) + " | total " + fmt(total));
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
        if (lines.length == 0) {
            return "";
        }
        String[] first = lines[0].split(" ");
        String method = first.length > 0 ? first[0] : "GET";
        String path = first.length > 1 ? first[1] : "/";
        String host = HttpText.hostHeader(raw);
        String body = HttpText.body(raw);
        StringBuilder out = new StringBuilder("curl -i -X ").append(shell(method)).append(' ');
        for (String line : HttpText.headers(raw).replace("\r\n", "\n").split("\n")) {
            if (line.toLowerCase().startsWith("host:")) {
                continue;
            }
            if (line.contains(":")) {
                out.append("-H ").append(shell(line)).append(' ');
            }
        }
        if (!body.isBlank()) {
            out.append("--data-binary ").append(shell(body)).append(' ');
        }
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
            if (line.toLowerCase().startsWith("host:")) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.append("    ").append(JsonUtil.quote(line.substring(0, colon).trim())).append(": ").append(JsonUtil.quote(line.substring(colon + 1).trim())).append(",\n");
            }
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
        if (throwable != null) {
            api.logging().logToError(message, throwable);
        }
        JOptionPane.showMessageDialog(this, message + (detail.isBlank() ? "" : "\n" + detail), "Burp Cockpit", JOptionPane.ERROR_MESSAGE);
    }

    private static boolean looksLikeBurpErrorHtml(String text) {
        String value = Objects.toString(text, "");
        return value.contains("Burp Suite") && value.contains("Invalid client request received");
    }
}
