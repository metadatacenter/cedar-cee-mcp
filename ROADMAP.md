# Roadmap

Scope decisions and deferred work. The server is local, single-user machinery by design;
several items below are deliberate cuts, not oversights.

## Next

- **End-to-end stdio IT.** Mirror the siblings: spawn the shaded jar, speak real JSON-RPC over
  stdio, create a session, fetch the host page and session data over HTTP, POST a submission, and
  collect it — the regression net for shading, classpath, and tool-registration failures. (The
  in-browser half stays a manual smoke test; driving a real browser from CI is out of proportion
  here.)

- **Material icon font.** The CEE's icon ligatures render as text (`more_vert`, `unfold_more`)
  in the host page — the Material Symbols font is not loaded. Add the font link to the host page
  (or confirm which font face the pinned CEE version expects) so the chrome looks right.

- **Pre-fill ergonomics.** `fill_instance` with an existing instance requires the complete CEDAR
  JSON form; a sparse instance must be inflated first via `cedar-artifact-mcp`. Once the artifact
  library grows its template-driven inflater (see the library ROADMAP), inflate here
  automatically — the template is already in hand.

## Later / maybe

- **Kiosk mode.** One persistent browser tab that receives successive show/fill calls (SSE or
  polling) instead of a new tab per session — better ergonomics for repeated demos. Tab-per-call
  is fine for now.

- **Inline in-chat rendering (MCP Apps).** The emerging MCP extension for `ui://` tool-result
  resources rendered in the client. Revisit when client support is broad and the sandbox/CSP
  story accommodates a 2.7 MB component bundle that needs network access to the terminology
  service. The localhost-tab approach works in every client today, including terminal ones.

- **`cedar-artifact-viewer` (CAV) for read-only display.** A dedicated viewer component exists
  (npm `cedar-artifact-viewer`, currently 0.9.x). Using the CEE's `readOnlyMode` for both
  display modes keeps one component and one spike; switch the `show_*` tools to CAV if its
  rendering proves better for inspection.

- **Offline use.** The CEE bundle is loaded from the CDN; an offline environment has no fallback.
  If that need materializes, vendor the bundle into the jar's resources and serve it locally —
  a fatter artifact and a manual step per CEE upgrade.

## Out of scope

- **Deployability.** Auth, TLS, non-loopback binds, session persistence, multi-user concerns —
  this is a local, single-user, conversation-lifetime tool (DESIGN.md Principle 2).
- **Template authoring or persistence.** The siblings own those.
- **Discovery.** No template browsing/search; artifacts arrive through tool arguments.
