package org.metadatacenter.cedar.cee;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP server that displays CEDAR templates and instances in the user's browser via the CEDAR
 * Embeddable Editor (CEE), and collects user-populated instances back into the conversation. The
 * form-presentation counterpart to {@code cedar-artifact-mcp} (authoring) and
 * {@code cedar-artifact-rest-mcp} (persistence).
 *
 * <p>The display surface is a loopback-only web server ({@link CeeWebServer}) hosting a single
 * page that loads the CEE web-component bundle; tools ({@link CeeTools}) create sessions, open
 * browser tabs, and wait for / collect submitted instances. See {@code DESIGN.md} and
 * {@code ROADMAP.md}.
 */
public final class CedarCeeMcpServer
{
  private static final String SERVER_NAME = "cedar-cee-mcp";
  private static final String SERVER_VERSION = "0.1.0";

  private CedarCeeMcpServer() {}

  public static void main(String[] args) throws InterruptedException
  {
    SessionStore sessions = new SessionStore();
    CeeWebServer web = new CeeWebServer(sessions);
    CeeTools tools = new CeeTools(sessions, web, new BrowserOpener());

    var spec = McpServer.sync(new StdioServerTransportProvider(McpJsonDefaults.getMapper()))
        .serverInfo(SERVER_NAME, SERVER_VERSION)
        .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
        .toolCall(pingTool(), CedarCeeMcpServer::pingHandler);

    for (CeeTools.RegisteredTool registered : tools.all())
      spec.toolCall(registered.tool(), registered.handler());

    McpSyncServer server = spec.build();

    // Stdio transport reads from System.in on a background thread; keep main alive until the JVM
    // is interrupted (typically when the parent process closes stdin).
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      web.stop();
      server.close();
    }));
    Thread.currentThread().join();
  }

  // ---------------------------------------------------------------- ping

  private static McpSchema.Tool pingTool()
  {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("message", Map.of("type", "string", "description", "Arbitrary string to echo back."));

    McpSchema.JsonSchema schema = new McpSchema.JsonSchema(
        "object", properties, List.of("message"), Boolean.FALSE, null, null);

    return McpSchema.Tool.builder()
        .name("ping")
        .title("ping")
        .description("Echoes the supplied message. Verifies the MCP server is reachable. Does not "
            + "open a browser or start the web server.")
        .inputSchema(schema)
        .build();
  }

  private static McpSchema.CallToolResult pingHandler(
      McpSyncServerExchange exchange, McpSchema.CallToolRequest request)
  {
    Object raw = request.arguments() == null ? null : request.arguments().get("message");
    String message = raw == null ? "" : raw.toString();

    return McpSchema.CallToolResult.builder()
        .content(List.of(new McpSchema.TextContent(null, "pong: " + message)))
        .isError(false)
        .build();
  }
}
