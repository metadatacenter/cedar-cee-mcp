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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the codec, the session web server, and the tools end-to-end in process — the browser
 * is stubbed out and the CEE bundle is never fetched (these tests cover everything up to the
 * component boundary; rendering inside a real browser is the manual smoke test in the README).
 */
final class CeeMcpTest
{
  private static final ObjectMapper JACKSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  private static final String TEMPLATE_YAML = """
      type: template
      name: Patient Study
      children:
        - key: Patient Name
          type: text-field
          name: Patient Name
      """;

  private static final String INSTANCE_YAML = """
      type: instance
      name: Patient Study metadata
      isBasedOn: https://repo.metadatacenter.org/templates/123
      children:
        Patient Name:
          value: Alice
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

  // ---------------------------------------------------------------- codec

  @Test void template_yaml_converts_to_canonical_json()
  {
    ObjectNode json = ArtifactCodec.templateToJson(TEMPLATE_YAML);
    assertEquals("Patient Study", json.get("schema:name").asText());
    assertTrue(json.has("properties"), "canonical template JSON carries a JSON Schema properties node");
  }

  @Test void template_json_passes_through_untouched()
  {
    String json = "{\"schema:name\": \"As Is\", \"@type\": \"https://schema.metadatacenter.org/core/Template\"}";
    ObjectNode node = ArtifactCodec.templateToJson(json);
    assertEquals("As Is", node.get("schema:name").asText());
    assertFalse(node.has("properties"), "JSON input must not be round-tripped through the library");
  }

  @Test void instance_round_trips_yaml_to_json_to_compact_yaml()
  {
    ObjectNode instanceJson = ArtifactCodec.instanceToJson(INSTANCE_YAML);
    assertEquals("https://repo.metadatacenter.org/templates/123",
        instanceJson.get("schema:isBasedOn").asText());

    String yaml = ArtifactCodec.instanceToCompactYaml(instanceJson);
    assertTrue(yaml.contains("Alice"), "value survives the round trip; got:\n" + yaml);
    assertTrue(yaml.contains("isBasedOn"), "instance identity survives; got:\n" + yaml);
  }

  // ---------------------------------------------------------------- web server

  @Test void serves_host_page_and_session_data() throws Exception
  {
    Session session = sessions.create(Session.Mode.VIEW_TEMPLATE,
        ArtifactCodec.templateToJson(TEMPLATE_YAML), null);
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
    Session session = sessions.create(Session.Mode.FILL,
        ArtifactCodec.templateToJson(TEMPLATE_YAML), null);

    HttpResponse<String> data = get(web.sessionUrl(session) + "/data");
    ObjectNode config = (ObjectNode) JACKSON.readTree(data.body()).get("config");
    assertFalse(config.has("readOnlyMode"), "fill mode leaves the editor live");
    assertEquals(CeeWebServer.DEFAULT_TERMINOLOGY_URL,
        config.get("terminologyIntegratedSearchUrl").asText());
  }

  @Test void unknown_session_is_404() throws Exception
  {
    String base = web.ensureStarted();
    assertEquals(404, get(base + "/s/nope").statusCode());
  }

  @Test void submit_stores_the_instance_and_completes_the_future() throws Exception
  {
    Session session = sessions.create(Session.Mode.FILL,
        ArtifactCodec.templateToJson(TEMPLATE_YAML), null);
    String body = ArtifactCodec.compactJson(ArtifactCodec.instanceToJson(INSTANCE_YAML));

    HttpResponse<String> response = post(web.sessionUrl(session) + "/submit", body);
    assertEquals(204, response.statusCode());
    assertTrue(session.submittedInstance().isPresent());
    assertTrue(session.firstSubmission().isDone());
  }

  @Test void submit_to_a_read_only_session_is_rejected() throws Exception
  {
    Session session = sessions.create(Session.Mode.VIEW_TEMPLATE,
        ArtifactCodec.templateToJson(TEMPLATE_YAML), null);
    HttpResponse<String> response = post(web.sessionUrl(session) + "/submit", "{}");
    assertEquals(409, response.statusCode());
  }

  // ---------------------------------------------------------------- tools

  @Test void show_template_returns_the_session_url()
  {
    McpSchema.CallToolResult result = call("show_template", Map.of("template", TEMPLATE_YAML));
    assertFalse(result.isError(), text(result));
    assertTrue(text(result).contains("http://127.0.0.1:"), text(result));
  }

  @Test void show_instance_requires_both_artifacts()
  {
    McpSchema.CallToolResult result = call("show_instance", Map.of("template", TEMPLATE_YAML));
    assertTrue(result.isError());
    assertTrue(text(result).contains("instance"), text(result));
  }

  @Test void fill_instance_times_out_with_a_collect_hint()
  {
    McpSchema.CallToolResult result = call("fill_instance",
        Map.of("template", TEMPLATE_YAML, "timeout_seconds", 1));
    assertFalse(result.isError(), text(result));
    assertTrue(text(result).contains("collect_instance"), text(result));
    assertNotNull(sessionIdIn(text(result)));
  }

  @Test void fill_instance_returns_yaml_once_the_user_submits()
  {
    // Simulate the user: submit the instance over HTTP shortly after the blocking call starts.
    CompletableFuture<McpSchema.CallToolResult> pending = CompletableFuture.supplyAsync(
        () -> call("fill_instance", Map.of("template", TEMPLATE_YAML, "timeout_seconds", 30)));

    Session session = awaitSingleSession();
    String body = ArtifactCodec.compactJson(ArtifactCodec.instanceToJson(INSTANCE_YAML));
    try {
      assertEquals(204, post(web.sessionUrl(session) + "/submit", body).statusCode());
      McpSchema.CallToolResult result = pending.get();
      assertFalse(result.isError(), text(result));
      assertTrue(text(result).contains("Alice"), text(result));
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  @Test void collect_instance_reports_pending_then_returns_the_submission() throws Exception
  {
    Session session = sessions.create(Session.Mode.FILL,
        ArtifactCodec.templateToJson(TEMPLATE_YAML), null);

    McpSchema.CallToolResult before = call("collect_instance", Map.of("session_id", session.id));
    assertFalse(before.isError(), text(before));
    assertTrue(text(before).contains("not pressed Done"), text(before));

    String body = ArtifactCodec.compactJson(ArtifactCodec.instanceToJson(INSTANCE_YAML));
    post(web.sessionUrl(session) + "/submit", body);

    McpSchema.CallToolResult after = call("collect_instance", Map.of("session_id", session.id));
    assertFalse(after.isError(), text(after));
    assertTrue(text(after).contains("Alice"), text(after));
  }

  @Test void collect_instance_rejects_unknown_sessions()
  {
    McpSchema.CallToolResult result = call("collect_instance", Map.of("session_id", "nope"));
    assertTrue(result.isError());
  }

  @Test void list_sessions_shows_mode_and_submission_state()
  {
    call("show_template", Map.of("template", TEMPLATE_YAML));
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
