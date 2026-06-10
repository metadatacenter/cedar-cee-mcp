# For Claude (or any new contributor)

Start with [DESIGN.md](./DESIGN.md) (principles — especially the headless-server-conjures-a-browser
model and the blocking/collect duality) and [ROADMAP.md](./ROADMAP.md) (deliberate cuts vs.
deferred work). [README.md](./README.md) is the user-facing story.

## Conventions you must respect

Same house rules as the sibling MCPs (`cedar-artifact-mcp`, `cedar-rest-mcp`):

- **Comments describe code-level facts only.** No PR numbers, session context, or anything that
  needs the authoring context to make sense.
- **This server does not interpret artifacts.** No parsing, conversion, or validation of artifact
  content — canonical CEDAR JSON in, JSON-LD out, byte-for-byte, and deliberately **no dependency
  on `cedar-artifact-library`**. YAML ↔ JSON translation belongs to `cedar-artifact-mcp`; if a
  conversion concern appears here, it belongs there.
- **Tests must pass with no skips.** Two tiers: `mvn test` runs the in-process unit tests (no
  browser, no CDN, no network); `mvn verify` adds `EndToEndStdioIT`, which spawns the shaded jar
  and exercises stdio + HTTP from outside the process (shading, resource packaging, tool
  registration). The IT must never open a browser — it hands the subprocess a PATH of no-op
  open/xdg-open shims. Anything that needs a *real* browser (CDN failure, rendering) is a manual
  smoke test (README) — don't try to automate a browser in either tier.
- **The CEE is a pinned, prebuilt bundle** (DESIGN.md Principle 3). Don't introduce npm, Node, or
  a frontend build. Upgrading the CEE = bump the version string in
  `src/main/resources/web/session.html` + a manual browser check of both modes.

## Layout

- `CedarCeeMcpServer` — main; stdio MCP wiring, ping.
- `CeeTools` — the five tools; all return the session URL; fill blocks on the session future.
- `CeeWebServer` — loopback HTTP: host page, per-session data, submit endpoint. Lazy-started on
  first use.
- `Session` / `SessionStore` — in-memory; UUID ids; `firstSubmission()` future + latest-wins
  resubmission.
- `Json` — JSON parse/serialize helpers; no artifact interpretation (DESIGN.md Principle 5).
- `src/main/resources/web/session.html` — the entire frontend. Keep it small enough to read in
  one sitting.

## Build & run

```bash
mvn package    # shaded jar: target/cedar-cee-mcp-<version>-all.jar
mvn test       # unit tests, no network
```

There are no environment variables, deliberately. Tests suppress browser-opening by injecting a
no-op `BrowserOpener` subclass; the terminology endpoint and CEE version are constants
(`CeeWebServer.TERMINOLOGY_URL`, the version string in `session.html`).
