package org.metadatacenter.cedar.cee;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

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
   * An unquoted instance value reaches the CEE as the string it was written as. The YAML 1.2 core
   * schema reads 007 and 010 as integers, 1e3 as a float and True as a boolean, but a field's value
   * is a string, so the CEE receives the source text of each. A ZIP code of 007 is not 7.
   */
  @Test void an_unquoted_instance_value_keeps_the_text_it_was_written_as()
  {
    for (String spelling : List.of("007", "010", "0x1F", "0o17", "1e3", "+1", ".5", "-0", "True", "FALSE",
        "2026-01-02")) {
      ObjectNode json = Json.toObject(instanceWithValue(spelling));
      assertEquals(TextNode.valueOf(spelling), json.path("Answer").path("@value"), spelling);
    }
  }
}
