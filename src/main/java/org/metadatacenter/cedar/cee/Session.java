package org.metadatacenter.cedar.cee;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.metadatacenter.model.ModelNodeNames.JSON_LD_ID;
import static org.metadatacenter.model.ModelNodeNames.SCHEMA_IS_BASED_ON;

/**
 * One browser-rendered CEE session: the template (always present), an optional instance, the
 * display mode, and — for {@link Mode#FILL} — the future that completes when the user submits the
 * populated instance from the browser.
 *
 * <p>Sessions live in memory only and die with the server. The id is an unguessable UUID; combined
 * with the loopback-only web server that is the entire access-control story (deliberately — see
 * DESIGN.md).
 */
final class Session
{
  enum Mode
  {
    /** Read-only render of a template (empty form, no instance). */
    VIEW_TEMPLATE,
    /** Read-only render of an instance against its template. */
    VIEW_INSTANCE,
    /** Editable form; the user populates an instance and submits it back. */
    FILL
  }

  final String id;
  final Mode mode;
  final ObjectNode templateJson;
  final ObjectNode instanceJson; // null unless VIEW_INSTANCE, or FILL with a pre-filled instance
  final String language; // UI language for the editor (ISO code, e.g. "en")
  final Instant createdAt = Instant.now();
  private final CompletableFuture<ObjectNode> submitted = new CompletableFuture<>();

  Session(String id, Mode mode, ObjectNode templateJson, ObjectNode instanceJson, String language)
  {
    this.id = id;
    this.mode = mode;
    this.templateJson = templateJson;
    this.instanceJson = instanceJson;
    this.language = language;
  }

  /** Record the instance the user submitted from the browser. Later submissions win — the user may
   *  click Done, notice a mistake, fix it, and click Done again before collecting. */
  void submit(ObjectNode instance)
  {
    if (!submitted.complete(instance))
      latest = instance;
  }

  private volatile ObjectNode latest; // a re-submission after the first

  /** The most recently submitted instance, if any. */
  Optional<ObjectNode> submittedInstance()
  {
    ObjectNode resubmitted = latest;
    if (resubmitted != null)
      return Optional.of(resubmitted);
    return Optional.ofNullable(submitted.getNow(null));
  }

  /** The future the blocking fill tool waits on; completes with the first submission. */
  CompletableFuture<ObjectNode> firstSubmission()
  {
    return submitted;
  }

  /**
   * Why a submission is not an instance that can be returned, or empty when it is one. An instance
   * names the template it is based on, so a submission that names none is not one. The editor sends
   * such a submission for a template without an {@code @id}, and an empty object for a template or
   * instance it refused.
   */
  Optional<String> refusal(ObjectNode submission)
  {
    if (statesText(submission, SCHEMA_IS_BASED_ON))
      return Optional.empty();
    if (!statesText(templateJson, JSON_LD_ID))
      return Optional.of("The template has no @id, so an instance of it cannot name the template it is "
          + "based on (" + SCHEMA_IS_BASED_ON + "), and no instance can be written. This is typical of a "
          + "template that has never been saved. Save the template, then open the form again with the "
          + "saved template, which carries the @id the repository assigned.");
    return Optional.of("What the editor sent names no template (" + SCHEMA_IS_BASED_ON + "), so it is not "
        + "an instance. The editor sends an empty object when it refused the template or instance it was "
        + "handed, and the browser console says why.");
  }

  /** Whether {@code node} holds a string at {@code key} that is not blank. */
  private static boolean statesText(ObjectNode node, String key)
  {
    JsonNode value = node.get(key);
    return value != null && value.isTextual() && !value.asText().isBlank();
  }
}
