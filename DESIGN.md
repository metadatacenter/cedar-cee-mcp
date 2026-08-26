# Design

Principles governing `cedar-cee-mcp`. Read before adding a tool or changing the web surface.

## Principle 1 — Display and population only

This MCP does exactly two things: render CEDAR artifacts for a human (read-only), and let a human
populate a template instance through a real form. It does not author templates (that's
`cedar-artifact-mcp`), persist anything (that's `cedar-artifact-rest-mcp`), or validate (both siblings do).
The three compose: author → show/fill → validate → persist.

## Principle 2 — A headless server conjures a browser

MCP is a stdio tool protocol with no display. The display surface is a loopback-only HTTP server
on an ephemeral port, serving one static host page that loads the CEE web component and drives it
with per-session data. Tools create sessions and (best-effort) open the user's browser; every
tool result carries the URL so a failed auto-open degrades to "click this".

The security story is deliberately minimal and must stay honest about it: loopback bind +
unguessable UUID session ids + in-memory sessions that die with the server. That is local,
single-user scope. Anything that would make this a deployable service (auth, TLS, session
persistence, non-loopback binds) is out of scope — see ROADMAP.md.

## Principle 3 — The CEE is a prebuilt, pinned dependency

The CEE is consumed as the single self-contained web-component bundle its npm package publishes
(`cedar-embeddable-editor.js`), pinned by version and served by this server out of its own jar.
There is no frontend build step, no npm, no Angular toolchain in this repo. The build fetches the
package from the BMIR Nexus, where the CEE publishes under the `@org.metadatacenter` scope, and
verifies the bundle against the hash the pin names — a dev version label can be republished, so the
version alone does not identify the bytes.

Serving it locally is not a preference. No public CDN carries a package published to a private
registry, so there is nothing for the page to link to. It also means a session needs no network but
the terminology and bridge servers the fields themselves call.

Upgrading the CEE is two lines in the pom — version and hash — plus a browser check, and the
configuration the host page sends has to be checked against the release's own surface: CEE reads a
fixed set of keys and ignores the rest, reporting them to a console a host does not watch. The unit
suite asserts every key this server sends is one CEE reads.

## Principle 4 — The return path is a tool result, blocking with an escape hatch

The one genuine impedance mismatch: the LLM only learns things through tool results, but the
human finishes the form on their own clock. `fill_instance` therefore blocks on the submission
future with a bounded timeout — the magical path when the user is quick — and on timeout returns
the session id with the form left open, degrading to the robust two-step path
(`collect_instance` when the user says they're done). Both paths share all plumbing; neither is
privileged. A later Done press replaces an earlier submission (people fix mistakes); collection
always returns the latest.

## Principle 5 — YAML-first at the tool boundary, JSON at the CEE boundary

The tool surface accepts CEDAR artifacts as compact YAML (the preferred LLM-facing form) or as
CEDAR JSON. Before a session is created, `Json.toObject` passes YAML through
`cedar-artifact-library`'s compact `YamlArtifactReader` and `JsonArtifactRenderer`; JSON input is
parsed directly. The library is therefore a load-bearing runtime dependency, pinned by
`cedar-artifact-library.version` in `pom.xml`. The CEE itself always receives the resulting CEDAR
JSON object.

The populated instance travels in the other direction as JSON-LD exactly as the CEE submitted it.
This server does not translate that human-authored output; callers that want compact YAML use
`cedar-artifact-mcp`'s `render_instance_artifact` with `format: yaml` after collection.

The host configuration must also use the CEE 2.0 vocabulary exactly. Every session sends
`showDownloadMenu`, `defaultLanguage`, `fallbackLanguage`, `terminologyBaseUrl`, and
`bridgeBaseUrl`; read-only sessions additionally send `readOnlyMode`. These names are checked
against the CEE's declared configuration surface by the unit suite.

## Principle 6 — Never lose the human's input

The instance coming back from the CEE is returned exactly as the editor produced it — untouched
JSON-LD. Because nothing is converted, nothing can fail in a way that discards something a person
just spent minutes typing.

## Principle 7 — Errors are content

Tool failures (unreadable artifact, unknown session, read-only session) come back as
`isError=true` results with a message the LLM can act on — not protocol errors. The web surface
mirrors this — the host page surfaces bundle-load and submit failures as visible page status, so
the human sees what went wrong without opening a console.

## Note — pre-filling needs a complete instance

The CEE lives in CEDAR's all-fields-present JSON world. Pre-filling `fill_instance` with a sparse
instance will not render; `cedar-artifact-mcp`'s `render_instance_artifact` with `format: json`,
given the template, produces exactly the complete JSON-LD form the editor needs.

## Note — what the terminology URL buys

Controlled-term autocomplete inside the form calls CEDAR's public terminology proxy
(`terminologyBaseUrl` in the CEE config) straight from the user's browser. No key is
handled by this MCP. Without network access to that endpoint the form still renders; only
autocomplete suggestions are lost.
