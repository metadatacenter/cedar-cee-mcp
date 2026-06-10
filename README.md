# cedar-cee-mcp

An MCP server that puts a **human in the loop** of a CEDAR metadata workflow: it displays CEDAR
**templates** and **instances** in the user's browser via the
[CEDAR Embeddable Editor](https://github.com/metadatacenter/cedar-embeddable-editor) (CEE), and —
for instance population — collects what the human typed back into the conversation as YAML.

It completes a trio:

- [`cedar-artifact-mcp`](../cedar-artifact-mcp) **authors** artifacts in memory;
- [`cedar-rest-mcp`](../cedar-rest-mcp) **persists** them to a CEDAR server;
- `cedar-cee-mcp` **shows** them to a person, and lets a person **fill in** an instance using a
  real form — with the CEE's ontology-backed autocomplete for controlled-term fields — instead of
  dictating values through chat.

An MCP server is headless, so the display surface is conjured: each tool stores a session on a
tiny loopback-only web server, opens `http://127.0.0.1:<port>/s/<session>` in the user's default
browser, and that page hosts the CEE web component. The CEE bundle is loaded from the CDN
(pinned), with an optional locally vendored fallback.

See [DESIGN.md](./DESIGN.md) for the principles and [ROADMAP.md](./ROADMAP.md) for deferred work.

## Example workflow

Natural-language prompts the user gives the LLM, and what happens. Assume the LLM has authored a
Patient Study template with `cedar-artifact-mcp`:

```yaml
type: template
name: Patient Study
children:
  - key: Patient Name
    type: text-field
    name: Patient Name
  - key: Age
    type: numeric-field
    name: Age
    datatype: xsd:int
```

*Show me this template.*

`show_template` opens a browser tab rendering the template as a **read-only** form — structure,
fields, and constraints, inputs disabled. The tool returns the URL (shown to the user in case the
tab didn't open); nothing is collected back.

*Let me fill in a record for this template.*

`fill_instance` opens an **editable** form and blocks. The user types values — controlled-term
fields autocomplete against BioPortal via the CEDAR terminology service — and presses the form's
**Done** button. The tool call returns with the populated instance:

```yaml
type: instance
name: Patient Study metadata
isBasedOn: https://repo.metadatacenter.org/templates/...
children:
  Patient Name:
    value: Alice
  Age:
    datatype: xsd:int
    value: 30
```

If the user takes longer than the timeout, the call returns with the session id and the form
stays open; when the user says they're done, the LLM calls `collect_instance` with that id.

*Show me the populated instance.*

`show_instance` renders the instance read-only against its template — populated values only,
empty fields hidden.

From here the pipeline continues with the siblings: validate with `cedar-artifact-mcp`, persist
with `cedar-rest-mcp`.

## Tools

| Tool | What it does |
|---|---|
| `show_template(template)` | Read-only render of a template. Returns the page URL. |
| `show_instance(template, instance)` | Read-only render of a populated instance (empty fields hidden). |
| `fill_instance(template, instance?, timeout_seconds?)` | Editable form; **blocks** until the user presses Done (default 120 s), then returns the instance as compact YAML. On timeout the session stays open. |
| `collect_instance(session_id)` | Fetches the submitted instance after the fact — the non-blocking half of fill. Latest Done press wins. |
| `list_sessions()` | What is currently showing: id, mode, URL, age, submitted state. |
| `ping(message)` | Echo; verifies the server is reachable. |

- **`template` / `instance`** are accepted as **YAML or JSON** (auto-detected). YAML is converted
  to the canonical CEDAR JSON form via `cedar-artifact-library`; JSON is passed to the CEE
  untouched. Returned instances are **compact YAML** (raw JSON-LD as a fallback if the library
  can't read what the CEE produced — the human's input is never dropped).
- **Pre-filling** `fill_instance` with an existing instance requires a *complete* CEDAR JSON
  instance (the CEE's world). A sparse instance from `cedar-artifact-mcp` should be inflated
  first (`instance_to_json` given the template).
- Sessions are **in-memory** and die with the server; ids are unguessable UUIDs; the web server
  binds to the loopback interface only.

## Configuration

No configuration is required. Optional environment variables (set in the MCP client's config):

| Variable | Effect |
|---|---|
| `CEDAR_CEE_BUNDLE` | Path to a locally vendored `cedar-embeddable-editor.min.js`, served as a fallback when the CDN is unreachable (e.g. hackathon Wi-Fi). Get it with: `curl -L -o cedar-embeddable-editor.min.js https://cdn.jsdelivr.net/npm/cedar-embeddable-editor@1.5.0/cedar-embeddable-editor.min.js` |
| `CEDAR_CEE_TERMINOLOGY_URL` | Override the terminology endpoint for controlled-term autocomplete. Defaults to CEDAR's public proxy (`https://terminology.metadatacenter.org/bioportal/integrated-search`). |
| `CEDAR_CEE_NO_BROWSER` | Set to `1` to never auto-open a browser (the tools still return the URL). |

Registration for Claude Code (`~/.claude.json`):

```json
"cedar-cee": {
  "command": "/usr/bin/java",
  "args": ["-jar", "/path/to/cedar-cee-mcp/target/cedar-cee-mcp-0.1.0-SNAPSHOT-all.jar"]
}
```

Use the absolute path to `java`; GUI clients don't inherit shell `PATH`. Restart the client after
editing the config.

## Requirements

- Java 17+, Maven 3.9+
- A local install of `cedar-artifact-library` 2.8.1-SNAPSHOT (build it with `mvn install` from a
  checkout of
  [metadatacenter/cedar-artifact-library](https://github.com/metadatacenter/cedar-artifact-library)
  on `develop`).
- A browser, and network access to the CDN (or a vendored bundle) and — for controlled-term
  autocomplete — the CEDAR terminology service.

## Build

```bash
mvn package        # builds target/cedar-cee-mcp-0.1.0-SNAPSHOT-all.jar (shaded, executable)
mvn test           # unit tests — in-process, no browser, no CDN
```

## Smoke test

Protocol-level (no browser):

```bash
cat <<'EOF' | CEDAR_CEE_NO_BROWSER=1 java -jar target/cedar-cee-mcp-0.1.0-SNAPSHOT-all.jar
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"smoke","version":"0"}}}
{"jsonrpc":"2.0","method":"notifications/initialized"}
{"jsonrpc":"2.0","id":2,"method":"tools/list"}
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"ping","arguments":{"message":"hello"}}}
EOF
```

You should see the capabilities, six tools, and `pong: hello`. `Ctrl-C` to exit.

Browser-level (the part unit tests can't cover): call `show_template` from an MCP client with any
template and confirm the form renders read-only in the opened tab; then `fill_instance`, type a
value, press **Done**, and confirm the YAML comes back. This end-to-end loop — CDN bundle load,
read-only render, editable render, typed value landing in `currentMetadata`, Done → submit →
YAML — has been verified against CEE 1.5.0.

## License

BSD-2-Clause.
