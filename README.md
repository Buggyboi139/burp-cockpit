# Burp Cockpit

Burp Cockpit is a Burp Suite Community/Professional Montoya extension that ports the useful parts of ZAP Cockpit into Burp without dragging ZAP internals behind it like a corpse on roller skates.

## What it does

- Adds a `Burp Cockpit` suite tab.
- Adds context menu actions from Burp Proxy, Repeater, Logger, Organizer, and message editors.
- Sends selected request/response pairs into Cockpit.
- Lets you edit and resend HTTP requests through Burp's own HTTP stack.
- Keeps request iteration history with back/forward controls.
- Provides Analysis, Chat, AI Edit Draft, RAG Search, Payload Ideas, and Notes panels.
- Stores notes locally by host/domain and supports pinned note auto-injection into prompts.
- Supports configurable OpenAI-compatible local chat endpoint, RAG search endpoint, and payload endpoint.
- Includes right-click copy/cut/paste/select-all menus on Cockpit text controls.

## Build

Requires Java 21 and Gradle.

```bash
gradle clean jar
```

The JAR is written to:

```text
build/libs/burp-cockpit-0.1.0.jar
```

GitHub Actions also builds the JAR on push and exposes it as the `burp-cockpit-jar` artifact.

## Load in Burp

1. Open Burp.
2. Go to `Extensions` → `Installed` → `Add`.
3. Extension type: `Java`.
4. Select `build/libs/burp-cockpit-0.1.0.jar`.
5. Extension class name: `Extension` if Burp asks.

## Default endpoints

Chat endpoint:

```text
http://127.0.0.1:8080/v1/chat/completions
```

RAG search endpoint:

```text
http://127.0.0.1:8765/rag/search
```

Payload ideas endpoint:

```text
http://127.0.0.1:8765/rag/payload-ideas
```

Change these in the Cockpit `Settings` tab.

## Expected chat backend

The chat client speaks OpenAI-compatible `/v1/chat/completions` JSON and supports streaming SSE responses.

The extension expects responses like llama.cpp/OpenAI-compatible servers usually emit:

```json
{"choices":[{"delta":{"content":"text"}}]}
```

It also handles non-streaming `message.content` responses.

## RAG endpoint payloads

RAG search POST body:

```json
{"query":"...","limit":8}
```

Payload ideas POST body:

```json
{"request":"...","response":"...","limit":25}
```

The extension displays the raw response if your backend uses a custom schema. Humans invented too many JSON shapes, so raw fallback is deliberate.

## Notes

Default notes directory:

```text
~/.burp-cockpit/notes
```

Notes are Markdown files. The extension creates a default note per current host, for example:

```text
google.com.md
calendar.google.com.md
```

Pinned notes are injected into AI prompts when `Inject pinned note` is enabled.

## Current limitations

- This is a Burp extension, not a scanner.
- It does not require Burp Pro features.
- HTTP/2 raw editing works through Burp's abstractions, but the editor intentionally treats the draft as HTTP text.
- RAG/notes endpoints are configurable because your OpenLumara module names and ports may change, because of course they will.
