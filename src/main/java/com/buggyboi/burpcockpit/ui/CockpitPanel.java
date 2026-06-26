package com.buggyboi.burpcockpit.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.core.Range;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.Selection;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import com.buggyboi.burpcockpit.lumara.LumaraClient;
import com.buggyboi.burpcockpit.notes.NotesStore;
import com.buggyboi.burpcockpit.state.CockpitSettings;
import com.buggyboi.burpcockpit.state.CockpitState;
import com.buggyboi.burpcockpit.state.TrafficSnapshot;
import com.buggyboi.burpcockpit.util.CodecChain;
import com.buggyboi.burpcockpit.util.HttpText;
import com.buggyboi.burpcockpit.util.JsonUtil;
import com.buggyboi.burpcockpit.util.PromptBuilder;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
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
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static burp.api.montoya.http.message.requests.HttpRequest.httpRequest;
import static burp.api.montoya.http.message.responses.HttpResponse.httpResponse;

public final class CockpitPanel extends JPanel {
    private static final Dimension TOKEN_SELECTOR_SIZE = new Dimension(58, 24);
    private static final Dimension NOTE_SELECTOR_SIZE = new Dimension(230, 24);
    private static final Dimension NOTE_NAME_SIZE = new Dimension(230, 24);
    private static final Color HEADER_ACCENT = new Color(82, 145, 204);

    private final MontoyaApi api;
    private final CockpitState state;
    private final CockpitSettings settings;
    private final NotesStore notesStore;
    private final LumaraClient lumaraClient;

    private final HttpRequestEditor requestEditor;
    private final HttpResponseEditor responseEditor;
    private final TextContextMenu.ChatTranscriptPane transcriptArea = TextContextMenu.transcript(24, 70);
    private final JTextArea promptArea = TextContextMenu.area(4, 70, true);
    private final JTextArea notesArea = TextContextMenu.area(24, 70, true);
    private final JLabel requestSummaryLabel = new JLabel("No request loaded.");
    private final JLabel responseSummaryLabel = new JLabel("No response loaded.");

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
    private final JCheckBox notesCheck = new JCheckBox("Notes", false);
    private final JLabel statusLabel = new JLabel("Analysis ready.");
    private final JLabel chatStatusLabel = new JLabel("Chat ready.");
    private final JLabel historyLabel = new JLabel("0/0");
    private final JLabel contextLabel = new JLabel("Mode: Chat | Traffic 0.0k | Notes off | RAG off | Total 0.0k");

    private JSplitPane mainSplitPane;
    private JTabbedPane rightTabs;
    private Component rightPane;
    private boolean rightPaneVisible = true;
    private Thread currentAiThread;
    private String lastRagDump = "";
    private String lastMode = "Chat";
    private Timer busyTimer;
    private Timer noteSaveTimer;
    private int busyTick;
    private boolean suppressNoteEvents;
    private boolean loadingNote;
    private String activeNoteName = "";
    private String requestTextCache = "";
    private String responseTextCache = "";

    public CockpitPanel(MontoyaApi api, CockpitState state, LumaraClient lumaraClient) {
        super(new BorderLayout());
        this.api = api;
        this.state = state;
        this.settings = state.settings();
        this.notesStore = state.notesStore();
        this.lumaraClient = lumaraClient;
        this.requestEditor = api.userInterface().createHttpRequestEditor(EditorOptions.WRAP_LINES);
        this.responseEditor = api.userInterface().createHttpResponseEditor(EditorOptions.READ_ONLY, EditorOptions.WRAP_LINES);
        this.chatEndpointField = TextContextMenu.field(settings.chatEndpoint());
        this.modelField = TextContextMenu.field(settings.model());
        this.ragEndpointField = TextContextMenu.field(settings.ragSearchEndpoint());
        this.notesDirField = TextContextMenu.field(settings.notesDirectory().toString());
        this.thinkingCheck = new JCheckBox("Thinking", settings.includeThinking());
        this.deltaCheck = new JCheckBox("Delta only", settings.deltaOnly());
        this.ragCheck = new JCheckBox("RAG", false);
        settings.injectRag(false);
        configureControls();
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

    private void configureControls() {
        promptArea.setLineWrap(true);
        promptArea.setWrapStyleWord(true);
        notesArea.setLineWrap(true);
        notesArea.setWrapStyleWord(true);
        configureNoteAutosave();

        tokenSelector.setPrototypeDisplayValue("20k");
        tokenSelector.setPreferredSize(TOKEN_SELECTOR_SIZE);
        tokenSelector.setMaximumSize(TOKEN_SELECTOR_SIZE);
        tokenSelector.setMinimumSize(TOKEN_SELECTOR_SIZE);

        noteSelector.setEditable(true);
        noteSelector.setPreferredSize(NOTE_SELECTOR_SIZE);
        noteSelector.setMaximumSize(NOTE_SELECTOR_SIZE);
        noteSelector.setPrototypeDisplayValue("script.google.com...........");
        Component editor = noteSelector.getEditor().getEditorComponent();
        if (editor instanceof JTextField textField) TextContextMenu.install(textField);
        noteSelector.addActionListener(e -> {
            if (suppressNoteEvents) return;
            String name = selectedComboNoteName();
            if (!name.isBlank()) noteNameField.setText(name);
        });

        noteNameField.setPreferredSize(NOTE_NAME_SIZE);
        noteNameField.setMaximumSize(NOTE_NAME_SIZE);
        contextLabel.setMaximumSize(new Dimension(660, 22));
        chatStatusLabel.setBorder(BorderFactory.createEmptyBorder(2, 4, 0, 4));
        notesCheck.setToolTipText("Include the active local note as read-only AI context.");
        notesCheck.addActionListener(e -> updateContextCounter());
    }

    private void configureNoteAutosave() {
        noteSaveTimer = new Timer(900, e -> saveActiveNoteAfterEdit());
        noteSaveTimer.setRepeats(false);
        notesArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { scheduleNoteSaveAfterEdit(); }
            @Override public void removeUpdate(DocumentEvent e) { scheduleNoteSaveAfterEdit(); }
            @Override public void changedUpdate(DocumentEvent e) { scheduleNoteSaveAfterEdit(); }
        });
    }

    private void scheduleNoteSaveAfterEdit() {
        if (loadingNote || activeNoteName.isBlank()) return;
        noteSaveTimer.restart();
        updateContextCounter();
    }

    private void buildUi() {
        setPreferredSize(new Dimension(1300, 880));
        add(buildToolbar(), BorderLayout.NORTH);

        JSplitPane messages = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT,
                buildMessagePanel("Request", requestEditor.uiComponent(), true),
                buildMessagePanel("Response", responseEditor.uiComponent(), false));
        messages.setResizeWeight(0.62);

        rightTabs = new JTabbedPane();
        rightTabs.addTab("Analysis", buildAnalysisPanel());
        rightTabs.addTab("Notes", buildNotesPanel());
        rightPane = rightTabs;

        mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, messages, rightPane);
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
        bar.setBorder(BorderFactory.createEmptyBorder(5, 6, 5, 6));

        JButton newButton = toolbarButton("New", "Start a fresh editable request.");
        newButton.addActionListener(e -> openStarterRequest());

        JButton previous = toolbarButton("←", "Load the previous Cockpit history item.");
        previous.addActionListener(e -> state.previous().ifPresent(this::loadSnapshotWithoutPush));

        JButton next = toolbarButton("→", "Load the next Cockpit history item.");
        next.addActionListener(e -> state.next().ifPresent(this::loadSnapshotWithoutPush));

        addToolbarGroup(bar, "Navigate", newButton, previous, next);

        JButton send = toolbarButton("Send", "Send the current request through Burp.");
        send.setFont(send.getFont().deriveFont(Font.BOLD));
        send.setBackground(HEADER_ACCENT);
        send.setForeground(Color.WHITE);
        send.setOpaque(true);
        send.addActionListener(e -> sendCurrentRequest());

        JButton autoDecode = toolbarButton("Auto Decode", "Decode, edit, and re-encode the selected request text.");
        autoDecode.addActionListener(e -> autoDecodeSelection());

        JButton refreshCookies = toolbarButton("Cookies", "Refresh matching cookies from Burp's cookie jar.");
        refreshCookies.addActionListener(e -> refreshCookiesFromBurp());

        JButton clearCache = toolbarButton("Clear Cache", "Reset AI context to the visible request and response.");
        clearCache.addActionListener(e -> clearContextCache());

        addToolbarGroup(bar, "Traffic", send, autoDecode, refreshCookies, clearCache);

        JButton exportCurl = toolbarButton("curl", "Copy the current request as a curl command.");
        exportCurl.addActionListener(e -> exportCurrent("curl"));

        JButton exportPython = toolbarButton("Python", "Copy the current request as Python requests code.");
        exportPython.addActionListener(e -> exportCurrent("python"));

        addToolbarGroup(bar, "Export", exportCurl, exportPython);

        JButton toggleRight = toolbarButton("Hide Panel", "Show or hide the Analysis and Notes panel.");
        toggleRight.addActionListener(e -> {
            toggleRightPane();
            toggleRight.setText(rightPaneVisible ? "Hide Panel" : "Show Panel");
        });

        JButton settingsButton = toolbarButton("Settings", "Open Burp Cockpit settings.");
        settingsButton.addActionListener(e -> showSettingsDialog());

        addToolbarGroup(bar, "View", toggleRight, settingsButton);

        JPanel context = toolbarSection("AI Context");
        context.add(new JLabel("Tokens"));
        context.add(tokenSelector);
        context.add(thinkingCheck);
        context.add(deltaCheck);
        context.add(ragCheck);
        bar.add(context);
        return bar;
    }

    private void addToolbarGroup(JToolBar bar, String title, Component... components) {
        if (bar.getComponentCount() > 0) bar.add(Box.createHorizontalStrut(10));
        JPanel group = toolbarSection(title);
        for (Component component : components) group.add(component);
        bar.add(group);
    }

    private JPanel toolbarSection(String title) {
        JPanel group = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        group.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, mutedBorderColor()),
                BorderFactory.createEmptyBorder(0, 0, 0, 10)));
        JLabel label = new JLabel(title);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 11.0F));
        group.add(label);
        return group;
    }

    private JPanel compactGroup() {
        JPanel group = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        group.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(mutedBorderColor()),
                BorderFactory.createEmptyBorder(2, 5, 2, 5)));
        return group;
    }

    private JButton toolbarButton(String text, String tooltip) {
        JButton button = new JButton(text);
        button.setToolTipText(tooltip);
        button.setMargin(new Insets(3, 10, 3, 10));
        button.setFocusable(false);
        return button;
    }

    private JPanel buildMessagePanel(String title, Component editor, boolean request) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(mutedBorderColor()),
                BorderFactory.createEmptyBorder(0, 0, 0, 0)));

        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(BorderFactory.createEmptyBorder(5, 7, 5, 7));
        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD));
        header.add(titleLabel, BorderLayout.WEST);

        JLabel summary = request ? requestSummaryLabel : responseSummaryLabel;
        summary.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, mutedBorderColor()),
                BorderFactory.createEmptyBorder(3, 7, 3, 7)));

        panel.add(header, BorderLayout.NORTH);
        panel.add(editor, BorderLayout.CENTER);
        panel.add(summary, BorderLayout.SOUTH);
        return panel;
    }

    private Component buildAnalysisPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        promptArea.setText("What are the highest-value bug bounty tests for this request/response?");

        JPanel topStatus = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        topStatus.add(contextLabel);
        panel.add(topStatus, BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(4, 4));
        center.add(wrap("Chat", transcriptArea), BorderLayout.CENTER);
        center.add(chatStatusLabel, BorderLayout.SOUTH);
        panel.add(center, BorderLayout.CENTER);

        JPanel south = new JPanel(new BorderLayout(4, 4));
        JScrollPane promptScroll = new JScrollPane(promptArea);
        promptScroll.setBorder(BorderFactory.createEmptyBorder());
        south.add(promptScroll, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        JPanel contextButtons = compactGroup();
        contextButtons.add(notesCheck);
        contextButtons.add(ragCheck);
        buttons.add(contextButtons);

        JPanel actionButtons = compactGroup();
        JButton chat = toolbarButton("Send Chat", "Send the prompt as a short chat question.");
        chat.addActionListener(e -> runChatFromUi());
        actionButtons.add(chat);

        JButton analyze = toolbarButton("Analyze", "Run a deeper security analysis over the current traffic.");
        analyze.addActionListener(e -> runAnalysisFromUi());
        actionButtons.add(analyze);

        JButton stop = toolbarButton("Stop", "Cancel the current AI request.");
        stop.addActionListener(e -> stopCurrentAiWorker());
        actionButtons.add(stop);

        JButton clearChat = toolbarButton("Clear", "Clear the chat transcript.");
        clearChat.addActionListener(e -> clearChatTranscript());
        actionButtons.add(clearChat);
        buttons.add(actionButtons);

        south.add(buttons, BorderLayout.SOUTH);
        panel.add(south, BorderLayout.SOUTH);
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

    private JScrollPane wrap(String title, Component component) {
        if (component instanceof JTextArea area) area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scroll = new JScrollPane(component);
        scroll.setBorder(BorderFactory.createTitledBorder(title));
        return scroll;
    }

    private JPanel frame(String title, Component component) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(title));
        panel.add(component, BorderLayout.CENTER);
        return panel;
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
        state.pushSnapshot(snapshot);
        loadSnapshotWithoutPush(snapshot);
    }

    private void loadSnapshotWithoutPush(TrafficSnapshot snapshot) {
        flushPendingNoteSave();
        setRequestEditorText(snapshot.requestText(), snapshot.service());
        setResponseEditorText(snapshot.responseText());
        loadNoteForSnapshot(snapshot);
        historyLabel.setText(state.historyLabel());
        setStatus("Loaded " + HttpText.shortSummary(snapshot.requestText(), snapshot.service()));
        updateContextCounter();
    }

    private void setRequestEditorText(String rawRequest, HttpService service) {
        requestTextCache = Objects.toString(rawRequest, "");
        try {
            HttpRequest request = service == null ? httpRequest(requestTextCache) : httpRequest(service, requestTextCache);
            requestEditor.setRequest(request);
            requestEditor.setCaretPosition(0);
        } catch (Throwable throwable) {
            api.logging().logToError("Burp Cockpit failed to load request editor", throwable);
        }
    }

    private void setResponseEditorText(String rawResponse) {
        responseTextCache = Objects.toString(rawResponse, "");
        try {
            responseEditor.setResponse(responseTextCache.isBlank() ? httpResponse() : httpResponse(responseTextCache));
            responseEditor.setCaretPosition(0);
        } catch (Throwable throwable) {
            api.logging().logToError("Burp Cockpit failed to load response editor", throwable);
        }
    }

    private String requestText() {
        try {
            HttpRequest request = requestEditor.getRequest();
            if (request != null) {
                requestTextCache = request.toString();
            }
        } catch (Throwable throwable) {
            api.logging().logToError("Burp Cockpit failed to read request editor", throwable);
        }
        return requestTextCache;
    }

    private String responseText() {
        try {
            HttpResponse response = responseEditor.getResponse();
            if (response != null) {
                responseTextCache = response.toString();
            }
        } catch (Throwable throwable) {
            api.logging().logToError("Burp Cockpit failed to read response editor", throwable);
        }
        return responseTextCache;
    }

    private Optional<HttpService> editorService() {
        try {
            HttpRequest request = requestEditor.getRequest();
            return request == null ? Optional.empty() : Optional.ofNullable(request.httpService());
        } catch (Throwable ignored) {
            return Optional.empty();
        }
    }

    private void syncSnapshotFromEditors(String source) {
        HttpService service = state.currentService();
        String requestText = requestText();
        if (service == null) service = editorService().orElse(null);
        if (service == null) service = HttpText.inferService(requestText, true).orElse(null);
        TrafficSnapshot snapshot = new TrafficSnapshot(service, requestText, responseText(), Instant.now(), source);
        state.pushSnapshot(snapshot);
        historyLabel.setText(state.historyLabel());
        updateContextCounter();
    }

    private void clearContextCache() {
        stopBusyIndicator();
        lastRagDump = "";
        HttpService service = state.currentService();
        String requestText = requestText();
        if (service == null) service = editorService().orElse(null);
        if (service == null) service = HttpText.inferService(requestText, true).orElse(null);
        TrafficSnapshot snapshot = new TrafficSnapshot(service, requestText, responseText(), Instant.now(), "cache reset");
        state.resetToCurrentSnapshot(snapshot);
        historyLabel.setText(state.historyLabel());
        updateContextCounter();
        setStatus("Cache cleared. Context reset to current request and response. Notes and RAG are opt-in for chat.");
        setChatStatus("Chat cache cleared.");
    }

    private void sendCurrentRequest() {
        syncSettingsFromControls();
        updateContextCounter();
        String raw = HttpText.normalizeLineEndings(requestText());
        if (looksLikeBurpErrorHtml(raw)) {
            showError("The request editor contains Burp's HTML proxy error page, not the target request. Reload the original target request from Burp history/repeater.", null);
            return;
        }
        HttpService service = state.currentService();
        if (service == null) service = editorService().orElse(null);
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

    private void autoDecodeSelection() {
        try {
            Optional<Selection> selected = requestEditor.selection();
            if (selected.isEmpty() || selected.get().contents().length() == 0) {
                setStatus("Auto Decode skipped: select encoded text in the request editor first.");
                return;
            }

            Selection selection = selected.get();
            Range range = selection.offsets();
            if (range.startIndexInclusive() >= range.endIndexExclusive()) {
                setStatus("Auto Decode skipped: select encoded text in the request editor first.");
                return;
            }

            ByteArray originalBytes = selection.contents().copy();
            String originalText = new String(originalBytes.getBytes(), StandardCharsets.UTF_8);
            CodecChain.Result result = CodecChain.decode(originalText);
            if (!result.decodedAnything()) {
                setStatus("Auto Decode found no supported URL/Base64 encoding in the request selection.");
                return;
            }

            showAutoDecodeDialog(originalBytes, range, result);
        } catch (Throwable throwable) {
            showError("Auto Decode failed", throwable);
        }
    }

    private void showAutoDecodeDialog(ByteArray originalBytes, Range range, CodecChain.Result result) {
        JTextArea originalArea = TextContextMenu.area(5, 86, false);
        originalArea.setText(result.original());
        originalArea.setCaretPosition(0);

        JTextArea decodedArea = TextContextMenu.area(8, 86, true);
        decodedArea.setText(result.decoded());
        decodedArea.setCaretPosition(0);

        JTextArea previewArea = TextContextMenu.area(5, 86, false);
        previewArea.setText(result.reencode(decodedArea.getText()));
        previewArea.setCaretPosition(0);

        JLabel chainLabel = new JLabel("Detected chain: " + result.chainDisplay());

        JPanel fields = new JPanel(new GridLayout(3, 1, 0, 7));
        fields.add(frame("Original selected text", new JScrollPane(originalArea)));
        fields.add(frame("Decoded editable text", new JScrollPane(decodedArea)));
        fields.add(frame("Re-encoded preview", new JScrollPane(previewArea)));

        Runnable refreshPreview = () -> {
            previewArea.setText(result.reencode(decodedArea.getText()));
            previewArea.setCaretPosition(0);
        };
        decodedArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { refreshPreview.run(); }
            @Override public void removeUpdate(DocumentEvent e) { refreshPreview.run(); }
            @Override public void changedUpdate(DocumentEvent e) { refreshPreview.run(); }
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton apply = toolbarButton("Apply", "Replace only the original request selection.");
        JButton copyDecoded = toolbarButton("Copy decoded", "Copy the decoded editable text.");
        JButton sendToDecoder = toolbarButton("Send original to Burp Decoder", "Open the original selection in Burp Decoder.");
        JButton cancel = toolbarButton("Cancel", "Close without changing the request.");
        buttons.add(sendToDecoder);
        buttons.add(copyDecoded);
        buttons.add(cancel);
        buttons.add(apply);

        JPanel content = new JPanel(new BorderLayout(6, 6));
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        content.add(chainLabel, BorderLayout.NORTH);
        content.add(fields, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);

        Window owner = SwingUtilities.getWindowAncestor(this);
        JDialog dialog = new JDialog(owner, "Auto Decode", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(apply);

        apply.addActionListener(e -> {
            try {
                applyAutoDecodeReplacement(range, originalBytes, previewArea.getText());
                dialog.dispose();
            } catch (Throwable throwable) {
                showError("Auto Decode apply failed", throwable);
            }
        });
        copyDecoded.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(decodedArea.getText()), null);
            setStatus("Copied decoded selection to clipboard.");
        });
        sendToDecoder.addActionListener(e -> {
            try {
                api.decoder().sendToDecoder(originalBytes);
                setStatus("Sent original selection to Burp Decoder.");
            } catch (Throwable throwable) {
                showError("Send to Burp Decoder failed", throwable);
            }
        });
        cancel.addActionListener(e -> dialog.dispose());

        api.userInterface().applyThemeToComponent(content);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(780, 580));
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private void applyAutoDecodeReplacement(Range range, ByteArray originalBytes, String replacement) {
        String rawRequest = requestText();
        byte[] requestBytes = rawRequest.getBytes(StandardCharsets.UTF_8);
        int start = range.startIndexInclusive();
        int end = range.endIndexExclusive();
        if (start < 0 || end < start || end > requestBytes.length) {
            throw new IllegalStateException("The saved selection no longer matches the request editor contents.");
        }
        byte[] selectedBytes = originalBytes.getBytes();
        if (!Arrays.equals(selectedBytes, Arrays.copyOfRange(requestBytes, start, end))) {
            throw new IllegalStateException("The request changed after Auto Decode opened. Re-select the encoded value and try again.");
        }

        byte[] replacementBytes = Objects.toString(replacement, "").getBytes(StandardCharsets.UTF_8);
        byte[] updated = new byte[start + replacementBytes.length + (requestBytes.length - end)];
        System.arraycopy(requestBytes, 0, updated, 0, start);
        System.arraycopy(replacementBytes, 0, updated, start, replacementBytes.length);
        System.arraycopy(requestBytes, end, updated, start + replacementBytes.length, requestBytes.length - end);

        HttpService service = editorService().orElse(state.currentService());
        setRequestEditorText(new String(updated, StandardCharsets.UTF_8), service);
        setStatus("Auto Decode applied " + resultByteCount(end - start, replacementBytes.length) + " to the request selection.");
        updateContextCounter();
    }

    private static String resultByteCount(int originalBytes, int replacementBytes) {
        return originalBytes + " -> " + replacementBytes + " bytes";
    }

    private void refreshCookiesFromBurp() {
        try {
            String originalRequest = requestText();
            RequestTarget target = RequestTarget.from(originalRequest);
            if (target.host().isBlank()) {
                setStatus("Refresh Cookies failed: request has no Host header.");
                return;
            }
            Set<String> originalNames = requestCookieNames(originalRequest);
            if (originalNames.isEmpty()) {
                setStatus("Refresh Cookies skipped: request has no Cookie header to refresh.");
                return;
            }
            List<Object> cookies = readBurpCookies(target.url());
            Map<String, CookieView> matched = new LinkedHashMap<>();
            for (Object cookie : cookies) {
                CookieView view = CookieView.from(cookie);
                if (view.matches(target) && originalNames.contains(view.name())) putPreferredCookie(matched, view);
            }
            if (matched.isEmpty()) {
                setStatus("Refresh Cookies found no Burp cookie jar values for the request's existing cookies on " + target.host() + target.path() + ".");
                return;
            }
            setRequestEditorText(withRefreshedCookies(originalRequest, cookieValuesInRequestOrder(originalNames, matched)), editorService().orElse(state.currentService()));
            setStatus("Refreshed " + matched.size() + " cookie(s) from Burp cookie jar for " + target.host() + ".");
            updateContextCounter();
        } catch (Throwable throwable) {
            showError("Refresh Cookies failed", throwable);
        }
    }

    private List<Object> readBurpCookies(String url) throws Exception {
        Object http = invokeNoArg(api, "http");
        Object jar = invokeNoArg(http, "cookieJar");
        Object result = null;
        for (String method : List.of("cookies", "getCookies")) {
            result = tryInvokeNoArg(jar, method);
            if (result != null) break;
            result = tryInvokeOneString(jar, method, url);
            if (result != null) break;
        }
        if (result == null) throw new IllegalStateException("Montoya cookie jar did not expose cookies()/getCookies().");
        List<Object> out = new ArrayList<>();
        if (result instanceof Iterable<?> iterable) {
            for (Object item : iterable) out.add(item);
            return out;
        }
        if (result.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(result); i++) out.add(Array.get(result, i));
            return out;
        }
        throw new IllegalStateException("Montoya cookie jar returned unsupported type: " + result.getClass().getName());
    }

    private static void putPreferredCookie(Map<String, CookieView> matched, CookieView candidate) {
        CookieView existing = matched.get(candidate.name());
        if (existing == null || candidate.path().length() > existing.path().length()) {
            matched.put(candidate.name(), candidate);
        }
    }

    private static Map<String, String> cookieValuesInRequestOrder(Set<String> originalNames, Map<String, CookieView> matched) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String name : originalNames) {
            CookieView view = matched.get(name);
            if (view != null) values.put(name, view.value());
        }
        return values;
    }

    private void runChatFromUi() { runAi(false); }
    private void runAnalysisFromUi() { runAi(true); }

    private void runAi(boolean analysis) {
        syncSettingsFromControls();
        syncSnapshotFromEditors(analysis ? "analysis context" : "chat context");
        TrafficSnapshot promptSnapshot = state.current().orElse(null);
        if (looksLikeBurpErrorHtml(requestText())) {
            showError("The current request is Burp's HTML proxy error page. Reload the original request, then run analysis again.", null);
            return;
        }
        if (currentAiThread != null && currentAiThread.isAlive()) {
            setChatStatus("AI is already working. Stop it first.");
            return;
        }
        String userInstruction = promptArea.getText();
        lastMode = analysis ? "Analyze" : "Chat";
        lastRagDump = "";
        updateContextCounter();
        appendChatTurn(userInstruction, analysis);
        startBusyIndicator();
        setStatus(ragCheck.isSelected() ? "Preparing RAG context..." : "Streaming response...");
        setChatStatus(ragCheck.isSelected() ? "Preparing RAG context..." : "Streaming response...");

        Thread worker = new Thread(() -> {
            AtomicBoolean contentStarted = new AtomicBoolean(false);
            StringBuilder assistantBuffer = new StringBuilder();
            try {
                String ragDump = "";
                if (ragCheck.isSelected()) {
                    try {
                        String query = PromptBuilder.ragQuery(state, userInstruction);
                        ragDump = lumaraClient.ragSearch(settings, query, 5, "both");
                    } catch (Throwable throwable) {
                        ragDump = "RAG injection failed: " + Objects.toString(throwable.getMessage(), throwable.getClass().getSimpleName());
                    }
                }

                String finalRagDump = ragDump;
                TextContextMenu.later(() -> {
                    lastRagDump = finalRagDump;
                    updateContextCounter();
                    setStatus("Streaming response...");
                    setChatStatus("Streaming response...");
                });

                String noteContext = notesCheck.isSelected() ? activeNoteContent() : "";
                String prompt = analysis
                        ? PromptBuilder.analysisPrompt(state, promptSnapshot, userInstruction, noteContext, ragDump)
                        : PromptBuilder.chatPrompt(state, promptSnapshot, userInstruction, noteContext, ragDump);
                String system = PromptBuilder.systemPrompt(settings.includeThinking(), analysis);

                lumaraClient.streamChat(settings, system, prompt, contentToken -> TextContextMenu.later(() -> {
                    if (contentStarted.compareAndSet(false, true)) {
                        stopBusyIndicator();
                        setChatStatus("Streaming response...");
                    }
                    assistantBuffer.append(contentToken);
                    transcriptArea.replaceActiveAssistantText(assistantBuffer.toString());
                }));

                TextContextMenu.later(() -> {
                    stopBusyIndicator();
                    if (!contentStarted.get()) {
                        replaceActiveAssistantText("No streamed content was returned by the model.");
                    }
                    state.lastPromptSnapshot(promptSnapshot);
                    setStatus("AI response ready.");
                    setChatStatus("AI response ready.");
                    updateContextCounter();
                });
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                TextContextMenu.later(() -> {
                    stopBusyIndicator();
                    setChatStatus("AI request cancelled.");
                    setStatus("AI request cancelled.");
                });
            } catch (Throwable throwable) {
                TextContextMenu.later(() -> {
                    stopBusyIndicator();
                    setChatStatus("Lumara call failed.");
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

    private void appendChatTurn(String userPrompt, boolean analysis) {
        transcriptArea.startTurn(analysis ? "User Analyze" : "User", Objects.toString(userPrompt, ""), analysis);
    }

    private void replaceActiveAssistantText(String replacement) {
        transcriptArea.replaceActiveAssistantText(Objects.toString(replacement, ""));
    }

    private void clearChatTranscript() {
        transcriptArea.clearTranscript();
        setChatStatus("Chat cleared.");
    }

    private void startBusyIndicator() {
        stopBusyIndicator();
        busyTick = 0;
        busyTimer = new Timer(450, e -> {
            String[] states = {"Working", "Working.", "Working..", "Working..."};
            setChatStatus(states[busyTick++ % states.length]);
        });
        busyTimer.start();
    }

    private void stopBusyIndicator() {
        if (busyTimer != null) {
            busyTimer.stop();
            busyTimer = null;
        }
    }

    private void setChatStatus(String status) { chatStatusLabel.setText(Objects.toString(status, "")); }

    private String activeNoteContent() {
        return activeNoteName.isBlank() ? "" : notesArea.getText();
    }

    private void stopCurrentAiWorker() {
        if (currentAiThread != null && currentAiThread.isAlive()) {
            currentAiThread.interrupt();
            stopBusyIndicator();
            setChatStatus("AI request cancelled.");
            setStatus("AI request cancelled.");
        }
    }

    private void refreshNoteList() {
        String selected = activeNoteName.isBlank() ? selectedComboNoteName() : activeNoteName;
        suppressNoteEvents = true;
        noteSelector.removeAllItems();
        List<String> names = notesStore.listNoteNames();
        for (String name : names) noteSelector.addItem(name);
        suppressNoteEvents = false;
        if (!selected.isBlank() && names.contains(selected)) selectNote(selected);
        else if (!names.isEmpty()) selectNote(names.get(0));
        else noteNameField.setText(activeNoteName.isBlank() ? "DEFAULT" : activeNoteName);
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
        if (!found) noteSelector.addItem(clean);
        noteSelector.setSelectedItem(clean);
        noteSelector.getEditor().setItem(clean);
        noteNameField.setText(clean);
        suppressNoteEvents = false;
    }

    private void createNewNote() {
        flushPendingNoteSave();
        String defaultName = currentNoteName();
        if (defaultName.isBlank()) defaultName = "DEFAULT";
        String entered = JOptionPane.showInputDialog(this, "New note name", defaultName);
        String name = cleanOptionalNoteName(entered);
        if (name.isBlank()) return;
        notesStore.ensureNote(name);
        refreshNoteList();
        selectNote(name);
        activeNoteName = name;
        setNoteText(notesStore.read(name));
        notesArea.setCaretPosition(0);
        setStatus("Created note: " + name);
        updateContextCounter();
    }

    private void loadSelectedNote() {
        flushPendingNoteSave();
        String name = selectedComboNoteName();
        if (name.isBlank()) name = currentNoteName();
        if (name.isBlank()) return;
        notesStore.ensureNote(name);
        selectNote(name);
        activeNoteName = name;
        setNoteText(notesStore.read(name));
        notesArea.setCaretPosition(0);
        setStatus("Loaded note: " + name);
        updateContextCounter();
    }

    private void loadNoteForSnapshot(TrafficSnapshot snapshot) {
        String name = noteNameForSnapshot(snapshot);
        if (name.isBlank()) return;
        name = notesStore.ensureNote(name);
        refreshNoteList();
        selectNote(name);
        activeNoteName = name;
        setNoteText(notesStore.read(name));
        notesArea.setCaretPosition(0);
    }

    private void saveSelectedNote() {
        if (noteSaveTimer != null) noteSaveTimer.stop();
        String targetName = currentNoteName();
        String sourceName = noteSaveSourceName();
        if (targetName.isBlank()) targetName = sourceName.isBlank() ? "DEFAULT" : sourceName;
        try {
            if (!sourceName.isBlank() && !sourceName.equals(targetName) && notesStore.exists(sourceName)) {
                notesStore.write(sourceName, notesArea.getText());
                targetName = notesStore.rename(sourceName, targetName);
                setStatus("Renamed note: " + sourceName + " -> " + targetName);
            } else {
                notesStore.write(targetName, notesArea.getText());
                setStatus("Saved note locally: " + targetName);
            }
            activeNoteName = targetName;
            refreshNoteList();
            selectNote(targetName);
            updateContextCounter();
        } catch (Throwable throwable) {
            showError("Failed to save note", throwable);
        }
    }

    private void setNoteText(String text) {
        loadingNote = true;
        try {
            notesArea.setText(Objects.toString(text, ""));
        } finally {
            loadingNote = false;
        }
    }

    private void flushPendingNoteSave() {
        if (noteSaveTimer != null && noteSaveTimer.isRunning()) {
            noteSaveTimer.stop();
            saveActiveNoteAfterEdit();
        }
    }

    private void saveActiveNoteAfterEdit() {
        String name = noteSaveSourceName();
        if (name.isBlank()) return;
        try {
            notesStore.write(name, notesArea.getText());
            activeNoteName = name;
            setStatus("Saved note locally: " + name);
            updateContextCounter();
        } catch (Throwable throwable) {
            api.logging().logToError("Burp Cockpit failed to save note after edit", throwable);
        }
    }

    private String noteSaveSourceName() {
        if (activeNoteName.isBlank()) return "";
        return NotesStore.sanitizeName(activeNoteName);
    }

    private String currentNoteName() {
        String typedName = cleanOptionalNoteName(noteNameField.getText());
        if (!typedName.isBlank()) return typedName;
        return selectedComboNoteName();
    }

    private String selectedComboNoteName() {
        String selected = cleanOptionalNoteName(noteSelector.getSelectedItem());
        Component editor = noteSelector.getEditor().getEditorComponent();
        if (editor instanceof JTextField textField) {
            String typed = cleanOptionalNoteName(textField.getText());
            if (!typed.isBlank()) {
                String active = noteSaveSourceName();
                if (!selected.isBlank() && typed.equals(active) && !selected.equals(active)) {
                    return selected;
                }
                return typed;
            }
        }
        Object editorItemValue = noteSelector.getEditor().getItem();
        String editorItem = cleanOptionalNoteName(editorItemValue);
        if (!editorItem.isBlank()) return editorItem;
        return selected;
    }

    private static String cleanOptionalNoteName(Object value) {
        String raw = Objects.toString(value, "").trim();
        return raw.isBlank() ? "" : NotesStore.sanitizeName(raw);
    }

    private static String noteNameForSnapshot(TrafficSnapshot snapshot) {
        if (snapshot == null) return "";
        String host = snapshot.service() == null ? "" : snapshot.service().host();
        if (host == null || host.isBlank()) {
            host = HttpText.hostHeader(snapshot.requestText());
        }
        return cleanOptionalNoteName(host);
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
        if (value <= 1100) tokenSelector.setSelectedItem("1k");
        else if (value <= 2200) tokenSelector.setSelectedItem("2k");
        else if (value >= 90000) tokenSelector.setSelectedItem("96k");
        else tokenSelector.setSelectedItem("20k");
    }

    private void updateContextCounter() {
        HttpService service = state.currentService();
        String requestText = requestText();
        if (service == null) service = editorService().orElse(null);
        if (service == null) service = HttpText.inferService(requestText, true).orElse(null);
        TrafficSnapshot snapshot = new TrafficSnapshot(service, requestText, responseText(), Instant.now(), "counter");
        updateTrafficView(snapshot);
        boolean analyze = "Analyze".equals(lastMode);
        int traffic = PromptBuilder.estimatedTokens(analyze
                ? PromptBuilder.buildAnalyzeContext(snapshot, settings.deltaOnly(), state.lastPromptRequest(), state.lastPromptResponse())
                : PromptBuilder.buildChatContext(snapshot, settings.deltaOnly(), state.lastPromptRequest(), state.lastPromptResponse()));
        int notes = notesCheck.isSelected() ? PromptBuilder.estimatedTokens(PromptBuilder.notesContext(activeNoteContent())) : 0;
        int rag = ragCheck.isSelected() ? PromptBuilder.estimatedTokens(lastRagDump) : 0;
        int total = traffic + notes + rag;
        contextLabel.setText("Mode: " + lastMode + " | Traffic " + fmt(traffic) + " | Notes " + (notesCheck.isSelected() ? fmt(notes) : "off") + " | RAG " + (ragCheck.isSelected() ? fmt(rag) : "off") + " | Total " + fmt(total));
    }

    private static String fmt(int tokens) { return String.format("%.1fk", tokens / 1000.0D); }

    private void updateTrafficView(TrafficSnapshot snapshot) {
        String request = snapshot == null ? "" : snapshot.requestText();
        String response = snapshot == null ? "" : snapshot.responseText();
        String methodPath = HttpText.methodAndPath(request);
        String host = snapshot == null || snapshot.service() == null ? HttpText.hostHeader(request) : snapshot.service().host();
        requestSummaryLabel.setText((methodPath.isBlank() ? "No request line" : methodPath)
                + " | Host " + (host == null || host.isBlank() ? "unknown" : host)
                + " | " + byteCount(request));
        responseSummaryLabel.setText(response.isBlank()
                ? "No response loaded."
                : firstLine(response) + " | Body " + byteCount(HttpText.body(response)));
    }

    private static List<String> messageHeaders(String message) {
        String headers = HttpText.headers(message).replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = headers.split("\n", -1);
        List<String> out = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.contains(":")) out.add(line);
        }
        return out;
    }

    private static List<String> requestCookies(String request) {
        List<String> out = new ArrayList<>();
        for (String header : messageHeaders(request)) {
            if (!header.toLowerCase(Locale.ROOT).startsWith("cookie:")) continue;
            String value = header.substring(header.indexOf(':') + 1).trim();
            for (String cookie : value.split(";")) {
                String clean = cookie.trim();
                if (!clean.isBlank()) out.add(clean);
            }
        }
        return out;
    }

    private static String firstLine(String message) {
        String normalized = Objects.toString(message, "").replace("\r\n", "\n").replace('\r', '\n');
        int newline = normalized.indexOf('\n');
        return (newline < 0 ? normalized : normalized.substring(0, newline)).trim();
    }

    private static String byteCount(String value) {
        return Objects.toString(value, "").getBytes(StandardCharsets.UTF_8).length + " bytes";
    }

    private static Color mutedBorderColor() {
        return new Color(82, 86, 92);
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
        String raw = requestText();
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

    private static String shell(String value) { return "'" + Objects.toString(value, "").replace("'", "'\\''") + "'"; }

    private static String withRefreshedCookies(String rawRequest, Map<String, String> refreshed) {
        String raw = Objects.toString(rawRequest, "");
        String newline = raw.contains("\r\n") ? "\r\n" : "\n";
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');
        int split = normalized.indexOf("\n\n");
        String headers = split >= 0 ? normalized.substring(0, split) : normalized;
        String body = split >= 0 ? normalized.substring(split + 2) : "";
        String[] lines = headers.split("\n", -1);
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            if (line.toLowerCase(Locale.ROOT).startsWith("cookie:")) {
                out.add("Cookie: " + refreshedCookieHeader(line.substring(line.indexOf(':') + 1), refreshed));
                continue;
            }
            out.add(line);
        }
        return String.join(newline, out) + newline + newline + body.replace("\n", newline);
    }

    private static String refreshedCookieHeader(String cookieHeader, Map<String, String> refreshed) {
        List<String> out = new ArrayList<>();
        for (String cookie : Objects.toString(cookieHeader, "").split(";")) {
            String clean = cookie.trim();
            if (clean.isBlank()) continue;
            int equals = clean.indexOf('=');
            String name = equals < 0 ? clean : clean.substring(0, equals).trim();
            String value = equals < 0 ? "" : clean.substring(equals + 1).trim();
            if (refreshed.containsKey(name)) out.add(name + "=" + refreshed.get(name));
            else out.add(equals < 0 ? name : name + "=" + value);
        }
        return String.join("; ", out);
    }

    private static Set<String> requestCookieNames(String request) {
        Set<String> out = new LinkedHashSet<>();
        for (String cookie : requestCookies(request)) {
            int equals = cookie.indexOf('=');
            String name = equals < 0 ? cookie : cookie.substring(0, equals);
            name = name.trim();
            if (!name.isBlank()) out.add(name);
        }
        return out;
    }

    private record RequestTarget(String scheme, String host, String path) {
        String url() { return scheme + "://" + host + path; }

        static RequestTarget from(String rawRequest) {
            String text = Objects.toString(rawRequest, "").replace("\r\n", "\n").replace('\r', '\n');
            String[] lines = text.split("\n");
            String first = lines.length > 0 ? lines[0] : "";
            String host = "";
            String path = "/";
            String scheme = "https";
            for (String line : lines) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.startsWith("host:")) host = line.substring(line.indexOf(':') + 1).trim();
                else if (lower.startsWith("origin: http://") || lower.startsWith("referer: http://")) scheme = "http";
            }
            String[] parts = first.split("\\s+");
            if (parts.length > 1) {
                path = parts[1].trim();
                if (path.startsWith("http://") || path.startsWith("https://")) {
                    try {
                        URI uri = URI.create(path);
                        scheme = uri.getScheme() == null ? scheme : uri.getScheme();
                        host = uri.getHost() == null ? host : uri.getHost();
                        if (uri.getPort() > 0) host = host + ":" + uri.getPort();
                        path = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath();
                        if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
                    } catch (Throwable ignored) {
                        path = "/";
                    }
                }
            }
            return new RequestTarget(scheme, host, path.isBlank() ? "/" : path);
        }
    }

    private record CookieView(String name, String value, String domain, String path, boolean secure) {
        boolean matches(RequestTarget target) {
            if (name.isBlank()) return false;
            String targetHost = stripPort(target.host()).toLowerCase(Locale.ROOT);
            String cookieDomain = domain.toLowerCase(Locale.ROOT);
            if (cookieDomain.startsWith(".")) cookieDomain = cookieDomain.substring(1);
            boolean domainOk = cookieDomain.isBlank() || targetHost.equals(cookieDomain) || targetHost.endsWith("." + cookieDomain);
            boolean pathOk = pathMatches(target.path(), path);
            boolean secureOk = !secure || "https".equalsIgnoreCase(target.scheme());
            return domainOk && pathOk && secureOk;
        }

        private static boolean pathMatches(String targetPath, String cookiePath) {
            String target = Objects.toString(targetPath, "/");
            int query = target.indexOf('?');
            if (query >= 0) target = target.substring(0, query);
            if (target.isBlank()) target = "/";
            String cookie = Objects.toString(cookiePath, "");
            if (cookie.isBlank()) return true;
            if (target.equals(cookie)) return true;
            if (!target.startsWith(cookie)) return false;
            return cookie.endsWith("/") || (target.length() > cookie.length() && target.charAt(cookie.length()) == '/');
        }

        static CookieView from(Object cookie) {
            return new CookieView(
                    str(cookie, "name", "getName"),
                    str(cookie, "value", "getValue"),
                    str(cookie, "domain", "getDomain"),
                    str(cookie, "path", "getPath"),
                    bool(cookie, "secure", "isSecure", "getSecure")
            );
        }

        private static String stripPort(String host) {
            String value = Objects.toString(host, "").trim();
            int colon = value.lastIndexOf(':');
            return colon > 0 && value.indexOf(']') < colon ? value.substring(0, colon) : value;
        }

        private static String str(Object target, String... names) {
            for (String name : names) {
                try { return Objects.toString(invokeNoArg(target, name), ""); }
                catch (Throwable ignored) { }
            }
            return "";
        }

        private static boolean bool(Object target, String... names) {
            for (String name : names) {
                try {
                    Object value = invokeNoArg(target, name);
                    return value instanceof Boolean b ? b : Boolean.parseBoolean(Objects.toString(value, "false"));
                } catch (Throwable ignored) { }
            }
            return false;
        }
    }

    private static Object invokeNoArg(Object target, String name) throws Exception {
        Method method = target.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static Object tryInvokeNoArg(Object target, String name) {
        try { return invokeNoArg(target, name); }
        catch (Throwable ignored) { return null; }
    }

    private static Object tryInvokeOneString(Object target, String name, String arg) {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != 1 || !method.getParameterTypes()[0].equals(String.class)) continue;
            try {
                method.setAccessible(true);
                return method.invoke(target, arg);
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
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
