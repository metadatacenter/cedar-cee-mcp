package org.metadatacenter.cedar.cee;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JSON parse/serialize helpers. This server hands artifact JSON through byte-for-byte — templates
 * and instances go to the CEE exactly as supplied, and the populated instance comes back exactly
 * as the CEE produced it. There is deliberately no artifact parsing, conversion, or validation
 * here (and no dependency on {@code cedar-artifact-library}); YAML ↔ JSON translation belongs to
 * {@code cedar-artifact-mcp}. See DESIGN.md.
 */
final class Json
{
  private static final ObjectMapper JACKSON = new ObjectMapper();

  private Json() {}

  /** Whether the text plausibly starts a JSON object (used only to give YAML input a helpful
   *  redirect instead of a raw parse error). */
  static boolean looksLikeJson(String text)
  {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.isWhitespace(c)) continue;
      return c == '{';
    }
    return false;
  }

  static ObjectNode asObject(String text)
  {
    try {
      JsonNode node = JACKSON.readTree(text);
      if (!(node instanceof ObjectNode objectNode))
        throw new IllegalArgumentException("expected a JSON object, got " + node.getNodeType());
      return objectNode;
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new RuntimeException("JSON parse failed: " + e.getOriginalMessage(), e);
    }
  }

  static String compact(JsonNode node)
  {
    try {
      return JACKSON.writeValueAsString(node);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new RuntimeException("JSON serialize failed: " + e.getMessage(), e);
    }
  }

  static String pretty(JsonNode node)
  {
    try {
      return JACKSON.writerWithDefaultPrettyPrinter().writeValueAsString(node);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new RuntimeException("JSON serialize failed: " + e.getMessage(), e);
    }
  }
}
