package org.metadatacenter.cedar.cee;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.metadatacenter.artifacts.model.core.TemplateInstanceArtifact;
import org.metadatacenter.artifacts.model.reader.JsonArtifactReader;
import org.metadatacenter.artifacts.model.reader.YamlArtifactReader;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;
import org.metadatacenter.artifacts.model.tools.YamlSerializer;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Converts artifact bodies between the caller's format and what the CEE consumes and produces.
 * The CEE takes templates and instances in the canonical CEDAR <em>JSON Schema / JSON-LD</em>
 * form, and emits completed instances as JSON-LD; the surrounding MCP ecosystem speaks compact
 * YAML. So:
 *
 * <ul>
 *   <li><strong>inbound</strong> ({@link #templateToJson}, {@link #instanceToJson}): YAML or JSON
 *       (auto-detected) → canonical CEDAR JSON for the CEE, via {@code cedar-artifact-library};
 *       JSON input is passed through untouched;</li>
 *   <li><strong>outbound</strong> ({@link #instanceToCompactYaml}): the JSON-LD instance the CEE
 *       produced → compact YAML for the caller.</li>
 * </ul>
 */
final class ArtifactCodec
{
  private static final ObjectMapper JACKSON = new ObjectMapper();
  private static final JsonArtifactReader JSON_READER = new JsonArtifactReader();
  private static final JsonArtifactRenderer JSON_RENDERER = new JsonArtifactRenderer();
  private static final YamlArtifactReader YAML_READER = new YamlArtifactReader(true);

  private ArtifactCodec() {}

  /** Incoming template (YAML or JSON, auto-detected) → canonical CEDAR JSON object. */
  static ObjectNode templateToJson(String text)
  {
    if (looksLikeJson(text))
      return asObjectNode(text);
    return JSON_RENDERER.renderTemplateSchemaArtifact(
        YAML_READER.readTemplateSchemaArtifact(parseYamlMap(text)));
  }

  /** Incoming instance (YAML or JSON, auto-detected) → canonical CEDAR JSON-LD object. */
  static ObjectNode instanceToJson(String text)
  {
    if (looksLikeJson(text))
      return asObjectNode(text);
    return JSON_RENDERER.renderTemplateInstanceArtifact(
        YAML_READER.readTemplateInstanceArtifact(parseYamlMap(text)));
  }

  /** The CEE's completed JSON-LD instance → compact YAML for the caller. */
  static String instanceToCompactYaml(ObjectNode instanceJson)
  {
    TemplateInstanceArtifact instance = JSON_READER.readTemplateInstanceArtifact(instanceJson);
    return YamlSerializer.getYAML(instance, true, false);
  }

  static ObjectNode asObjectNode(String text)
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

  static String compactJson(JsonNode node)
  {
    try {
      return JACKSON.writeValueAsString(node);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new RuntimeException("JSON serialize failed: " + e.getMessage(), e);
    }
  }

  static String prettyJson(JsonNode node)
  {
    try {
      return JACKSON.writerWithDefaultPrettyPrinter().writeValueAsString(node);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new RuntimeException("JSON serialize failed: " + e.getMessage(), e);
    }
  }

  private static boolean looksLikeJson(String text)
  {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.isWhitespace(c)) continue;
      return c == '{';
    }
    return false;
  }

  @SuppressWarnings("unchecked")
  private static LinkedHashMap<String, Object> parseYamlMap(String text)
  {
    Object parsed = newYaml().load(text);
    if (!(parsed instanceof Map))
      throw new IllegalArgumentException("artifact YAML must be a mapping at the top level");
    LinkedHashMap<String, Object> map = new LinkedHashMap<>();
    ((Map<Object, Object>) parsed).forEach((k, v) -> map.put(String.valueOf(k), v));
    return map;
  }

  /**
   * A SnakeYAML instance whose resolver does not implicitly type date-like scalars as timestamps
   * (CEDAR keeps them as strings) — same configuration the artifact library's own tooling uses.
   */
  private static Yaml newYaml()
  {
    LoaderOptions loaderOptions = new LoaderOptions();
    DumperOptions dumperOptions = new DumperOptions();
    return new Yaml(new SafeConstructor(loaderOptions), new Representer(dumperOptions),
        dumperOptions, loaderOptions, new NoTimestampResolver());
  }

  private static final class NoTimestampResolver extends Resolver
  {
    @Override protected void addImplicitResolvers()
    {
      addImplicitResolver(Tag.BOOL, BOOL, "yYnNtTfFoO");
      addImplicitResolver(Tag.INT, INT, "-+0123456789");
      addImplicitResolver(Tag.FLOAT, FLOAT, "-+0123456789.");
      addImplicitResolver(Tag.MERGE, MERGE, "<");
      addImplicitResolver(Tag.NULL, NULL, "~nN\0");
      addImplicitResolver(Tag.NULL, EMPTY, null);
      // Tag.TIMESTAMP intentionally omitted: date-like scalars stay strings.
    }
  }
}
