package org.metadatacenter.cedar.cee;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

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
  final boolean hideEmptyFields; // VIEW_INSTANCE only: omit unpopulated template fields from the view
  final String language; // UI language for the editor (ISO code, e.g. "en")
  final Instant createdAt = Instant.now();
  private final CompletableFuture<ObjectNode> submitted = new CompletableFuture<>();

  Session(String id, Mode mode, ObjectNode templateJson, ObjectNode instanceJson,
      boolean hideEmptyFields, String language)
  {
    this.id = id;
    this.mode = mode;
    this.templateJson = templateJson;
    this.instanceJson = instanceJson;
    this.hideEmptyFields = hideEmptyFields;
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
}
