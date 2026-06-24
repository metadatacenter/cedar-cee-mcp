package org.metadatacenter.cedar.cee;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;

/**
 * The CEE display / population tools. Each tool stores a session, returns the session's localhost
 * URL, and (best-effort) opens it in the user's browser:
 *
 * <ul>
 *   <li>{@code show_template} — read-only rendering of a template;</li>
 *   <li>{@code show_instance} — read-only rendering of an instance against its template;</li>
 *   <li>{@code fill_instance} — editable form; waits for the user to press Done (bounded), then
 *       returns the populated instance as JSON-LD, exactly as the CEE produced it;</li>
 *   <li>{@code collect_instance} — fetches a submitted instance after the fact (the non-blocking
 *       half of the fill story);</li>
 *   <li>{@code list_sessions} — what is currently showing.</li>
 * </ul>
 *
 * <p>Artifacts should be supplied as the compact YAML exchange form; CEDAR JSON is also accepted.
 * YAML is converted to JSON via {@code cedar-artifact-library} before it reaches the CEE, which
 * consumes JSON internally. The populated instance comes back from the CEE as JSON-LD, exactly as
 * the editor produced it.
 */
final class CeeTools
{
  /** A built tool paired with its handler, ready to hand to {@code McpServer...toolCall}. */
  record RegisteredTool(
      McpSchema.Tool tool,
      BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler) {}

  static final int DEFAULT_FILL_TIMEOUT_SECONDS = 120;
  static final int MAX_FILL_TIMEOUT_SECONDS = 3600;

  private final SessionStore sessions;
  private final CeeWebServer web;
  private final BrowserOpener browser;

  CeeTools(SessionStore sessions, CeeWebServer web, BrowserOpener browser)
  {
    this.sessions = sessions;
    this.web = web;
    this.browser = browser;
  }

  List<RegisteredTool> all()
  {
    return List.of(showTemplateTool(), showInstanceTool(), fillInstanceTool(),
        collectInstanceTool(), listSessionsTool());
  }

  // ---------------------------------------------------------------- show_template

  private RegisteredTool showTemplateTool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("template", templateProperty());
    properties.put("language", languageProperty());

    McpSchema.Tool tool = McpSchema.Tool.builder()
        .name("show_template")
        .title("Display a CEDAR template in the browser (read-only)")
        .description("Renders a CEDAR template as a read-only form in the user's browser via the "
            + "CEDAR Embeddable Editor, so a human can inspect its structure, fields, and "
            + "constraints. Supply the template as YAML (the compact exchange form); CEDAR JSON "
            + "is also accepted. Returns the page URL; nothing is collected "
            + "back. Always show the user the URL in case the browser tab did not open "
            + "automatically.")
        .inputSchema(schema(properties, List.of("template")))
        .build();

    return new RegisteredTool(tool, (exchange, request) -> {
      Map<String, Object> args = args(request);
      ObjectNode templateJson;
      try {
        templateJson = artifactJson(args, "template");
      } catch (RuntimeException e) {
        return error("could not read the template: " + e.getMessage());
      }
      Session session = sessions.create(Session.Mode.VIEW_TEMPLATE, templateJson, null,
          false, languageArg(args));
      return opened(session, "read-only template view");
    });
  }

  // ---------------------------------------------------------------- show_instance

  private RegisteredTool showInstanceTool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("template", templateProperty());
    properties.put("instance", Map.of("type", "string", "description",
        "The CEDAR template instance to display, as YAML (the compact exchange form); CEDAR "
            + "JSON-LD is also accepted."));
    properties.put("hide_empty_fields", Map.of("type", "boolean", "description",
        "Omit template fields the instance has no value for, showing only the populated ones. "
            + "Defaults to false: the full template structure shows, with empty fields blank."));
    properties.put("language", languageProperty());

    McpSchema.Tool tool = McpSchema.Tool.builder()
        .name("show_instance")
        .title("Display a populated CEDAR instance in the browser (read-only)")
        .description("Renders a CEDAR template instance against its template as a read-only form "
            + "in the user's browser via the CEDAR Embeddable Editor. Takes both artifacts as "
            + "YAML (the compact exchange form); CEDAR JSON is also accepted. By default the "
            + "full template structure shows, with unpopulated fields blank; pass "
            + "hide_empty_fields:true to show only fields that hold a value. Returns the page "
            + "URL; nothing is collected back. Always show the user the URL in case the browser "
            + "tab did not open automatically.")
        .inputSchema(schema(properties, List.of("template", "instance")))
        .build();

    return new RegisteredTool(tool, (exchange, request) -> {
      Map<String, Object> args = args(request);
      ObjectNode templateJson;
      ObjectNode instanceJson;
      try {
        templateJson = artifactJson(args, "template");
        instanceJson = artifactJson(args, "instance");
      } catch (RuntimeException e) {
        return error("could not read the artifacts: " + e.getMessage());
      }
      Session session = sessions.create(Session.Mode.VIEW_INSTANCE, templateJson, instanceJson,
          boolArg(args, "hide_empty_fields", false), languageArg(args));
      return opened(session, "read-only instance view");
    });
  }

  // ---------------------------------------------------------------- fill_instance

  private RegisteredTool fillInstanceTool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("template", templateProperty());
    properties.put("instance", Map.of("type", "string", "description",
        "Optional instance to pre-fill the form with, as YAML; CEDAR JSON-LD is also accepted. Must be "
            + "a complete instance (every template field present) for the editor to render it — "
            + "cedar-artifact-mcp's instance_artifact_to_json, given the schema, produces exactly "
            + "that. Omit to start from an empty form."));
    properties.put("timeout_seconds", Map.of("type", "integer", "description",
        "How long this call waits for the user to press Done before returning control to the "
            + "conversation (default " + DEFAULT_FILL_TIMEOUT_SECONDS + ", max "
            + MAX_FILL_TIMEOUT_SECONDS + "). Not a deadline on the user — the form stays open "
            + "indefinitely; collect the result later with collect_instance."));
    properties.put("language", languageProperty());

    McpSchema.Tool tool = McpSchema.Tool.builder()
        .name("fill_instance")
        .title("Open an editable metadata form and wait for the user to fill it")
        .description("Opens an editable CEDAR metadata form in the user's browser via the CEDAR "
            + "Embeddable Editor — with ontology-backed autocomplete for controlled-term fields — "
            + "and waits up to timeout_seconds for the user to press the form's Done button. If "
            + "they do, the populated instance comes back immediately as JSON-LD, exactly as the "
            + "editor produced it. If they are still working, the call returns control with the "
            + "session id — the form stays open indefinitely and nothing the user typed is lost; "
            + "call collect_instance with that id once the user says they are done. Tell the "
            + "user a form has been opened and that they should press Done when finished. Takes "
            + "the template as YAML (the compact exchange form); CEDAR JSON is also accepted.")
        .inputSchema(schema(properties, List.of("template")))
        .build();

    return new RegisteredTool(tool, (exchange, request) -> {
      Map<String, Object> args = args(request);
      ObjectNode templateJson;
      ObjectNode instanceJson = null;
      try {
        templateJson = artifactJson(args, "template");
        String instanceText = str(args, "instance");
        if (instanceText != null && !instanceText.isBlank())
          instanceJson = artifactJson(args, "instance");
      } catch (RuntimeException e) {
        return error("could not read the artifacts: " + e.getMessage());
      }

      Session session = sessions.create(Session.Mode.FILL, templateJson, instanceJson,
          false, languageArg(args));
      String url = web.sessionUrl(session);
      boolean openedInBrowser = browser.open(url);

      int timeoutSeconds = intArg(args, "timeout_seconds", DEFAULT_FILL_TIMEOUT_SECONDS);
      timeoutSeconds = Math.max(1, Math.min(timeoutSeconds, MAX_FILL_TIMEOUT_SECONDS));

      try {
        ObjectNode submitted = session.firstSubmission().get(timeoutSeconds, TimeUnit.SECONDS);
        return text(instanceResultText(session, submitted));
      } catch (TimeoutException e) {
        return text("The form is open at " + url + " (session " + session.id + ") but the user "
            + "has not pressed Done yet"
            + (openedInBrowser ? "" : " — and the browser could not be opened automatically, so "
                + "share the URL with the user") + ". The form stays open indefinitely and "
            + "nothing is lost: when the user says they have finished, call collect_instance "
            + "with session_id " + session.id + ".");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return error("interrupted while waiting for the form submission");
      } catch (ExecutionException e) {
        return error("waiting for the form submission failed: " + e.getCause().getMessage());
      }
    });
  }

  // ---------------------------------------------------------------- collect_instance

  private RegisteredTool collectInstanceTool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("session_id", Map.of("type", "string", "description",
        "The session id returned by fill_instance."));

    McpSchema.Tool tool = McpSchema.Tool.builder()
        .name("collect_instance")
        .title("Collect the instance the user submitted from an open form")
        .description("Fetches the populated instance from a fill_instance session after the fact "
            + "— the non-blocking half of the fill story. Returns the instance as JSON-LD, "
            + "exactly as the editor produced it, if the user has pressed Done, or a "
            + "not-yet-submitted notice otherwise. If the user pressed Done more than once, the "
            + "latest submission wins.")
        .inputSchema(schema(properties, List.of("session_id")))
        .build();

    return new RegisteredTool(tool, (exchange, request) -> {
      Map<String, Object> args = args(request);
      String sessionId = str(args, "session_id");
      if (sessionId == null || sessionId.isBlank())
        return error("session_id is required");
      Optional<Session> found = sessions.get(sessionId.trim());
      if (found.isEmpty())
        return error("unknown session " + sessionId + " — sessions do not survive a server "
            + "restart; open a new form with fill_instance");
      Session session = found.get();
      if (session.mode != Session.Mode.FILL)
        return error("session " + sessionId + " is a read-only view; nothing to collect");
      Optional<ObjectNode> submitted = session.submittedInstance();
      if (submitted.isEmpty())
        return text("The user has not pressed Done yet (session " + sessionId + ", open at "
            + web.sessionUrl(session) + "). Ask them to finish the form, then collect again.");
      return text(instanceResultText(session, submitted.get()));
    });
  }

  // ---------------------------------------------------------------- list_sessions

  private RegisteredTool listSessionsTool()
  {
    McpSchema.Tool tool = McpSchema.Tool.builder()
        .name("list_sessions")
        .title("List the CEE sessions this server is showing")
        .description("Lists the browser sessions this server has opened: id, mode, page URL, age, "
            + "and — for fill sessions — whether the user has submitted yet.")
        .inputSchema(schema(new LinkedHashMap<>(), List.of()))
        .build();

    return new RegisteredTool(tool, (exchange, request) -> {
      List<Session> all = sessions.all();
      if (all.isEmpty())
        return text("No sessions yet.");
      StringBuilder out = new StringBuilder();
      for (Session session : all) {
        out.append(session.id)
            .append("  ").append(session.mode.name())
            .append("  ").append(web.sessionUrl(session))
            .append("  age=").append(Duration.between(session.createdAt, Instant.now()).toSeconds())
            .append("s");
        if (session.mode == Session.Mode.FILL)
          out.append("  submitted=").append(session.submittedInstance().isPresent());
        out.append('\n');
      }
      return text(out.toString().stripTrailing());
    });
  }

  // ---------------------------------------------------------------- shared

  private McpSchema.CallToolResult opened(Session session, String what)
  {
    String url = web.sessionUrl(session);
    boolean openedInBrowser = browser.open(url);
    return text("Opened a " + what + " at " + url + " (session " + session.id + ")."
        + (openedInBrowser ? "" : " The browser could not be opened automatically — give the user "
            + "the URL to open themselves."));
  }

  /**
   * The fill/collect success payload: the populated instance as JSON-LD, byte-for-byte as the CEE
   * produced it. This server does no conversion — translation to YAML, if wanted, is
   * cedar-artifact-mcp's job.
   */
  private static String instanceResultText(Session session, ObjectNode submitted)
  {
    return "The user submitted the form (session " + session.id + "). Populated instance, as "
        + "JSON-LD exactly as the editor produced it (cedar-artifact-mcp's "
        + "instance_artifact_to_yaml converts it to compact YAML if desired):\n\n" + Json.pretty(submitted);
  }

  private static Map<String, Object> templateProperty()
  {
    return Map.of("type", "string", "description",
        "The CEDAR template, as YAML (the compact exchange form); CEDAR JSON is also accepted (the "
            + "JSON Schema form the CEE renders).");
  }

  /** Parse a required artifact argument — YAML, or CEDAR JSON — into the JSON
   *  object the CEE renders (YAML is converted via cedar-artifact-library). */
  private static ObjectNode artifactJson(Map<String, Object> args, String key)
  {
    return Json.toObject(required(args, key));
  }

  private static Map<String, Object> languageProperty()
  {
    return Map.of("type", "string", "description",
        "UI language for the editor, as an ISO language code (e.g. \"en\", \"de\", \"hu\"). "
            + "Defaults to English; untranslated strings fall back to English.");
  }

  private static String languageArg(Map<String, Object> args)
  {
    String language = str(args, "language");
    return (language == null || language.isBlank()) ? "en" : language.trim();
  }

  private static McpSchema.JsonSchema schema(Map<String, Object> properties, List<String> required)
  {
    return new McpSchema.JsonSchema("object", properties, required, Boolean.FALSE, null, null);
  }

  private static Map<String, Object> args(McpSchema.CallToolRequest request)
  {
    return request.arguments() == null ? Map.of() : request.arguments();
  }

  private static String str(Map<String, Object> args, String key)
  {
    Object raw = args.get(key);
    return raw == null ? null : raw.toString();
  }

  private static String required(Map<String, Object> args, String key)
  {
    String value = str(args, key);
    if (value == null || value.isBlank())
      throw new IllegalArgumentException(key + " is required");
    return value;
  }

  private static boolean boolArg(Map<String, Object> args, String key, boolean defaultValue)
  {
    Object raw = args.get(key);
    if (raw == null)
      return defaultValue;
    if (raw instanceof Boolean bool)
      return bool;
    return Boolean.parseBoolean(raw.toString().trim());
  }

  private static int intArg(Map<String, Object> args, String key, int defaultValue)
  {
    Object raw = args.get(key);
    if (raw == null)
      return defaultValue;
    if (raw instanceof Number number)
      return number.intValue();
    try {
      return Integer.parseInt(raw.toString().trim());
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  private static McpSchema.CallToolResult text(String message)
  {
    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, message)))
        .isError(false)
        .build();
  }

  private static McpSchema.CallToolResult error(String message)
  {
    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, message)))
        .isError(true)
        .build();
  }
}
