package io.phasetwo.keycloak.redis.authSession;

import static org.keycloak.models.utils.SessionExpiration.getAuthSessionLifespan;

import com.google.common.collect.ImmutableMap;
import io.phasetwo.keycloak.common.ExpirableEntity;
import io.phasetwo.keycloak.common.TimeAdapter;
import io.phasetwo.keycloak.redis.KeyFormat;
import io.phasetwo.keycloak.redis.MapEntity;
import io.phasetwo.keycloak.redis.RedisChangelogTransaction;
import java.util.*;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.common.util.Base64Url;
import org.keycloak.common.util.SecretGenerator;
import org.keycloak.common.util.Time;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.sessions.RootAuthenticationSessionModel;
import redis.clients.jedis.UnifiedJedis;

@JBossLog
public class RedisRootAuthenticationSessionAdapter extends MapEntity<RootAuthenticationSessionKey>
    implements RootAuthenticationSessionModel, ExpirableEntity {

  private final KeycloakSession session;
  private final UnifiedJedis jedis;
  private final int authSessionsLimit;
  private final RedisChangelogTransaction<
          AuthenticationSessionKey, RedisAuthenticationSessionAdapter>
      authSessionTrx;

  public RedisRootAuthenticationSessionAdapter(
      KeycloakSession session,
      UnifiedJedis jedis,
      int authSessionsLimit,
      RedisChangelogTransaction<AuthenticationSessionKey, RedisAuthenticationSessionAdapter>
          authSessionTrx,
      String realmId,
      String id) {
    this(session, jedis, authSessionsLimit, authSessionTrx, realmId, id, null);
  }

  public RedisRootAuthenticationSessionAdapter(
      KeycloakSession session,
      UnifiedJedis jedis,
      int authSessionsLimit,
      RedisChangelogTransaction<AuthenticationSessionKey, RedisAuthenticationSessionAdapter>
          authSessionTrx,
      String realmId,
      String id,
      Map<String, String> existingData) {
    super(new RootAuthenticationSessionKey(realmId, id), existingData);
    this.session = session;
    this.jedis = jedis;
    this.authSessionsLimit = authSessionsLimit;
    this.authSessionTrx = authSessionTrx;
    setFieldFromKey("id", id);
    setFieldFromKey("realmId", realmId);
  }

  @Override
  public Map<String, String> getSecondaryIndexes() {
    ImmutableMap.Builder<String, String> b = ImmutableMap.builder();
    siPutIf(b, getRealmId(), KeyFormat.rootAuthSessionRealmIndex(getRealmId()), getKey().key());
    return b.build();
  }

  @Override
  public RealmModel getRealm() {
    return session.realms().getRealm(getRealmId());
  }

  public String getRealmId() {
    return getString("realmId");
  }

  @Override
  public String getId() {
    return getString("id");
  }

  @Override
  public void setTimestamp(int timestamp) {
    setTimestampLong(TimeAdapter.fromSecondsToMilliseconds(timestamp));
  }

  public void setTimestampLong(long timestamp) {
    setField("timestamp", timestamp);
  }

  public long getTimestampLong() {
    return getLong("timestamp", 0);
  }

  @Override
  public int getTimestamp() {
    return TimeAdapter.fromMilliSecondsToIntSeconds(getTimestampLong());
  }

  @Override
  public Long getExpiration() {
    if (isNull("expiration")) return null;
    return getLong("expiration", 0L);
  }

  @Override
  public void setExpiration(Long expiration) {
    setField("expiration", expiration);
  }

  // TODO add expiration - figure out how to expire entries with a job or
  // the redis internal mechanism (doesn't work on multi-region)

  @Override
  public Map<String, AuthenticationSessionModel> getAuthenticationSessions() {

    String indexKey = KeyFormat.authSessionParentIndex(getRealmId(), getId());
    log.debugf("[redis] SMEMBERS %s", indexKey);
    Set<String> strIds = jedis.smembers(indexKey);
    if (strIds != null && !strIds.isEmpty()) {
      Map<String, AuthenticationSessionModel> sessions = new HashMap<>();
      for (String rawId : strIds) {
        AuthenticationSessionKey key = AuthenticationSessionKey.fromString(rawId);
        RedisAuthenticationSessionAdapter session = authSessionTrx.getIfPresent(key);
        if (session == null) {
          // audit §4.7 index hygiene: skip the stale member now; the reap
          // DEFERS to transaction commit — no Redis writes outside commitImpl.
          authSessionTrx.reapIndexMemberOnCommit(indexKey, rawId);
          continue;
        }
        sessions.put(key.tabId(), session);
      }
      return sessions;
    }

    return new HashMap<>();
  }

  @Override
  public AuthenticationSessionModel getAuthenticationSession(ClientModel client, String tabId) {
    log.tracef("getAuthenticationSession tabId=%s clientId=%s", tabId, client.getId());
    if (tabId == null) {
      return null;
    }
    return authSessionTrx.getIfPresent(
        new AuthenticationSessionKey(getRealmId(), getId(), client.getId(), tabId));
  }

  private static final Comparator<RedisAuthenticationSessionAdapter> TIMESTAMP_COMPARATOR =
      Comparator.comparingLong(RedisAuthenticationSessionAdapter::getTimestamp);

  @Override
  public AuthenticationSessionModel createAuthenticationSession(ClientModel client) {
    Objects.requireNonNull(client, "The provided client can't be null!");

    long timestamp = Time.currentTimeMillis();
    int authSessionLifespanSeconds = getAuthSessionLifespan(client.getRealm());

    Map<String, AuthenticationSessionModel> authSessions = getAuthenticationSessions();
    if (authSessions != null && authSessions.size() >= authSessionsLimit) {
      Optional<RedisAuthenticationSessionAdapter> oldest =
          authSessions.values().stream()
              .map(a -> (RedisAuthenticationSessionAdapter) a)
              .min(TIMESTAMP_COMPARATOR);
      String tabId = oldest.map(RedisAuthenticationSessionAdapter::getTabId).orElse(null);

      if (tabId != null) {
        log.debugf(
            "Reached limit (%s) of active authentication sessions per a root authentication session. Removing oldest authentication session with tabId %s.",
            authSessionsLimit, tabId);

        // remove the oldest authentication session
        authSessionTrx.addForDelete(oldest.get());
        authSessions.remove(tabId);
      }
    }

    String tabId = generateTabId();
    RedisAuthenticationSessionAdapter adapter =
        authSessionTrx.get(
            new AuthenticationSessionKey(getRealmId(), getId(), client.getId(), tabId));
    adapter.setClientUuid(client.getId());
    adapter.setParentSession(this);
    adapter.setTimestamp(timestamp);
    log.tracef("created authSession %s", adapter);

    setTimestampLong(timestamp);
    long exp = timestamp + TimeAdapter.fromSecondsToMilliseconds(authSessionLifespanSeconds);
    setExpiration(exp);

    getAuthenticationSessions().put(tabId, adapter);

    session.getContext().setAuthenticationSession(adapter);
    return adapter;
  }

  @Override
  public void removeAuthenticationSessionByTabId(String tabId) {
    Map<String, AuthenticationSessionModel> authSessions = getAuthenticationSessions();
    AuthenticationSessionModel as = authSessions.get(tabId);
    if (as == null) return;
    RealmModel realm = as.getRealm();
    removeAuthenticationSession(as);
    authSessions.remove(tabId);
    if (authSessions.isEmpty()) {
      session.authenticationSessions().removeRootAuthenticationSession(realm, this);
    } else {
      long timestamp = Time.currentTimeMillis();
      setTimestampLong(timestamp);
      int authSessionLifespanSeconds = getAuthSessionLifespan(realm);
      long exp = timestamp + TimeAdapter.fromSecondsToMilliseconds(authSessionLifespanSeconds);
      setExpiration(exp);
    }
  }

  private void removeAuthenticationSession(AuthenticationSessionModel authSession) {
    if (authSession instanceof RedisAuthenticationSessionAdapter adapter) {
      authSessionTrx.addForDelete(adapter);
    } else {
      log.tracef(
          "No authentication session found for %s",
          authSession == null ? null : authSession.getTabId());
    }
  }

  @Override
  public void restartSession(RealmModel realm) {
    Iterator<Map.Entry<String, AuthenticationSessionModel>> iterator =
        getAuthenticationSessions().entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<String, AuthenticationSessionModel> entry = iterator.next();
      removeAuthenticationSession(entry.getValue());
      iterator.remove();
    }
    setTimestamp(Time.currentTime());
  }

  private String generateTabId() {
    return Base64Url.encode(SecretGenerator.getInstance().randomBytes(8));
  }
}
