package org.metadatacenter.cedar.cee;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.metadatacenter.artifacts.model.reader.ArtifactSourceParser;
import org.metadatacenter.artifacts.model.reader.YamlArtifactReader;
import org.metadatacenter.artifacts.model.renderer.JsonArtifactRenderer;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An artifact supplied as YAML is read into the model and rendered as the CEDAR JSON the CEE
 * consumes. A parser's rules decide what an unquoted scalar means, so these cases pin which rules
 * apply.
 */
final class JsonTest
{
  private static String instanceWithValue(String spelling)
  {
    return "type: instance\nname: X\nisBasedOn: \"https://repo.metadatacenter.org/templates/abc\"\n"
        + "children:\n  Answer:\n    value: " + spelling + "\n";
  }

  /**
   * An unquoted instance value reaches the CEE as CEDAR's parser reads it. SnakeYAML's YAML 1.1
   * resolver took yes, on, no and off for booleans, 1_000 and 0b1010 for integers and 1:20 for the
   * sexagesimal 80. Under the YAML 1.2 core schema none of them is a number or a boolean.
   */
  @Test void an_unquoted_instance_value_is_resolved_as_yaml_1_2_core()
  {
    for (String spelling : List.of("yes", "on", "no", "off", "1_000", "0b1010", "1:20")) {
      ObjectNode json = Json.toObject(instanceWithValue(spelling));
      assertEquals(spelling, json.path("Answer").path("@value").asText(), spelling);
    }
  }

  /**
   * Every unquoted spelling reaches the CEE as the library's own parser and reader read it, so an
   * instance value keeps whatever source text the library keeps: 010 is the octal 8 to YAML 1.1 and
   * 10 to YAML 1.2, and a library that keeps the text keeps 010.
   */
  @Test void an_unquoted_instance_value_is_read_as_the_library_reads_it() throws IOException
  {
    YamlArtifactReader reader = new YamlArtifactReader(true);
    JsonArtifactRenderer renderer = new JsonArtifactRenderer();
    for (String spelling : List.of("007", "010", "0x1F", "0o17", "1e3", "+1", ".5", "-0", "True", "FALSE",
        "2026-01-02")) {
      String yaml = instanceWithValue(spelling);
      ObjectNode expected =
          renderer.renderTemplateInstanceArtifact(reader.readTemplateInstanceArtifact(ArtifactSourceParser.yaml(yaml)));
      assertEquals(expected, Json.toObject(yaml), spelling);
    }
  }
}
