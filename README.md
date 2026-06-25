# Burp Cockpit

Burp Cockpit is a Burp Suite Community/Professional Montoya extension that ports the current ZAP Cockpit workflow into Burp without keeping the old ZAP-specific add-on skeleton around like a haunted basement appliance.

## What it does

- Adds a `Burp Cockpit` suite tab.
- Adds right-click context menu actions from Burp HTTP message views.
- Opens selected request/response pairs in Cockpit.
- Lets you edit and resend HTTP requests through Burp's own HTTP stack.
- Keeps bounded request iteration history with back/forward controls.
- Provides the current Cockpit layout: request editor, response viewer, right-side `Analysis` and `Notes` tabs.
- Supports streaming local AI chat and analysis against the current request/response exchange.
- Supports automatic scoped RAG injection when the `RAG` toggle is enabled.
- Supports local notes per host/domain, pinned-note context injection, and saving notes into scoped Lumara RAG.
- Includes export helpers for curl and Python.
- Includes right-click copy/cut/paste/select-all menus on Cockpit text controls.

## What it intentionally does not include

The older standalone buttons are gone:

- No standalone `Search RAG` button.
- No standalone `Payload Ideas` button.
- No AI draft/edit workflow separate from the main request editor.
- No scanner, OAST workflow, fuzzing loop, listener, callback server, or automated background attack nonsense.

Manual request sending only happens when you click `Send`.

## Build

Requires Java 21 and Gradle.

```bash
gradle clean jar
```

The JAR is written to:

```text
build/libs/burp-cockpit-0.1.0.jar
```

GitHub Actions builds the JAR on push and exposes it as the `burp-cockpit-jar` artifact.

## Load in Burp

1. Open Burp.
2. Go to `Extensions` → `Installed` → `Add`.
3. Extension type: `Java`.
4. Select `build/libs/burp-cockpit-0.1.0.jar`.
5. Extension class name: `Extension` if Burp asks.

## Default endpoints

For the Kali VM + host OpenLumara/llama.cpp setup, the defaults use VirtualBox NAT host access instead of `127.0.0.1`, because `127.0.0.1:8080` inside the VM is usually Burp itself. That was the source of the Burp HTML error page loop, a tiny ouroboros of bad assumptions.

Chat endpoint:

```text
http://10.0.2.2:8080/v1/chat/completions
```

RAG search endpoint:

```text
http://10.0.2.2:8765/rag/search
```

Change these with the Cockpit `Settings` button if your model/RAG server lives somewhere else.

## Expected chat backend

The chat client speaks OpenAI-compatible `/v1/chat/completions` JSON and supports streaming SSE responses.

The extension forces direct HTTP/1.1 and disables JVM proxy selection for Lumara calls so the AI request does not get accidentally sent into Burp's proxy listener.

Expected streaming response shape:

```json
{"choices":[{"delta":{"content":"text"}}]}
```

It also handles non-streaming `message.content` responses.

## RAG endpoint payload

RAG search POST body:

```json
{
  "query": "...",
  "q": "...",
  "n_results": 8,
  "nResults": 8,
  "scope": "both"
}
```

Notes save payloads use `scope: notes` and include `target_uri`, `note_path`, and `ingest: true`.

## Notes

Default notes directory:

```text
~/.burp-cockpit/notes
```

Notes are Markdown files. The extension creates a default note per current host, for example:

```text
google.com.md
calendar.google.com.md
script.google.com.md
```

Pinned notes are injected into AI prompts when `Notes` is enabled. Scoped RAG context is injected when `RAG` is enabled.

## Common failure: Burp HTML error from AI call

If you see:

```text
Invalid client request received: First line of request did not contain an absolute URL
```

then the chat endpoint is pointed at Burp's proxy listener. In the Kali VM, use:

```text
http://10.0.2.2:8080/v1/chat/completions
```

Or move Burp's proxy listener off port 8080 and use your real local endpoint. Software: still somehow mostly plumbing.
