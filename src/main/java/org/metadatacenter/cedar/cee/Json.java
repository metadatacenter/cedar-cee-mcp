package org.metadatacenter.cedar.cee;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.metadatacenter.artifacts.model.reader.ArtifactSourceParser;
import org.metadatacenter.artifacts.model.reader.YamlArtifactReader;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;

import java.io.IOException;
import java.util.LinkedHashMap;

/**
 * Parse/serialize helpers for artifacts crossing into the CEE. The CEE consumes CEDAR JSON, so an
 * artifact ends up as JSON before it is rendered — but a caller may supply its compact, read-only
 * YAML form (the JSON is large enough that handing it to an LLM is impractical), so
 * {@link #toObject} accepts either — YAML is read into the artifact model with
 * {@code cedar-artifact-library} and rendered to JSON. The populated instance still comes back
 * from the CEE as JSON-LD, exactly as the editor produced it.
 */
final class Json
{
  private static final ObjectMapper JACKSON = new ObjectMapper();
  // Compact-mode reader: accepts the lean authoring YAML (an absent modelVersion defaults).
  private static final YamlArtifactReader YAML_READER = new YamlArtifactReader(true);
  private static final JsonArtifactRenderer JSON_RENDERER = new JsonArtifactRenderer();

  private Json() {}

  /**
   * Parse an artifact — YAML or JSON — into a CEDAR JSON {@code ObjectNode}. JSON is
   * parsed as-is; YAML is read into the artifact model and re-rendered to JSON, with the kind
   * taken from the YAML {@code type:} discriminator (anything that isn't template / element /
   * instance / element-instance is a field kind).
   */
  static ObjectNode toObject(String text)
  {
    if (looksLikeJson(text))
      return asObject(text);

    LinkedHashMap<String, Object> map = parseYamlMap(text);
    String type = map.get("type") == null ? "" : String.valueOf(map.get("type"));
    return switch (type) {
      case "template" -> JSON_RENDERER.renderTemplateSchemaArtifact(YAML_READER.readTemplateSchemaArtifact(map));
      case "element" -> JSON_RENDERER.renderElementSchemaArtifact(YAML_READER.readElementSchemaArtifact(map));
      case "instance" -> JSON_RENDERER.renderTemplateInstanceArtifact(YAML_READER.readTemplateInstanceArtifact(map));
      case "element-instance" -> JSON_RENDERER.renderElementInstanceArtifact(YAML_READER.readElementInstanceArtifact(map));
      default -> JSON_RENDERER.renderFieldSchemaArtifact(YAML_READER.readFieldSchemaArtifact(map));
    };
  }

  /** Whether the text plausibly starts a JSON object (vs YAML); used to pick the parse path. */
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

  /**
   * Parse a YAML document with the library's parser, which the CEDAR server also reads YAML with.
   * It resolves YAML 1.2 core scalars, keeps date-like values as strings, and refuses duplicate
   * keys, unknown tags and a top level that is not a mapping. The mapping it returns goes to the
   * reader unchanged, because it also holds the text each unquoted number and boolean was written
   * as, and an instance value keeps that text.
   */
  private static LinkedHashMap<String, Object> parseYamlMap(String yamlText)
  {
    try {
      return ArtifactSourceParser.yaml(yamlText);
    } catch (IOException e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
  }
}
