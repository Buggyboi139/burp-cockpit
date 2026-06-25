package com.buggyboi.burpcockpit;

import burp.api.montoya.MontoyaApi;
import com.buggyboi.burpcockpit.http.CockpitHttpHandler;
import com.buggyboi.burpcockpit.lumara.LumaraClient;
import com.buggyboi.burpcockpit.notes.NotesStore;
import com.buggyboi.burpcockpit.state.CockpitSettings;
import com.buggyboi.burpcockpit.state.CockpitState;
import com.buggyboi.burpcockpit.ui.CockpitContextMenuProvider;
import com.buggyboi.burpcockpit.ui.CockpitPanel;

public final class BurpCockpitApp {
    private final MontoyaApi api;

    public BurpCockpitApp(MontoyaApi api) {
        this.api = api;
    }

    public void initialize() {
        api.extension().setName("Burp Cockpit");

        CockpitSettings settings = new CockpitSettings();
        NotesStore notesStore = new NotesStore(settings.notesDirectory());
        CockpitState state = new CockpitState(api, settings, notesStore);
        LumaraClient lumaraClient = new LumaraClient();
        CockpitPanel panel = new CockpitPanel(api, state, lumaraClient);

        api.userInterface().registerSuiteTab("Burp Cockpit", panel);
        api.userInterface().registerContextMenuItemsProvider(new CockpitContextMenuProvider(api, panel));
        api.http().registerHttpHandler(new CockpitHttpHandler(panel));

        api.logging().logToOutput("Burp Cockpit loaded. Local Lumara endpoint: " + settings.chatEndpoint());
    }
}
