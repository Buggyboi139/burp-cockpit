package com.buggyboi.burpcockpit.state;

import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;

import java.time.Instant;
import java.util.Objects;

public final class TrafficSnapshot {
    private final HttpService service;
    private final String requestText;
    private final String responseText;
    private final Instant capturedAt;
    private final String source;

    public TrafficSnapshot(HttpService service, String requestText, String responseText, Instant capturedAt, String source) {
        this.service = service;
        this.requestText = Objects.toString(requestText, "");
        this.responseText = Objects.toString(responseText, "");
        this.capturedAt = capturedAt == null ? Instant.now() : capturedAt;
        this.source = Objects.toString(source, "manual");
    }

    public static TrafficSnapshot from(HttpRequestResponse pair, String source) {
        HttpRequest request = pair == null ? null : pair.request();
        HttpResponse response = pair == null || !pair.hasResponse() ? null : pair.response();
        HttpService service = pair == null ? null : pair.httpService();
        return new TrafficSnapshot(service, request == null ? "" : request.toString(), response == null ? "" : response.toString(), Instant.now(), source);
    }

    public HttpService service() { return service; }
    public String requestText() { return requestText; }
    public String responseText() { return responseText; }
    public Instant capturedAt() { return capturedAt; }
    public String source() { return source; }

    public String hostLabel() {
        if (service == null || service.host() == null || service.host().isBlank()) return "DEFAULT";
        return service.host();
    }
}
