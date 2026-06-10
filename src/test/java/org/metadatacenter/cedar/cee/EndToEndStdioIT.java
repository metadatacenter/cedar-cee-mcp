package org.metadatacenter.cedar.cee;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end deployment test: spawns the <em>shaded jar</em> as a subprocess, speaks real
 * newline-delimited JSON-RPC over its stdio, and exercises the session web server over real HTTP
 * from outside the process. This is the tier that catches what in-process tests cannot: shading
 * and classpath problems, resources missing from the jar (the host page), stdio framing, and tool
 * registration.
 *
 * <p>No browser opens during the run: the subprocess is started with a {@code PATH} containing
 * only no-op {@code open} / {@code xdg-open} shims, which is the environment the JVM's
 * {@code ProcessBuilder} resolves those commands against.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
final class EndToEndStdioIT
{
  private static final ObjectMapper JACKSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final long READ_DEADLINE_MILLIS = 30_000;

  private static final String TEMPLATE_JSON =
      "{\"@type\": \"https://schema.metadatacenter.org/core/Template\", \"schema:name\": \"IT Template\"}";
  private static final String INSTANCE_JSON =
      "{\"schema:isBasedOn\": \"https://repo.metadatacenter.org/templates/123\", "
          + "\"Patient Name\": {\"@value\": \"Alice\"}}";

  private static Process server;
  private static BufferedWriter toServer;
  private static BufferedReader fromServer;
  private static int nextId = 1;

  @BeforeAll static void startShadedJar() throws IOException
  {
    Path jar;
    try (Stream<Path> target = Files.list(Path.of("target"))) {
      jar = target.filter(p -> p.getFileName().toString().endsWith("-all.jar")).findFirst()
          .orElseThrow(() -> new IllegalStateException(
              "shaded jar not found in target/ — the IT runs after package (mvn verify)"));
    }

    // A PATH with only no-op browser-opener shims, so BrowserOpener cannot open anything.
    Path shims = Files.createTempDirectory("cee-it-shims");
    for (String name : new String[] {"open", "xdg-open"}) {
      Path shim = shims.resolve(name);
      Files.writeString(shim, "#!/bin/sh\nexit 0\n");
      Files.setPosixFilePermissions(shim, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    ProcessBuilder builder = new ProcessBuilder(javaBin, "-jar", jar.toAbsolutePath().toString());
    builder.environment().put("PATH", shims.toAbsolutePath().toString());
    builder.redirectErrorStream(false);
    builder.redirectError(ProcessBuilder.Redirect.DISCARD);
    server = builder.start();
    toServer = new BufferedWriter(new OutputStreamWriter(server.getOutputStream(), StandardCharsets.UTF_8));
    fromServer = new BufferedReader(new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8));

    JsonNode initialized = request("initialize", Map.of(
        "protocolVersion", "2024-11-05",
        "capabilities", Map.of(),
        "clientInfo", Map.of("name", "it", "version", "0")));
    assertEquals("cedar-cee-mcp", initialized.at("/result/serverInfo/name").asText());
    notify("notifications/initialized");
  }

  @AfterAll static void stopShadedJar()
  {
    if (server != null)
      server.destroyForcibly();
  }

  @Test @Order(1) void lists_all_six_tools() throws IOException
  {
    JsonNode response = request("tools/list", Map.of());
    var names = new java.util.ArrayList<String>();
    response.at("/result/tools").forEach(tool -> names.add(tool.get("name").asText()));
    for (String expected : new String[] {"ping", "show_template", "show_instance", "fill_instance",
        "collect_instance", "list_sessions"})
      assertTrue(names.contains(expected), "missing " + expected + "; got " + names);
    assertEquals(6, names.size(), names.toString());
  }

  @Test @Order(2) void ping_round_trips() throws IOException
  {
    assertEquals("pong: it", toolText(call("ping", Map.of("message", "it"))));
  }

  @Test @Order(3) void shaded_jar_serves_the_host_page_and_session_data() throws Exception
  {
    String resultText = toolText(call("show_template", Map.of("template", TEMPLATE_JSON)));
    String url = urlIn(resultText);
    assertNotNull(url, resultText);

    // The host page must have survived shading into the jar, and must carry the CEE machinery.
    HttpResponse<String> page = get(url);
    assertEquals(200, page.statusCode());
    assertTrue(page.body().contains("<cedar-embeddable-editor>"), "host page carries the CEE tag");
    assertTrue(page.body().contains("cdn.jsdelivr.net/npm/cedar-embeddable-editor@"),
        "host page references the pinned CEE bundle");

    HttpResponse<String> data = get(url + "/data");
    assertEquals(200, data.statusCode());
    JsonNode dataJson = JACKSON.readTree(data.body());
    assertTrue(dataJson.at("/config/readOnlyMode").asBoolean());
    assertEquals("IT Template", dataJson.at("/templateObject/schema:name").asText());

    String base = url.substring(0, url.indexOf("/s/"));
    assertEquals(200, get(base + "/health").statusCode());
  }

  @Test @Order(4) void fill_submit_collect_works_across_the_process_boundary() throws Exception
  {
    String timedOut = toolText(call("fill_instance",
        Map.of("template", TEMPLATE_JSON, "timeout_seconds", 1)));
    String url = urlIn(timedOut);
    String sessionId = sessionIdIn(timedOut);
    assertNotNull(url, timedOut);
    assertNotNull(sessionId, timedOut);

    HttpResponse<String> submit = HTTP.send(HttpRequest.newBuilder(URI.create(url + "/submit"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(INSTANCE_JSON)).build(),
        HttpResponse.BodyHandlers.ofString());
    assertEquals(204, submit.statusCode());

    String collected = toolText(call("collect_instance", Map.of("session_id", sessionId)));
    assertTrue(collected.contains("Alice"), collected);
  }

  // ---------------------------------------------------------------- JSON-RPC plumbing

  private static JsonNode call(String toolName, Map<String, Object> arguments) throws IOException
  {
    return request("tools/call", Map.of("name", toolName, "arguments", arguments));
  }

  private static String toolText(JsonNode response)
  {
    return response.at("/result/content/0/text").asText();
  }

  private static JsonNode request(String method, Map<String, Object> params) throws IOException
  {
    int id = nextId++;
    send(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
    long deadline = System.currentTimeMillis() + READ_DEADLINE_MILLIS;
    while (System.currentTimeMillis() < deadline) {
      String line = fromServer.readLine();
      if (line == null)
        throw new IllegalStateException("server closed stdout before answering " + method);
      if (line.isBlank())
        continue;
      JsonNode message = JACKSON.readTree(line);
      if (message.path("id").asInt(-1) == id)
        return message;
      // Skip notifications and responses to other requests.
    }
    throw new IllegalStateException("no response to " + method + " within " + READ_DEADLINE_MILLIS + " ms");
  }

  private static void notify(String method) throws IOException
  {
    send(Map.of("jsonrpc", "2.0", "method", method));
  }

  private static void send(Map<String, Object> message) throws IOException
  {
    toServer.write(JACKSON.writeValueAsString(message));
    toServer.write("\n");
    toServer.flush();
  }

  private static String urlIn(String text)
  {
    Matcher matcher = Pattern.compile("http://127\\.0\\.0\\.1:\\d+/s/[0-9a-f-]{36}").matcher(text);
    return matcher.find() ? matcher.group() : null;
  }

  private static String sessionIdIn(String text)
  {
    Matcher matcher = Pattern.compile("session ([0-9a-f-]{36})").matcher(text);
    return matcher.find() ? matcher.group(1) : null;
  }

  private static HttpResponse<String> get(String url) throws Exception
  {
    return HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
