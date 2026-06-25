package com.buggyboi.burpcockpit.state;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.HttpService;
import com.buggyboi.burpcockpit.notes.NotesStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class CockpitState {
    private final MontoyaApi api;
    private final CockpitSettings settings;
    private final NotesStore notesStore;
    private final List<TrafficSnapshot> history = new ArrayList<>();
    private TrafficSnapshot current;
    private int historyIndex = -1;
    private String lastPromptRequest = "";
    private String pinnedNoteName = "";

    public CockpitState(MontoyaApi api, CockpitSettings settings, NotesStore notesStore) {
        this.api = api;
        this.settings = settings;
        this.notesStore = notesStore;
    }

    public MontoyaApi api() { return api; }
    public CockpitSettings settings() { return settings; }
    public NotesStore notesStore() { return notesStore; }

    public synchronized void pushSnapshot(TrafficSnapshot snapshot) {
        if (snapshot == null) return;
        while (history.size() > historyIndex + 1) history.remove(history.size() - 1);
        history.add(snapshot);
        historyIndex = history.size() - 1;
        current = snapshot;
    }

    public synchronized Optional<TrafficSnapshot> current() { return Optional.ofNullable(current); }

    public synchronized Optional<TrafficSnapshot> previous() {
        if (historyIndex <= 0) return Optional.empty();
        historyIndex--;
        current = history.get(historyIndex);
        return Optional.of(current);
    }

    public synchronized Optional<TrafficSnapshot> next() {
        if (historyIndex < 0 || historyIndex >= history.size() - 1) return Optional.empty();
        historyIndex++;
        current = history.get(historyIndex);
        return Optional.of(current);
    }

    public synchronized String historyLabel() { return historyIndex < 0 ? "0/0" : (historyIndex + 1) + "/" + history.size(); }
    public synchronized HttpService currentService() { return current == null ? null : current.service(); }
    public synchronized String currentRequest() { return current == null ? "" : current.requestText(); }
    public synchronized String currentResponse() { return current == null ? "" : current.responseText(); }
    public synchronized String lastPromptRequest() { return lastPromptRequest; }
    public synchronized void lastPromptRequest(String value) { lastPromptRequest = value == null ? "" : value; }
    public synchronized String pinnedNoteName() { return pinnedNoteName; }
    public synchronized void pinnedNoteName(String value) { pinnedNoteName = value == null ? "" : value; }
}
