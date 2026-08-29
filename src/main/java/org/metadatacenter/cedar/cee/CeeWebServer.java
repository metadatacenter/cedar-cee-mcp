package org.metadatacenter.cedar.cee;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.Executors;

/**
 * The localhost web surface for CEE sessions. An MCP server is headless; this is how it conjures a
 * browser UI: a tiny HTTP server bound to the loopback interface on an ephemeral port, serving
 * one static host page that loads the CEE web-component bundle and drives it with per-session
 * data.
 *
 * <p>The bundle is served from here too, out of the jar. The build fetches the pinned stable npm
 * package and stages its self-contained bundle as a resource, so the editor's own availability
 * does not depend on a CDN at runtime. A session needs no network beyond the terminology and bridge
 * services used by its fields.
 *
 * <p>Routes:
 * <ul>
 *   <li>{@code GET /s/{id}} — the host page (same page for every mode; it fetches its data);</li>
 *   <li>{@code GET /cee/cedar-embeddable-editor.js} — the CEE bundle, shared by every session;</li>
 *   <li>{@code GET /s/{id}/data} — the session's mode, CEE config, template, and optional
 *       instance as one JSON object;</li>
 *   <li>{@code POST /s/{id}/submit} — the populated JSON-LD instance from the browser's Done
 *       button;</li>
 *   <li>{@code GET /health} — liveness probe.</li>
 * </ul>
 *
 * <p>The server starts lazily on the first tool call that needs it and is loopback-only; session
 * ids are unguessable UUIDs. That is the entire access-control story, by design (see DESIGN.md).
 */
final class CeeWebServer
{
  /** Where the host page loads the CEE bundle from; served by {@link #route} out of the jar. */
  static final String CEE_BUNDLE_PATH = "/cee/cedar-embeddable-editor.js";

  private static final String CEE_BUNDLE_RESOURCE = "/web/cedar-embeddable-editor.js";

  /**
   * CEDAR's public terminology server, backing the CEE's controlled-term autocomplete. A base URL,
   * not an endpoint: CEE appends the integrated-search route itself, and refuses a base that does
   * not end in a slash.
   */
  static final String TERMINOLOGY_BASE_URL = "https://terminology.metadatacenter.org/";

  /**
   * CEDAR's public bridge server, which resolves the external-authority fields — ORCID, ROR, DOI,
   * PubMed, RRID, NIH grant, PFAS. A base URL on the same terms as the terminology one: unset,
   * those fields offer nothing and CEE names the key it wanted.
   */
  static final String BRIDGE_BASE_URL = "https://bridge.metadatacenter.org/";

  private static final ObjectMapper JACKSON = new ObjectMapper();

  private final SessionStore sessions;
  private HttpServer server; // guarded by this

  CeeWebServer(SessionStore sessions)
  {
    this.sessions = sessions;
  }

  /** Start the server if it isn't running and return its base URL, e.g. {@code http://127.0.0.1:49213}. */
  synchronized String ensureStarted()
  {
    if (server == null) {
      try {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      } catch (IOException e) {
        throw new RuntimeException("could not start the local CEE web server: " + e.getMessage(), e);
      }
      server.createContext("/", this::route);
      server.setExecutor(Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "cee-web");
        thread.setDaemon(true);
        return thread;
      }));
      server.start();
    }
    return baseUrl();
  }

  synchronized void stop()
  {
    if (server != null) {
      server.stop(0);
      server = null;
    }
  }

  private synchronized String baseUrl()
  {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  String sessionUrl(Session session)
  {
    return ensureStarted() + "/s/" + session.id;
  }

  // ---------------------------------------------------------------- routing

  private void route(HttpExchange exchange) throws IOException
  {
    try {
      String path = exchange.getRequestURI().getPath();
      String method = exchange.getRequestMethod();

      if ("GET".equals(method) && "/health".equals(path)) {
        respond(exchange, 200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8));
      } else if ("GET".equals(method) && CEE_BUNDLE_PATH.equals(path)) {
        // Not session-scoped: one bundle serves every session, and the browser caches it once.
        respond(exchange, 200, "text/javascript; charset=utf-8", resource(CEE_BUNDLE_RESOURCE));
      } else if (path.startsWith("/s/")) {
        routeSession(exchange, method, path);
      } else {
        respond(exchange, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
      }
    } catch (Exception e) {
      respond(exchange, 500, "text/plain",
          ("internal error: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
    } finally {
      exchange.close();
    }
  }

  private void routeSession(HttpExchange exchange, String method, String path) throws IOException
  {
    String[] parts = path.split("/"); // "", "s", "{id}", ("data" | "submit")?
    Optional<Session> found = parts.length >= 3 ? sessions.get(parts[2]) : Optional.empty();
    if (found.isEmpty()) {
      respond(exchange, 404, "text/plain", "unknown session".getBytes(StandardCharsets.UTF_8));
      return;
    }
    Session session = found.get();

    if ("GET".equals(method) && parts.length == 3) {
      respond(exchange, 200, "text/html; charset=utf-8", hostPage());
    } else if ("GET".equals(method) && parts.length == 4 && "data".equals(parts[3])) {
      respond(exchange, 200, "application/json", sessionData(session));
    } else if ("POST".equals(method) && parts.length == 4 && "submit".equals(parts[3])) {
      handleSubmit(exchange, session);
    } else {
      respond(exchange, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
    }
  }

  private void handleSubmit(HttpExchange exchange, Session session) throws IOException
  {
    if (session.mode != Session.Mode.FILL) {
      respond(exchange, 409, "text/plain",
          "session is read-only; nothing to submit".getBytes(StandardCharsets.UTF_8));
      return;
    }
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    ObjectNode instance;
    try {
      instance = Json.asObject(body);
    } catch (RuntimeException e) {
      respond(exchange, 400, "text/plain",
          ("submitted metadata is not a JSON object: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
      return;
    }
    session.submit(instance);
    respond(exchange, 204, null, new byte[0]);
  }

  // ---------------------------------------------------------------- payloads

  /**
   * The one JSON object the host page needs: display mode, the CEE config for that mode, the
   * template, and (when present) the instance.
   */
  byte[] sessionData(Session session)
  {
    ObjectNode data = JACKSON.createObjectNode();
    data.put("mode", session.mode.name());
    data.set("config", ceeConfig(session));
    data.set("templateObject", session.templateJson);
    if (session.instanceJson != null)
      data.set("instanceObject", session.instanceJson);
    return Json.compact(data).getBytes(StandardCharsets.UTF_8);
  }

  /**
   * The CEE configuration per session. A read-only mode sets {@code readOnlyMode}; the fill mode
   * leaves the editor live. Controlled-term autocomplete is pointed at CEDAR's terminology server
   * and the external-authority fields at its bridge server, so a template using either kind works
   * without the caller configuring anything. The download menu is on, so a viewer can take the
   * artifact away as JSON-LD, JSON Schema or YAML — the panels that used to print those beneath the
   * form are gone. The UI language follows the session's, falling back to English for untranslated
   * strings.
   *
   * <p>CEE reads these nine keys and no others, and says so in the browser console when it is handed
   * something else. Its configuration surface narrowed sharply in 2.0: the panels this MCP used to
   * switch on — instance data, template source, rendering representation, multi-instance info, the
   * data-quality report — and the header, footer and sample-template chrome it switched off are all
   * gone, along with {@code hideEmptyFields}. Sending them would cost nothing but a console full of
   * complaints about keys that have no effect.
   */
  private ObjectNode ceeConfig(Session session)
  {
    ObjectNode config = JACKSON.createObjectNode();
    config.put("showDownloadMenu", true);
    config.put("defaultLanguage", session.language);
    config.put("fallbackLanguage", "en");
    config.put("terminologyBaseUrl", TERMINOLOGY_BASE_URL);
    config.put("bridgeBaseUrl", BRIDGE_BASE_URL);
    if (session.mode != Session.Mode.FILL)
      config.put("readOnlyMode", true);
    return config;
  }

  private byte[] hostPage() throws IOException
  {
    return resource("/web/session.html");
  }

  /**
   * A static resource from the jar. The CEE bundle is staged there by the build, so a jar that was
   * assembled without it would fail here rather than in the browser, where the symptom would be a
   * page that loads and then does nothing.
   */
  private static byte[] resource(String path) throws IOException
  {
    try (InputStream in = CeeWebServer.class.getResourceAsStream(path)) {
      if (in == null)
        throw new IllegalStateException("resource " + path + " missing from jar");
      return in.readAllBytes();
    }
  }

  private static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
      throws IOException
  {
    if (contentType != null)
      exchange.getResponseHeaders().set("Content-Type", contentType);
    exchange.sendResponseHeaders(status, status == 204 ? -1 : body.length);
    if (status != 204)
      exchange.getResponseBody().write(body);
  }
}
