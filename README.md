# Burp Cockpit

Burp Cockpit is a Burp Suite Community/Professional Montoya extension that ports the current Cockpit workflow into Burp without dragging along the old ZAP-specific add-on skeleton or the earlier button-farm experiments.

## What it does

- Adds a `Burp Cockpit` suite tab.
- Adds right-click context menu actions from Burp HTTP message views.
- Opens selected request/response pairs in Cockpit.
- Lets you edit and resend HTTP requests through Burp's own HTTP stack.
- Keeps bounded request iteration history with back/forward controls.
- Adds `Clear Cache` to collapse history back to the current visible request/response and clear RAG/delta context while preserving the active local note.
- Provides the current Cockpit layout: request editor, response viewer, right-side `Analysis` and `Notes` tabs.
- Streams final AI responses live into a card-rendered transcript.
- Uses the `Thinking` checkbox only as a reasoning-behavior toggle.
- Sends explicit `/think` or `/no_think` control hints with each AI prompt.
- Shows a temporary flashing `Working...` indicator in the chat box while waiting for final content.
- Does not display, store, append, or re-inject reasoning text.
- Supports scoped RAG injection only when the `RAG` toggle is enabled.
- Keeps notes local under `~/.burp-cockpit/notes/`.
- Auto-loads or creates the current host note when a request is opened.
- Includes the active local note in AI context only when the `Notes` toggle is enabled.
- Treats notes and RAG as read-only AI context.
- Never lets model output write to disk. Only the user-facing Notes tab writes notes.
- Keeps the target label out of the toolbar so long paths stay in the request editor where they belong.
- Keeps the token selector fixed-width so it does not eat the toolbar like a deranged UI parasite.
- Includes export helpers for curl and Python.
- Includes right-click copy/cut/paste/select-all menus on Cockpit text controls.

## Current UI

Toolbar:

```text
[New] [←] [→] [Send] [Refresh Cookies] [Clear Cache] [Export curl] [Export Python] [Hide Right Pane] [Settings]
Tokens [1k|2k|20k|96k] [Thinking] [Delta only] [RAG]
```

Analysis tab:

```text
Mode/context badge
HTML card transcript
Prompt box
[Notes] [RAG] [Send Chat] [Analyze] [Stop] [Clear Chat]
```

Notes tab:

```text
Editable note dropdown
[Load] [New Note]
Editable note name field
[Save] [Refresh]
Local Markdown note editor
```

## V1.5 context behavior

Chat and Analyze intentionally use different prompt builders. This is the entire point, because apparently if you let a model eat every byte forever it turns into a fog machine with syntax highlighting.

Chat mode:

```text
Target host
Request method/path
Essential headers only
Response status/first line
First 500 chars of response body
Optional read-only notes
Optional read-only RAG
```

Analyze mode:

```text
Full capped request context
Full capped response context
Optional request delta
Optional read-only notes
Optional read-only RAG
Structured security-audit output contract
```

## Context caps

The editor panes keep the full visible request/response, but the AI context and counter use capped prompt material:

```text
Analyze request:  first ~4k tokens + last ~4k tokens
Analyze response: first ~4k tokens + last ~4k tokens
Analyze notes:    first ~10k tokens, only when Notes is enabled
Analyze RAG:      first ~4k tokens + last ~4k tokens, only when RAG is enabled
Chat response:    first 500 chars of response body
Chat notes:       first ~1.2k tokens, only when Notes is enabled
Chat RAG:         first ~1.4k tokens, only when RAG is enabled
```

## What `Clear Cache` does

`Clear Cache` does not delete notes or clear the visible request/response. It:

- saves the current local note from the Notes tab
- keeps the current visible request and response
- keeps the active note selected
- clears the last RAG dump
- resets the delta baseline
- collapses request history to one current exchange
- recalculates context from current request, response, and opt-in notes/RAG only

## What it intentionally does not include

The older standalone controls are gone:

- No `Import Raw` button.
- No toolbar-level `Analyze` button.
- No `Stream` checkbox. Streaming is always on.
- No forced `Notes` context. Notes are opt-in for AI prompts.
- No model note append channel.
- No AI-generated writes into local notes.
- No toolbar target label.
- No dedicated `Thinking preview` box.
- No displayed reasoning text.
- No `Host Note` button.
- No `Pin` button.
- No `Ingest Notes` button.
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
2. Go to `Extensions` -> `Installed` -> `Add`.
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
http://10.0.2.2:5000/rag/search
```

Change these with the Cockpit `Settings` button if your model/RAG server lives somewhere else.

## Expected chat backend

The chat client speaks OpenAI-compatible `/v1/chat/completions` JSON and expects streaming SSE responses.

The extension forces direct HTTP/1.1 and disables JVM proxy selection for Lumara calls so the AI request does not get accidentally sent into Burp's proxy listener.

Expected streaming final content shape:

```json
{"choices":[{"delta":{"content":"text"}}]}
```

Reasoning output, if your backend emits it, is intentionally ignored by the UI. The `Thinking` checkbox only changes the reasoning instruction sent to the model by adding `/think` or `/no_think` at the start of the prompt.

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

RAG is read-only from Burp Cockpit. Burp notes are not saved into RAG and are not ingested into OpenLumara knowledge.

## Notes

Default notes directory:

```text
~/.burp-cockpit/notes
```

Notes are Markdown files. The extension creates a default note per current host, for example:

```text
google.com.md
```
