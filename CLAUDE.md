# For Claude (or any new contributor)

Start with [DESIGN.md](./DESIGN.md) (principles — especially the headless-server-conjures-a-browser
model and the blocking/collect duality) and [ROADMAP.md](./ROADMAP.md) (deliberate cuts vs.
deferred work). [README.md](./README.md) is the user-facing story.

## Conventions you must respect

Same house rules as the sibling MCPs (`cedar-artifact-mcp`, `cedar-artifact-rest-mcp`):

- **Comments describe code-level facts only.** No PR numbers, session context, or anything that
  needs the authoring context to make sense.
- **Compact YAML is the primary input form.** Every display/population tool accepts the compact
  YAML exchange form and also accepts CEDAR JSON. `Json.toObject` uses
  `cedar-artifact-library`'s compact `YamlArtifactReader` and `JsonArtifactRenderer` to turn YAML
  into the JSON object the CEE consumes. The library version is pinned by
  `cedar-artifact-library.version` in `pom.xml`; keep the dependency and pin in place. A populated
  instance still comes back as JSON-LD exactly as the CEE submitted it. Conversion of that output
  to YAML, if wanted, belongs to `cedar-artifact-mcp`.
- **Tests must pass with no skips.** Two tiers: `mvn test` runs the in-process unit tests (no
  browser, no CDN, no network); `mvn verify` adds `EndToEndStdioIT`, which spawns the shaded jar
  and exercises stdio + HTTP from outside the process (shading, resource packaging, tool
  registration). The IT must never open a browser — it hands the subprocess a PATH of no-op
  open/xdg-open shims. Anything that needs a *real* browser (CDN failure, rendering) is a manual
  smoke test (README) — don't try to automate a browser in either tier.
- **The CEE is a pinned, prebuilt bundle** (DESIGN.md Principle 3). Don't introduce npm, Node, or
  a frontend build. Upgrading the CEE = update `cee.version` and `cee.sha256` together in
  `pom.xml`, then manually check both browser modes. The configuration sent by `CeeWebServer`
  uses the CEE 2.0 names `showDownloadMenu`, `defaultLanguage`, `fallbackLanguage`,
  `terminologyBaseUrl`, `bridgeBaseUrl`, and, for read-only sessions, `readOnlyMode`; do not bring
  back legacy key names.

## Layout

- `CedarCeeMcpServer` — main; stdio MCP wiring, ping.
- `CeeTools` — the five tools; all return the session URL; fill blocks on the session future.
- `CeeWebServer` — loopback HTTP: host page, per-session data, submit endpoint. Lazy-started on
  first use.
- `Session` / `SessionStore` — in-memory; UUID ids; `firstSubmission()` future + latest-wins
  resubmission.
- `Json` — reads compact artifact YAML through `cedar-artifact-library`, accepts JSON directly,
  and serializes the JSON objects exchanged with the CEE (DESIGN.md Principle 5).
- `src/main/resources/web/session.html` — the small host page around the separately staged CEE
  bundle. Keep it small enough to read in one sitting.

## Build & run

```bash
mvn package    # shaded jar: target/cedar-cee-mcp-<version>-all.jar
mvn test       # unit tests, no network
```

There are no environment variables, deliberately. Tests suppress browser-opening by injecting a
no-op `BrowserOpener` subclass. The terminology and bridge endpoints are constants
(`CeeWebServer.TERMINOLOGY_BASE_URL`, `CeeWebServer.BRIDGE_BASE_URL`); the CEE bundle version and
hash are Maven properties in `pom.xml`.
