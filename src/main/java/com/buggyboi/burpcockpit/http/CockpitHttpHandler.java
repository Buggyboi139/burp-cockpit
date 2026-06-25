package com.buggyboi.burpcockpit.http;

import burp.api.montoya.http.handler.HttpHandler;
import burp.api.montoya.http.handler.HttpRequestToBeSent;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.handler.RequestToBeSentAction;
import burp.api.montoya.http.handler.ResponseReceivedAction;
import com.buggyboi.burpcockpit.ui.CockpitPanel;

public final class CockpitHttpHandler implements HttpHandler {
    private final CockpitPanel panel;

    public CockpitHttpHandler(CockpitPanel panel) {
        this.panel = panel;
    }

    @Override
    public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent requestToBeSent) {
        panel.considerAutoCapture(requestToBeSent, null, "auto request");
        return RequestToBeSentAction.continueWith(requestToBeSent);
    }

    @Override
    public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived responseReceived) {
        panel.considerAutoCapture(responseReceived.initiatingRequest(), responseReceived, "auto response");
        return ResponseReceivedAction.continueWith(responseReceived);
    }
}
