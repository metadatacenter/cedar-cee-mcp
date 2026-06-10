package org.metadatacenter.cedar.cee;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the session web server and the tools end-to-end in process — the browser is stubbed
 * out and the CEE bundle is never fetched (these tests cover everything up to the component
 * boundary; rendering inside a real browser is the manual smoke test in the README).
 *
 * <p>Artifacts are arbitrary JSON here: this server passes artifact JSON through byte-for-byte
 * and never interprets it, so the fixtures only need to be JSON objects, not valid CEDAR.
 */
final class CeeMcpTest
{
  private static final ObjectMapper JACKSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  private static final String TEMPLATE_JSON = """
      {
        "@type": "https://schema.metadatacenter.org/core/Template",
        "schema:name": "Patient Study"
      }
      """;

  private static final String INSTANCE_JSON = """
      {
        "schema:name": "Patient Study metadata",
        "schema:isBasedOn": "https://repo.metadatacenter.org/templates/123",
        "Patient Name": {"@value": "Alice"}
      }
      """;

  private static final String TEMPLATE_YAML = """
      type: template
      name: Patient Study
      """;

  /** Never opens a real browser. */
  private static final class NoBrowser extends BrowserOpener
  {
    @Override boolean open(String url) { return false; }
  }

  private final SessionStore sessions = new SessionStore();
  private final CeeWebServer web = new CeeWebServer(sessions);
  private final CeeTools tools = new CeeTools(sessions, web, new NoBrowser());

  @AfterEach void stopServer()
  {
    web.stop();
  }

  // ---------------------------------------------------------------- JSON boundary

  @Test void template_json_passes_through_untouched() throws Exception
  {
    call("show_template", Map.of("template", TEMPLATE_JSON));

    Session session = sessions.all().get(0);
    HttpResponse<String> data = get(web.sessionUrl(session) + "/data");
    ObjectNode served = (ObjectNode) JACKSON.readTree(data.body()).get("templateObject");
    assertEquals(JACKSON.readTree(TEMPLATE_JSON), served,
        "artifact JSON must reach the page byte-for-byte (no conversion, no additions)");
  }

  @Test void yaml_input_is_redirected_to_the_artifact_mcp()
  {
    McpSchema.CallToolResult result = call("show_template", Map.of("template", TEMPLATE_YAML));
    assertTrue(result.isError());
    assertTrue(text(result).contains("cedar-artifact-mcp"), text(result));
  }

  // ---------------------------------------------------------------- web server

  @Test void serves_host_page_and_session_data() throws Exception
  {
    Session session = sessions.create(Session.Mode.VIEW_TEMPLATE, Json.asObject(TEMPLATE_JSON), null);
    String url = web.sessionUrl(session);

    HttpResponse<String> page = get(url);
    assertEquals(200, page.statusCode());
    assertTrue(page.body().contains("cedar-embeddable-editor"), "host page hosts the CEE tag");

    HttpResponse<String> data = get(url + "/data");
    assertEquals(200, data.statusCode());
    ObjectNode dataJson = (ObjectNode) JACKSON.readTree(data.body());
    assertEquals("VIEW_TEMPLATE", dataJson.get("mode").asText());
    assertTrue(dataJson.get("config").get("readOnlyMode").asBoolean(), "view modes are read-only");
    assertEquals("Patient Study", dataJson.get("templateObject").get("schema:name").asText());
    assertFalse(dataJson.has("instanceObject"));
  }

  @Test void fill_session_data_is_editable_and_carries_terminology_url() throws Exception
  {
    Session session = sessions.create(Session.Mode.FILL, Json.asObject(TEMPLATE_JSON), null);

    HttpResponse<String> data = get(web.sessionUrl(session) + "/data");
    ObjectNode config = (ObjectNode) JACKSON.readTree(data.body()).get("config");
    assertFalse(config.has("readOnlyMode"), "fill mode leaves the editor live");
    assertEquals(CeeWebServer.TERMINOLOGY_URL,
        config.get("terminologyIntegratedSearchUrl").asText());
  }

  @Test void unknown_session_is_404() throws Exception
  {
    String base = web.ensureStarted();
    assertEquals(404, get(base + "/s/nope").statusCode());
  }

  @Test void submit_stores_the_instance_and_completes_the_future() throws Exception
  {
    Session session = sessions.create(Session.Mode.FILL, Json.asObject(TEMPLATE_JSON), null);

    HttpResponse<String> response = post(web.sessionUrl(session) + "/submit", INSTANCE_JSON);
    assertEquals(204, response.statusCode());
    assertTrue(session.submittedInstance().isPresent());
    assertTrue(session.firstSubmission().isDone());
  }

  @Test void submit_to_a_read_only_session_is_rejected() throws Exception
  {
    Session session = sessions.create(Session.Mode.VIEW_TEMPLATE, Json.asObject(TEMPLATE_JSON), null);
    HttpResponse<String> response = post(web.sessionUrl(session) + "/submit", "{}");
    assertEquals(409, response.statusCode());
  }

  @Test void malformed_submission_is_a_400_and_does_not_poison_the_session() throws Exception
  {
    // A page bug or hand-crafted request must not crash the session or count as a submission.
    Session session = sessions.create(Session.Mode.FILL, Json.asObject(TEMPLATE_JSON), null);
    String submitUrl = web.sessionUrl(session) + "/submit";

    assertEquals(400, post(submitUrl, "not json {{{").statusCode());
    assertEquals(400, post(submitUrl, "[1, 2, 3]").statusCode(), "non-object JSON is rejected");
    assertTrue(session.submittedInstance().isEmpty(), "rejected submissions must not be stored");

    // The session still works after the bad submissions.
    assertEquals(204, post(submitUrl, INSTANCE_JSON).statusCode());
    assertTrue(session.submittedInstance().isPresent());
  }

  @Test void concurrent_fill_sessions_keep_their_submissions_apart() throws Exception
  {
    Session first = sessions.create(Session.Mode.FILL, Json.asObject(TEMPLATE_JSON), null);
    Session second = sessions.create(Session.Mode.FILL, Json.asObject(TEMPLATE_JSON), null);

    post(web.sessionUrl(first) + "/submit", "{\"Patient Name\": {\"@value\": \"Alice\"}}");
    post(web.sessionUrl(second) + "/submit", "{\"Patient Name\": {\"@value\": \"Bob\"}}");

    assertEquals("Alice",
        first.submittedInstance().orElseThrow().get("Patient Name").get("@value").asText());
    assertEquals("Bob",
        second.submittedInstance().orElseThrow().get("Patient Name").get("@value").asText());
  }

  // ---------------------------------------------------------------- tools

  @Test void show_template_returns_the_session_url()
  {
    McpSchema.CallToolResult result = call("show_template", Map.of("template", TEMPLATE_JSON));
    assertFalse(result.isError(), text(result));
    assertTrue(text(result).contains("http://127.0.0.1:"), text(result));
  }

  @Test void show_instance_shows_empty_fields_by_default_and_hides_on_request() throws Exception
  {
    call("show_instance", Map.of("template", TEMPLATE_JSON, "instance", INSTANCE_JSON));
    call("show_instance", Map.of("template", TEMPLATE_JSON, "instance", INSTANCE_JSON,
        "hide_empty_fields", true));

    List<Session> created = sessions.all();
    assertEquals(2, created.size());
    for (Session session : created) {
      HttpResponse<String> data = get(web.sessionUrl(session) + "/data");
      ObjectNode config = (ObjectNode) JACKSON.readTree(data.body()).get("config");
      assertEquals(session.hideEmptyFields, config.get("hideEmptyFields").asBoolean());
    }
    assertFalse(created.get(0).hideEmptyFields, "default is to show the full template structure");
    assertTrue(created.get(1).hideEmptyFields);
  }

  @Test void language_parameter_reaches_the_cee_config() throws Exception
  {
    call("show_template", Map.of("template", TEMPLATE_JSON, "language", "de"));
    call("fill_instance", Map.of("template", TEMPLATE_JSON, "timeout_seconds", 1));

    List<Session> created = sessions.all();
    assertEquals("de", created.get(0).language);
    assertEquals("en", created.get(1).language, "language defaults to English");

    ObjectNode config = (ObjectNode) JACKSON
        .readTree(get(web.sessionUrl(created.get(0)) + "/data").body()).get("config");
    assertEquals("de", config.get("defaultLanguage").asText());
    assertEquals("en", config.get("fallbackLanguage").asText());
    assertFalse(config.get("showSampleTemplateLinks").asBoolean(),
        "sample-template loader is pinned off");
  }

  @Test void show_instance_requires_both_artifacts()
  {
    McpSchema.CallToolResult result = call("show_instance", Map.of("template", TEMPLATE_JSON));
    assertTrue(result.isError());
    assertTrue(text(result).contains("instance"), text(result));
  }

  @Test void fill_instance_times_out_with_a_collect_hint()
  {
    McpSchema.CallToolResult result = call("fill_instance",
        Map.of("template", TEMPLATE_JSON, "timeout_seconds", 1));
    assertFalse(result.isError(), text(result));
    assertTrue(text(result).contains("collect_instance"), text(result));
    assertNotNull(sessionIdIn(text(result)));
  }

  @Test void fill_instance_returns_the_instance_once_the_user_submits()
  {
    // Simulate the user: submit the instance over HTTP shortly after the blocking call starts.
    CompletableFuture<McpSchema.CallToolResult> pending = CompletableFuture.supplyAsync(
        () -> call("fill_instance", Map.of("template", TEMPLATE_JSON, "timeout_seconds", 30)));

    Session session = awaitSingleSession();
    try {
      assertEquals(204, post(web.sessionUrl(session) + "/submit", INSTANCE_JSON).statusCode());
      McpSchema.CallToolResult result = pending.get();
      assertFalse(result.isError(), text(result));
      assertTrue(text(result).contains("Alice"), text(result));
      assertTrue(text(result).contains("JSON-LD"), text(result));
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  @Test void collect_instance_reports_pending_then_returns_the_submission() throws Exception
  {
    Session session = sessions.create(Session.Mode.FILL, Json.asObject(TEMPLATE_JSON), null);

    McpSchema.CallToolResult before = call("collect_instance", Map.of("session_id", session.id));
    assertFalse(before.isError(), text(before));
    assertTrue(text(before).contains("not pressed Done"), text(before));

    post(web.sessionUrl(session) + "/submit", INSTANCE_JSON);

    McpSchema.CallToolResult after = call("collect_instance", Map.of("session_id", session.id));
    assertFalse(after.isError(), text(after));
    assertTrue(text(after).contains("Alice"), text(after));
  }

  @Test void fill_prefill_instance_reaches_the_session_data() throws Exception
  {
    call("fill_instance",
        Map.of("template", TEMPLATE_JSON, "instance", INSTANCE_JSON, "timeout_seconds", 1));

    Session session = sessions.all().get(0);
    ObjectNode data = (ObjectNode) JACKSON.readTree(get(web.sessionUrl(session) + "/data").body());
    assertEquals("Alice", data.get("instanceObject").get("Patient Name").get("@value").asText(),
        "the pre-fill instance must reach the page for the editor to render");
  }

  @Test void timed_out_fill_degrades_into_collect()
  {
    // The documented degraded path, end to end: the wait elapses, the user submits later, and
    // collect_instance still returns the submission.
    McpSchema.CallToolResult timedOut = call("fill_instance",
        Map.of("template", TEMPLATE_JSON, "timeout_seconds", 1));
    String sessionId = sessionIdIn(text(timedOut));
    assertNotNull(sessionId, text(timedOut));

    try {
      Session session = sessions.get(sessionId).orElseThrow();
      assertEquals(204, post(web.sessionUrl(session) + "/submit", INSTANCE_JSON).statusCode());
    } catch (Exception e) {
      throw new AssertionError(e);
    }

    McpSchema.CallToolResult collected = call("collect_instance", Map.of("session_id", sessionId));
    assertFalse(collected.isError(), text(collected));
    assertTrue(text(collected).contains("Alice"), text(collected));
  }

  @Test void collect_instance_rejects_unknown_sessions()
  {
    McpSchema.CallToolResult result = call("collect_instance", Map.of("session_id", "nope"));
    assertTrue(result.isError());
  }

  @Test void list_sessions_shows_mode_and_submission_state()
  {
    call("show_template", Map.of("template", TEMPLATE_JSON));
    McpSchema.CallToolResult result = call("list_sessions", Map.of());
    assertFalse(result.isError(), text(result));
    assertTrue(text(result).contains("VIEW_TEMPLATE"), text(result));
  }

  // ---------------------------------------------------------------- helpers

  private McpSchema.CallToolResult call(String toolName, Map<String, Object> args)
  {
    for (CeeTools.RegisteredTool registered : tools.all())
      if (registered.tool().name().equals(toolName))
        return registered.handler().apply(null, new McpSchema.CallToolRequest(toolName, args));
    throw new AssertionError("no tool " + toolName);
  }

  private static String text(McpSchema.CallToolResult result)
  {
    return ((McpSchema.TextContent) result.content().get(0)).text();
  }

  private static String sessionIdIn(String text)
  {
    Matcher matcher = Pattern.compile("session ([0-9a-f-]{36})").matcher(text);
    return matcher.find() ? matcher.group(1) : null;
  }

  private Session awaitSingleSession()
  {
    long deadline = System.currentTimeMillis() + 5000;
    while (System.currentTimeMillis() < deadline) {
      var all = sessions.all();
      if (!all.isEmpty())
        return all.get(0);
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(e);
      }
    }
    throw new AssertionError("fill_instance never created its session");
  }

  private static HttpResponse<String> get(String url) throws Exception
  {
    return HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> post(String url, String body) throws Exception
  {
    return HTTP.send(HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
