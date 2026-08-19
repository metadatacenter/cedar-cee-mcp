# Roadmap

Scope decisions and deferred work. The server is local, single-user machinery by design;
several items below are deliberate cuts, not oversights.

## Next

- **Material icon font.** The CEE's icon ligatures render as text (`more_vert`, `unfold_more`)
  in the host page — the Material Symbols font is not loaded. Add the font link to the host page
  (or confirm which font face the pinned CEE version expects) so the chrome looks right.

- **Pre-fill ergonomics.** `fill_instance` with an existing instance requires the complete CEDAR
  JSON-LD form, which `cedar-artifact-mcp`'s `render_instance_artifact` (format: json) produces given the template. If
  that hand-off proves awkward in practice, revisit the flow — but artifact manipulation stays out
  of this server (DESIGN.md Principle 5), so any fix belongs on the artifact-mcp side.

- **Build without a locally installed library.** Like the sibling Java MCPs, this server pins
  `cedar-artifact-library:2.8.4-SNAPSHOT`, which must be `mvn install`ed from a local checkout
  (together with `cedar-parent` and the `cedar-model-*` libraries), so it no longer resolves
  purely from Maven Central. The fix is on the library side — publish released, non-SNAPSHOT
  artifacts and pin all three Java MCPs to a released version. See `cedar-artifact-mcp`'s ROADMAP
  for the full note.

- **The library dependency here is CEE-driven, not server-driven — the CEDAR server going
  YAML-native does NOT free cee-mcp of `cedar-artifact-library`.** This is a subtle point and it
  keeps getting lost, so it is spelled out here deliberately. cee-mcp pulls in the library to
  convert the caller's YAML into JSON for the **CEE web component** (`cedar-embeddable-editor`),
  which consumes JSON-LD internally. That is a *different target* from `cedar-artifact-rest-mcp`,
  whose conversion feeds the **CEDAR server**. So the rest-mcp "YAML straight through" plan — which
  lets rest-mcp drop the library once the *server* accepts YAML — has no equivalent here. cee-mcp
  can shed `cedar-artifact-library` only if the **CEE component itself** accepts YAML, an upstream
  change outside this project that is not on the horizon. Whatever the CEDAR server does about
  YAML, cee-mcp keeps this dependency. Do not assume the rest-mcp YAML work generalizes to cee-mcp.

## Later / maybe

- **Kiosk mode.** One persistent browser tab that receives successive show/fill calls (SSE or
  polling) instead of a new tab per session — better ergonomics for repeated demos. Tab-per-call
  is fine for now.

- **Inline in-chat rendering (MCP Apps).** The emerging MCP extension for `ui://` tool-result
  resources rendered in the client. Revisit when client support is broad and the sandbox/CSP
  story accommodates a 2.7 MB component bundle that needs network access to the terminology
  service. The localhost-tab approach works in every client today, including terminal ones.

- **Offline use.** *Done.* The bundle is fetched at build time and served from the jar, which the
  move to a Nexus-published CEE forced and which costs the artifact 2 MB. A session now reaches the
  network only for the terminology and bridge services the fields themselves call.

## Out of scope

- **Deployability.** Auth, TLS, non-loopback binds, session persistence, multi-user concerns —
  this is a local, single-user, conversation-lifetime tool (DESIGN.md Principle 2).
- **Template authoring or persistence.** The siblings own those.
- **Discovery.** No template browsing/search; artifacts arrive through tool arguments.
