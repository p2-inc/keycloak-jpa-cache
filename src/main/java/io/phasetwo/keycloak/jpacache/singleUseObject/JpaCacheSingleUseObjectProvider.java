package io.phasetwo.keycloak.jpacache.singleUseObject;

import static org.keycloak.common.util.StackUtil.getShortStackTrace;

import io.phasetwo.keycloak.jpacache.singleUseObject.persistence.entities.SingleUseObject;
import io.phasetwo.keycloak.mapstorage.common.TimeAdapter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.SingleUseObjectProvider;

@JBossLog
@RequiredArgsConstructor
public class JpaCacheSingleUseObjectProvider implements SingleUseObjectProvider {
  private static final String EMPTY_NOTE = "internal.emptyNote";
  private final KeycloakSession session;
  private final EntityManager entityManager;

  @Override
  public void put(String key, long lifespanSeconds, Map<String, String> notes) {
    log.tracef("put(%s)%s", key, getShortStackTrace());

    /* This is automatic if I set the key uq constraint properly
    if (singleUseEntity != null) {
      throw new ModelDuplicateException("Single-use object entity exists: " + singleUseEntity.getKey());
    }
    */

    SingleUseObject singleUseEntity =
        SingleUseObject.builder()
            .key(key)
            .expiresAt(getExpiration(lifespanSeconds))
            .notes(getInternalNotes(notes))
            .build();

    entityManager.persist(singleUseEntity);
    entityManager.flush();
  }

  private Date getExpiration(long lifespanSeconds) {
    int ttl = TimeAdapter.fromLongWithTimeInSecondsToIntegerWithTimeInSeconds(lifespanSeconds);
    Date expiration = new Date();
    expiration.setTime(expiration.getTime() + ttl);
    return expiration;
  }

  @Override
  public Map<String, String> get(String key) {
    log.tracef("get(%s)%s", key, getShortStackTrace());

    SingleUseObject singleUseEntity = findByKeyAndExpiration(key, new Date());
    if (singleUseEntity != null) {
      return getExternalNotes(singleUseEntity.getNotes());
    }
    return null;
  }

  private SingleUseObject findByKeyAndExpiration(String key, Date now) {
    TypedQuery<SingleUseObject> query =
        entityManager.createNamedQuery("findByKeyAndExpiration", SingleUseObject.class);
    query.setParameter("key", key);
    query.setParameter("now", new Date());
    return query.getSingleResult();
  }

  @Override
  public Map<String, String> remove(String key) {
    log.tracef("remove(%s)%s", key, getShortStackTrace());

    SingleUseObject singleUseEntity = findByKeyAndExpiration(key, new Date());
    if (singleUseEntity != null) {
      Map<String, String> notes = singleUseEntity.getNotes();
      entityManager.remove(singleUseEntity);
      entityManager.flush();
      return getExternalNotes(notes);
    }
    return null;
  }

  @Override
  public boolean replace(String key, Map<String, String> notes) {
    log.tracef("replace(%s)%s", key, getShortStackTrace());

    SingleUseObject singleUseEntity = findByKeyAndExpiration(key, new Date());
    if (singleUseEntity != null) {
      singleUseEntity.setNotes(getInternalNotes(notes));
      return true;
    }
    return false;
  }

  @Override
  public boolean putIfAbsent(String key, long lifespanSeconds) {
    log.tracef("putIfAbsent(%s)%s", key, getShortStackTrace());

    SingleUseObject singleUseEntity = findByKeyAndExpiration(key, new Date());
    if (singleUseEntity != null) {
      return false;
    } else {
      singleUseEntity =
          SingleUseObject.builder()
              .key(key)
              .expiresAt(getExpiration(lifespanSeconds))
              .notes(getInternalNotes(null))
              .build();
      entityManager.persist(singleUseEntity);
      entityManager.flush();
      return true;
    }
  }

  @Override
  public boolean contains(String key) {
    log.tracef("contains(%s)%s", key, getShortStackTrace());

    SingleUseObject singleUseEntity = findByKeyAndExpiration(key, new Date());

    return singleUseEntity != null;
  }

  @Override
  public void close() {
    // Nothing to do
  }

  private Map<String, String> getInternalNotes(Map<String, String> notes) {
    Map<String, String> result =
        notes == null
            ? new HashMap<>()
            : notes.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

    if (result.isEmpty()) {
      result.put(EMPTY_NOTE, EMPTY_NOTE);
    }

    return result;
  }

  private Map<String, String> getExternalNotes(Map<String, String> notes) {
    Map<String, String> result = notes == null ? new HashMap<>() : new HashMap<>(notes);

    result.remove(EMPTY_NOTE);

    return result;
  }
}
