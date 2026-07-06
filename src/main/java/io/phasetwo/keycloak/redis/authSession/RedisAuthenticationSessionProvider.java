package io.phasetwo.keycloak.redis.authSession;

import static org.keycloak.common.util.StackUtil.getShortStackTrace;
import static org.keycloak.models.utils.SessionExpiration.getAuthSessionLifespan;

import io.phasetwo.keycloak.redis.KeyFormat;
import io.phasetwo.keycloak.redis.RedisChangelogTransaction;
import io.phasetwo.keycloak.redis.connection.RedisMode;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.common.util.Time;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.sessions.AuthenticationSessionCompoundId;
import org.keycloak.sessions.AuthenticationSessionProvider;
import org.keycloak.sessions.RootAuthenticationSessionModel;
import redis.clients.jedis.UnifiedJedis;

@JBossLog
public class RedisAuthenticationSessionProvider implements AuthenticationSessionProvider {

  private final KeycloakSession session;
  private final UnifiedJedis jedis;
  private final int authSessionsLimit;

  private final RedisChangelogTransaction<
          RootAuthenticationSessionKey, RedisRootAuthenticationSessionAdapter>
      rootSessionTrx;
  private final RedisChangelogTransaction<
          AuthenticationSessionKey, RedisAuthenticationSessionAdapter>
      authSessionTrx;

  public RedisAuthenticationSessionProvider(
      KeycloakSession session, UnifiedJedis jedis, RedisMode redisMode, int authSessionsLimit) {
    this.session = session;
    this.jedis = jedis;
    this.authSessionsLimit = authSessionsLimit;

    this.authSessionTrx =
        new RedisChangelogTransaction<>(
            "authSession",
            jedis,
            redisMode,
            new AuthenticationSessionAdapterSupplier(session, jedis));
    this.rootSessionTrx =
        new RedisChangelogTransaction<>(
            "rootAuthSession",
            jedis,
            redisMode,
            new RootAuthenticationSessionAdapterSupplier(
                session, jedis, this.authSessionsLimit, this.authSessionTrx));
    session.getTransactionManager().enlistAfterCompletion(this.authSessionTrx);
    session.getTransactionManager().enlistAfterCompletion(this.rootSessionTrx);
  }

  @Override
  public RootAuthenticationSessionModel createRootAuthenticationSession(RealmModel realm) {
    return createRootAuthenticationSession(realm, null);
  }

  @Override
  public RootAuthenticationSessionModel createRootAuthenticationSession(
      RealmModel realm, String id) {
    Objects.requireNonNull(realm, "The provided realm can't be null!");
    log.tracef("createRootAuthenticationSession(%s)%s", realm.getName(), getShortStackTrace());

    int timestamp = Time.currentTime();
    int authSessionLifespanSeconds = getAuthSessionLifespan(realm);

    RedisRootAuthenticationSessionAdapter adapter =
        new RedisRootAuthenticationSessionAdapter(
            session,
            jedis,
            authSessionsLimit,
            authSessionTrx,
            realm.getId(),
            id == null ? KeycloakModelUtils.generateId() : id);
    adapter.setTimestamp(timestamp);
    long exp = (timestamp + authSessionLifespanSeconds) * 1000L;
    adapter.setExpiration(exp);

    rootSessionTrx.addForSave(adapter);

    return adapter;
  }

  @Override
  public RootAuthenticationSessionModel getRootAuthenticationSession(
      RealmModel realm, String authenticationSessionId) {
    Objects.requireNonNull(realm, "The provided realm can't be null!");
    if (authenticationSessionId == null) {
      return null;
    }
    return rootSessionTrx.getIfPresent(
        new RootAuthenticationSessionKey(realm.getId(), authenticationSessionId));
  }

  @Override
  public void removeRootAuthenticationSession(
      RealmModel realm, RootAuthenticationSessionModel authenticationSession) {
    Objects.requireNonNull(
        authenticationSession, "The provided root authentication session can't be null!");

    RedisRootAuthenticationSessionAdapter adapter;
    if (authenticationSession instanceof RedisRootAuthenticationSessionAdapter) {
      adapter = (RedisRootAuthenticationSessionAdapter) authenticationSession;
    } else {
      adapter =
          rootSessionTrx.get(
              new RootAuthenticationSessionKey(realm.getId(), authenticationSession.getId()));
    }
    rootSessionTrx.addForDelete(adapter);
  }

  @SuppressWarnings("deprecation")
  @Override
  public void removeAllExpired() {
    log.tracef("removeAllExpired()%s", getShortStackTrace());
    log.warnf(
        "[deprecated] Clearing expired entities should not be triggered manually. It is responsibility of the store to clear these.");
  }

  @SuppressWarnings("deprecation")
  @Override
  public void removeExpired(RealmModel realm) {
    log.tracef("removeExpired(%s)%s", realm, getShortStackTrace());
    log.warnf(
        "[deprecated] Clearing expired entities should not be triggered manually. It is responsibility of the store to clear these.");
  }

  @Override
  public void onRealmRemoved(RealmModel realm) {
    log.tracef("onRealmRemoved(%s)%s", realm, getShortStackTrace());
    // Just let them expire...
  }

  @Override
  public void onClientRemoved(RealmModel realm, ClientModel client) {
    log.tracef("onClientRemoved(%s-%s)%s", realm, client, getShortStackTrace());
    // Just let them expire...
  }

  @Override
  public void updateNonlocalSessionAuthNotes(
      AuthenticationSessionCompoundId compoundId, Map<String, String> authNotesFragment) {
    if (compoundId == null) {
      return;
    }
    Objects.requireNonNull(
        authNotesFragment, "The provided authentication's notes map can't be null!");

    log.tracef("updateNonlocalSessionAuthNotes(%s)%s", compoundId, getShortStackTrace());

    // Deprecated cross-node path (no cluster in the serverless model); the
    // parent index is realm-scoped, so resolve the realm from context.
    RealmModel contextRealm = session.getContext().getRealm();
    if (contextRealm == null) {
      log.warn("updateNonlocalSessionAuthNotes without a context realm — skipping");
      return;
    }
    String indexKey =
        KeyFormat.authSessionParentIndex(contextRealm.getId(), compoundId.getRootSessionId());
    log.debugf("[redis] SMEMBERS %s", indexKey);
    Set<String> strIds = jedis.smembers(indexKey);
    if (strIds != null && !strIds.isEmpty()) {
      strIds.stream()
          .map(AuthenticationSessionKey::fromString)
          .map(authSessionTrx::getIfPresent)
          .filter(Objects::nonNull)
          .filter(c -> c.getTabId().equals(compoundId.getTabId()))
          .filter(c -> c.getClient().getId().equals(compoundId.getClientUUID()))
          .findFirst()
          .ifPresent(
              authenticationSession -> authenticationSession.setAuthNotes(authNotesFragment));
    }
  }

  @Override
  public void close() {
    // Nothing to do
  }
}
