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
 * <p>Routes:
 * <ul>
 *   <li>{@code GET /s/{id}} — the host page (same page for every mode; it fetches its data);</li>
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
  /** CEDAR's public terminology proxy, backing the CEE's controlled-term autocomplete. */
  static final String TERMINOLOGY_URL =
      "https://terminology.metadatacenter.org/bioportal/integrated-search";

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
      instance = ArtifactCodec.asObjectNode(body);
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
    data.set("config", ceeConfig(session.mode));
    data.set("templateObject", session.templateJson);
    if (session.instanceJson != null)
      data.set("instanceObject", session.instanceJson);
    return ArtifactCodec.compactJson(data).getBytes(StandardCharsets.UTF_8);
  }

  /**
   * The CEE configuration per mode. Read-only modes set {@code readOnlyMode}; the fill mode leaves
   * the editor live and points ontology autocomplete at the CEDAR terminology proxy. The
   * {@code showInstanceData*} / {@code showTemplateSourceData} debug panels stay off everywhere.
   */
  private ObjectNode ceeConfig(Session.Mode mode)
  {
    ObjectNode config = JACKSON.createObjectNode();
    config.put("showInstanceDataCore", false);
    config.put("showInstanceDataFull", false);
    config.put("showTemplateSourceData", false);
    config.put("defaultLanguage", "en");
    config.put("terminologyIntegratedSearchUrl", TERMINOLOGY_URL);
    if (mode != Session.Mode.FILL) {
      config.put("readOnlyMode", true);
      // Hide empty fields when displaying a populated instance; show the full structure when
      // displaying a bare template (everything is empty there — hiding would blank the page).
      config.put("hideEmptyFields", mode == Session.Mode.VIEW_INSTANCE);
    }
    return config;
  }

  private byte[] hostPage() throws IOException
  {
    try (InputStream in = CeeWebServer.class.getResourceAsStream("/web/session.html")) {
      if (in == null)
        throw new IllegalStateException("host page resource /web/session.html missing from jar");
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
