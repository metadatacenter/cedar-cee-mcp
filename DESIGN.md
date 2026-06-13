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
(`cedar-embeddable-editor.min.js`), pinned by version and loaded from the CDN. There is no
frontend build step, no npm, no Angular toolchain in this repo — and no configuration: the
server's only knobs are the version string in the host page and constants in the code. The host page is hand-written static HTML small enough to read in
one sitting. Upgrading the CEE means changing one version string in the host page after a manual
browser check.

## Principle 4 — The return path is a tool result, blocking with an escape hatch

The one genuine impedance mismatch: the LLM only learns things through tool results, but the
human finishes the form on their own clock. `fill_instance` therefore blocks on the submission
future with a bounded timeout — the magical path when the user is quick — and on timeout returns
the session id with the form left open, degrading to the robust two-step path
(`collect_instance` when the user says they're done). Both paths share all plumbing; neither is
privileged. A later Done press replaces an earlier submission (people fix mistakes); collection
always returns the latest.

## Principle 5 — JSON in, JSON out; artifact translation lives elsewhere

The CEE natively consumes canonical CEDAR JSON (JSON Schema templates, JSON-LD instances) and
produces JSON-LD. This server hands that JSON through **byte-for-byte in both directions** and
never parses, converts, validates, or otherwise interprets artifact content — it deliberately has
no dependency on `cedar-artifact-library`. YAML ↔ JSON translation is `cedar-artifact-mcp`'s job
(`template_to_json`, `instance_to_json`, `instance_to_yaml`); YAML handed to a tool here is
rejected with a redirect to those tools, not converted. If a conversion concern ever seems to
belong here, it belongs there.

## Principle 6 — Never lose the human's input

The instance coming back from the CEE is returned exactly as the editor produced it — untouched
JSON-LD. Because nothing is converted, nothing can fail in a way that discards something a person
just spent minutes typing.

## Principle 7 — Errors are content

Tool failures (unreadable artifact, unknown session, read-only session) come back as
`isError=true` results with a message the LLM can act on — not protocol errors. The web surface
mirrors this: the host page surfaces bundle-load and submit failures as visible page status, so
the human sees what went wrong without opening a console.

## Note — pre-filling needs a complete instance

The CEE lives in CEDAR's all-fields-present JSON world. Pre-filling `fill_instance` with a sparse
instance will not render; `cedar-artifact-mcp`'s `instance_to_json`, given the template, produces
exactly the complete JSON-LD form the editor needs.

## Note — what the terminology URL buys

Controlled-term autocomplete inside the form calls CEDAR's public terminology proxy
(`terminologyIntegratedSearchUrl` in the CEE config) straight from the user's browser. No key is
handled by this MCP. Without network access to that endpoint the form still renders; only
autocomplete suggestions are lost.
