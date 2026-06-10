package org.metadatacenter.cedar.cee;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory session registry. Sessions are never expired — the server is short-lived by design. */
final class SessionStore
{
  private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

  Session create(Session.Mode mode, ObjectNode templateJson, ObjectNode instanceJson)
  {
    return create(mode, templateJson, instanceJson, false, "en");
  }

  Session create(Session.Mode mode, ObjectNode templateJson, ObjectNode instanceJson,
      boolean hideEmptyFields, String language)
  {
    Session session = new Session(UUID.randomUUID().toString(), mode, templateJson, instanceJson,
        hideEmptyFields, language);
    sessions.put(session.id, session);
    return session;
  }

  Optional<Session> get(String id)
  {
    return Optional.ofNullable(sessions.get(id));
  }

  List<Session> all()
  {
    return sessions.values().stream().sorted(Comparator.comparing(s -> s.createdAt)).toList();
  }
}
