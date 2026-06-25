# Burp Cockpit

Burp Cockpit is a Burp Suite Community/Professional Montoya extension that ports the current Cockpit workflow into Burp without dragging along the old ZAP-specific add-on skeleton or the earlier button-farm experiments.

## What it does

- Adds a `Burp Cockpit` suite tab.
- Adds right-click context menu actions from Burp HTTP message views.
- Opens selected request/response pairs in Cockpit.
- Lets you edit and resend HTTP requests through Burp's own HTTP stack.
- Keeps bounded request iteration history with back/forward controls.
- Adds `Clear Cache` to collapse history back to the current visible request/response, clear RAG/delta context, and keep the active local note.
- Provides the current Cockpit layout: request editor, response viewer, right-side `Analysis` and `Notes` tabs.
- Streams final AI responses live into the chat transcript area.
- Uses the `Thinking` checkbox only as a reasoning-behavior toggle.
- Sends explicit `/think` or `/no_think` control hints with each AI prompt.
- Shows a temporary flashing `Working...` indicator in the chat box while waiting for final content.
- Does not display, store, append, or re-inject reasoning text.
- Supports automatic scoped RAG injection when the `RAG` toggle is enabled.
- Keeps notes local to Kali under `~/.burp-cockpit/notes/`.
- Auto-loads or creates the current host note when a request is opened.
- Always includes the active local note in AI context with a flat first-10k-token cap.
- Gives the model a local note append channel using `[[COCKPIT_NOTE]] ... [[/COCKPIT_NOTE]]` blocks.
- Strips note blocks out of visible chat and appends their contents to the active local note.
- Keeps the target label out of the toolbar so long paths stay in the request editor where they belong.
- Keeps the token selector fixed-width so it does not eat the toolbar like a deranged UI parasite.
- Includes export helpers for curl and Python.
- Includes right-click copy/cut/paste/select-all menus on Cockpit text controls.

## Current UI

Toolbar:

```text
[New] [←] [→] [Send] [Clear Cache] [Export curl] [Export Python] [Hide Right Pane] [Settings]
Tokens [1k|2k|20k|96k] [Thinking] [Delta only] [RAG]
```

Analysis tab:

```text
Prompt box
[Send Chat] [Analyze] [Stop]
Context counter based on capped prompt context
Single chat transcript with flashing Working... indicator while waiting
Streaming final response transcript
```

Notes tab:

```text
Editable note dropdown
[Load] [New Note]
Editable note name field
[Save] [Refresh]
Local Markdown note editor
```

## Model note append channel

The prompt sent to the model includes a local note append channel:

```text
[[COCKPIT_NOTE]]
Markdown to append. Keep it concise. Do not include full chat history.
[[/COCKPIT_NOTE]]
```

When the model emits one or more of those blocks, Cockpit removes the block from the visible chat transcript and appends the contents to the bottom of the active local note under a timestamped `Model note` heading. Notes stay local in Kali. This is deliberately boring because boring usually survives contact with Java desktop software.

## Context caps

The editor panes keep the full visible request/response, but the AI context and counter use capped prompt material:

```text
Request:  first ~4k tokens + last ~4k tokens
Response: first ~4k tokens + last ~4k tokens
Notes:    first ~10k tokens
RAG:      first ~4k tokens + last ~4k tokens
```

This keeps the model from being force-fed some surprise mega-body just because a web app coughed up a weird response. Humanity invented limits because apparently it had to.

## What `Clear Cache` does

`Clear Cache` does not delete notes or clear the visible request/response. It:

- saves the current local note
- keeps the current visible request and response
- keeps the active note selected
- clears the last RAG dump
- resets the delta baseline
- collapses request history to one current exchange
- recalculates context from current request, response, and note only

## What it intentionally does not include

The older standalone controls are gone:

- No `Import Raw` button.
- No toolbar-level `Analyze` button.
- No `Stream` checkbox. Streaming is always on.
- No `Notes` checkbox. Notes always happen.
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
calendar.google.com.md
script.google.com.md
```

The note dropdown and name field are editable. Typing a new note name and pressing `Save` renames the active local Markdown note. `New Note` creates a separate note.

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
