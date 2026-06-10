# cedar-cee-mcp

[CEDAR](https://metadatacenter.org/) — the Center for Expanded Data Annotation and Retrieval —
builds tools for authoring and applying metadata templates over scientific datasets. A CEDAR
**template** encodes a community's metadata standard in machine-actionable form — its fields,
their value types, and the controlled vocabularies they draw from — and an **instance** is a
template populated with values describing an actual dataset. Rich, standards-conformant metadata
is what makes scientific data findable and reusable; templates are how a community says precisely
what "conformant" means.

People, though, don't read JSON Schema — they meet a metadata standard as a **form**. CEDAR's
answer is the
[CEDAR Embeddable Editor](https://github.com/metadatacenter/cedar-embeddable-editor) (CEE;
[O'Connor et al. 2026](https://doi.org/10.5334/dsj-2026-002)), a web component that turns any
CEDAR template into a structured metadata-entry form — typed fields, required-value enforcement,
ontology-backed autocomplete via BioPortal, resolution of persistent identifiers such as ORCIDs
and RORs — and emits semantically rich JSON-LD.

The CEE's animating idea is that metadata authoring should be **embedded in the environments
where people already work**, not delegated to a separate portal. Data platforms such as the Open
Science Framework and Dryad embed it at the point of dataset submission; consortia such as HuBMAP
and RADx use its read-only mode as the authoritative way to inspect their templates and instances.
Because forms are rendered from the template at runtime, an evolving community standard is
reflected in every embedding with no interface code to rewrite — and across these deployments,
putting structured authoring where the work happens has meant more complete, consistent, and
semantically valid metadata.

This is a [Model Context Protocol](https://modelcontextprotocol.io/) server that makes the LLM
conversation one more environment the CEE is embedded in. Metadata work increasingly starts in
such conversations — an LLM can draft a template, find the right ontology terms, assemble and
validate instances — but a chat transcript is a poor place to *inspect* a structured artifact, and
a worse place to *type in* twenty field values. The tools here let the LLM hand the screen over
at exactly those moments: display a template or a populated instance in the user's browser
for review (read-only, the same mode HuBMAP and RADx rely on), or open an editable form so the
user enters values directly — picking ontology terms from autocomplete rather than dictating them
through chat — with the completed instance flowing back into the conversation for whatever comes
next.

`cedar-cee-mcp` is one of four MCP servers that together cover a metadata pipeline:
[`cedar-artifact-mcp`](https://github.com/metadatacenter/cedar-artifact-mcp) **authors** templates
and instances, [`bioportal-term-mcp`](https://github.com/metadatacenter/bioportal-term-mcp)
supplies the **ontology terms** they bind to,
[`cedar-rest-mcp`](https://github.com/metadatacenter/cedar-rest-mcp) **persists** them to a CEDAR
server — and this one is where a person **sees and completes** them.

Everything runs on your own machine: each tool serves a private page from the MCP server itself
and opens it in your browser; nothing is deployed, hosted, or shared (mechanics in
[How it works](#how-it-works) below). See [DESIGN.md](./DESIGN.md) for the principles and
[ROADMAP.md](./ROADMAP.md) for deferred work.

## Example workflow

Natural-language prompts the user gives the LLM, and what happens. This server speaks the CEE's
native form — canonical CEDAR JSON — and passes it through untouched in both directions; the
artifact translation in this walkthrough (YAML shown for readability) is `cedar-artifact-mcp`'s
work, not this server's.

Assume the LLM has authored a Patient Study template with `cedar-artifact-mcp`:

```yaml
type: template
name: Patient Study
id: https://repo.metadatacenter.org/templates/7f1c9e4a-5b2d-4e8f-a3c6-9d0b2f5a8c1e
children:
  - key: Patient Name
    type: text-field
    name: Patient Name
  - key: Age
    type: numeric-field
    name: Age
    datatype: xsd:int
```

and exported it to the canonical JSON form with `template_to_json` — which is what gets passed to
the tools below.

*Show me this template.*

`show_template` opens a browser tab rendering the template as a **read-only** form — structure,
fields, and constraints, inputs disabled. The tool returns the URL (shown to the user in case the
tab didn't open); nothing is collected back.

*Let me fill in an instance of this template.*

`fill_instance` opens an **editable** form and waits. The user types values — controlled-term
fields autocomplete against BioPortal via the CEDAR terminology service — and presses the form's
**Done** button. The tool call returns with the populated instance as **JSON-LD, exactly as the
editor produced it**. Rendered as compact YAML by `cedar-artifact-mcp`'s `instance_to_yaml`, the
instance reads:

```yaml
type: instance
name: Patient Study metadata
id: https://repo.metadatacenter.org/template-instances/4b8e2d7f-9a1c-4f5b-b6e3-2c7a0d9f4e8b
isBasedOn: https://repo.metadatacenter.org/templates/7f1c9e4a-5b2d-4e8f-a3c6-9d0b2f5a8c1e
children:
  Patient Name:
    value: Alice
  Age:
    datatype: xsd:int
    value: 30
```

If the user is still filling the form when the wait elapses, the call simply returns control with
the session id — the form stays open indefinitely and nothing the user typed is lost; when the
user says they're done, the LLM calls `collect_instance` with that id.

*Show me the populated instance.*

`show_instance` renders the instance read-only against its template — the full form with the
entered values in place.

## Tools

| Tool | What it does |
|---|---|
| `show_template(template, language?)` | Read-only rendering of a template, so a person can review the metadata standard as the form it will become. Structure, fields, and constraints render exactly as they would for data entry, with inputs disabled. Nothing is collected back; the tool returns the page URL. |
| `show_instance(template, instance, hide_empty_fields?, language?)` | Read-only rendering of a populated instance. The full template structure shows by default, with unpopulated fields blank; pass `hide_empty_fields: true` to show only fields that hold a value. |
| `fill_instance(template, instance?, timeout_seconds?, language?)` | Editable form. Waits up to `timeout_seconds` (default 120) for the user to press **Done** and returns the populated instance (JSON-LD, exactly as the editor produced it). If the user is still working, the call returns control to the conversation — the form stays open indefinitely, and `collect_instance` retrieves the result whenever the user finishes. Nothing expires. |
| `collect_instance(session_id)` | Fetches the submitted instance after the fact — the non-blocking half of fill. Latest Done press wins. |
| `list_sessions()` | What is currently showing: id, mode, URL, age, submitted state. |
| `ping(message)` | Echo; verifies the server is reachable. |

- **`template` / `instance`** are **canonical CEDAR JSON** — the JSON Schema and JSON-LD forms
  the CEE natively consumes — passed through **byte-for-byte**. This server does no format
  conversion and never interprets artifact content; convert YAML with `cedar-artifact-mcp`
  (`template_to_json` / `instance_to_json`), and convert a returned instance to YAML with its
  `instance_to_yaml` if desired.
- **Pre-filling** `fill_instance` with an existing instance requires a *complete* CEDAR JSON
  instance (the CEE's world); `cedar-artifact-mcp`'s `instance_to_json` given the template
  produces exactly that form.
- **`language`** sets the editor's UI language (ISO code, e.g. `"de"`); untranslated strings fall
  back to English.

## How it works

An MCP server is a headless process — it has no screen of its own. To show a form, this server
runs a tiny web server on your machine (bound to `127.0.0.1` on a random port) serving one static
page per session. That page loads the CEE web component from the CDN (pinned version), renders
the session's template or instance, and — in editable mode — sends what the user entered back to
the server when they press **Done**, where the waiting `fill_instance` (or a later
`collect_instance`) picks it up. Controlled-term autocomplete talks to CEDAR's public terminology
service directly from the browser. Sessions live in memory, are addressed by unguessable ids, and
die with the server. This is deliberately local, single-user machinery, not a deployable service
(DESIGN.md, Principle 2).

## Configuration

There is nothing to configure. Registration for Claude Code (`~/.claude.json`):

```json
"cedar-cee": {
  "command": "/usr/bin/java",
  "args": ["-jar", "/path/to/cedar-cee-mcp/target/cedar-cee-mcp-0.1.0-SNAPSHOT-all.jar"]
}
```

Use the absolute path to `java`; GUI clients don't inherit shell `PATH`. Restart the client after
editing the config.

## Requirements

- Java 17+, Maven 3.9+ (all dependencies are on Maven Central — no local library builds needed)
- A browser, and network access to the CDN and — for controlled-term autocomplete — the CEDAR
  terminology service.

## Build

```bash
mvn package        # builds target/cedar-cee-mcp-0.1.0-SNAPSHOT-all.jar (shaded, executable)
mvn test           # unit tests — in-process, no browser, no CDN
```

## Smoke test

Protocol-level (no browser):

```bash
cat <<'EOF' | java -jar target/cedar-cee-mcp-0.1.0-SNAPSHOT-all.jar
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"smoke","version":"0"}}}
{"jsonrpc":"2.0","method":"notifications/initialized"}
{"jsonrpc":"2.0","id":2,"method":"tools/list"}
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"ping","arguments":{"message":"hello"}}}
EOF
```

You should see the capabilities, six tools, and `pong: hello`. `Ctrl-C` to exit.

Browser-level (the part unit tests can't cover): call `show_template` from an MCP client with any
template and confirm the form renders read-only in the opened tab; then `fill_instance`, type a
value, press **Done**, and confirm the instance comes back. This end-to-end loop — CDN bundle
load, read-only rendering, editable rendering, typed value landing in `currentMetadata`, Done →
submit → returned instance — has been verified against CEE 1.5.0.

## License

BSD-2-Clause.
