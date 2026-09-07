package io.phasetwo.keycloak.redis.userSession;

import static io.phasetwo.keycloak.redis.userSession.expiration.RedisSessionExpiration.setClientSessionExpiration;

import com.google.common.collect.ImmutableMap;
import io.phasetwo.keycloak.common.ExpirableEntity;
import io.phasetwo.keycloak.redis.MapEntity;
import io.phasetwo.keycloak.redis.userSession.expiration.SessionExpirationData;
import java.util.Collections;
import java.util.Map;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.common.util.Time;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.models.UserSessionProvider;

@JBossLog
public class RedisAuthenticatedClientSessionAdapter extends MapEntity<AuthenticatedClientSessionKey>
    implements AuthenticatedClientSessionModel, ExpirableEntity {

  private final KeycloakSession session;

  private static final String REFRESH_TOKEN_LAST_USE_PREFIX = "refreshTokenLastUsePrefix";

  public RedisAuthenticatedClientSessionAdapter(KeycloakSession session, String id) {
    this(session, id, null);
  }

  public RedisAuthenticatedClientSessionAdapter(
      KeycloakSession session, String id, Map<String, String> existingData) {
    super(new AuthenticatedClientSessionKey(id), existingData);
    this.session = session;
    setField("id", id);
  }

  /**
   * Client-session ids are deterministic, which is what lets a caller address a client session it
   * cannot see through the (filtered) client-session map of its parent.
   */
  public static String clientSessionId(String parentId, String clientUuid) {
    return parentId + "::" + clientUuid;
  }

  @Override
  public Map<String, String> getSecondaryIndexes() {
    ImmutableMap.Builder<String, String> b = ImmutableMap.builder();
    siPut(b, "authenticated-client:parent-index:%s", getParentId(), getKey().key());
    siPut(b, "authenticated-client:client-index:%s", getClientUuid(), getKey().key());
    return b.build();
  }

  @Override
  public void detachFromUserSession() {
    UserSessionModel parent = getUserSession();
    if (parent == null) {
      // Detach is the revocation primitive behind refresh-token reuse, authorization-code replay
      // and offline-token revoke, and parent.removeAuthenticatedClientSessions() is the only path
      // that DELs this hash and SREMs both of its index memberships. Returning silently would
      // report a successful revoke while leaving the session -- and its refreshToken:<reuseId>
      // fields -- replayable until the hash's own TTL, and nothing else reaps an orphan
      // (removeAllExpired is a log-only no-op). So reap it here (issue #81).
      log.warnf(
          "Reaping orphaned client session %s: its parent user session %s is gone",
          getId(), getParentId());
      reap();
      return;
    }
    parent.removeAuthenticatedClientSessions(Collections.singleton(getClientUuid()));
  }

  /**
   * Registers this client session for deletion at commit.
   *
   * <p>Routed through the provider rather than left at {@link #markForDelete()} because the commit
   * only honours the flag on instances the transaction has cached, and this instance is not
   * necessarily one of them.
   */
  private void reap() {
    markForDelete();
    UserSessionProvider provider = session.getProvider(UserSessionProvider.class);
    if (provider instanceof RedisUserSessionProvider) {
      ((RedisUserSessionProvider) provider).removeClientSession(this);
    }
  }

  @Override
  public UserSessionModel getUserSession() {
    RealmModel realm = getRealm();
    if (realm == null || getParentId() == null) {
      // The provider requires a non-null realm; a client session whose realm no longer resolves
      // has no parent we could reach anyway (issue #81).
      return null;
    }

    UserSessionModel online = session.sessions().getUserSession(realm, getParentId());
    if (online != null) {
      return online;
    }

    // getOfflineUserSession() deliberately falls back to user-session:corresponding-session-index,
    // which maps an ONLINE session id to its OFFLINE sibling. That alias answers "give me the
    // offline session for this login" and is the wrong answer here, where we are asking for OUR
    // parent: accepting the sibling makes an orphan look attached, so the orphan guard never
    // fires, detach revokes the sibling's client session instead of this one, and the offline flag
    // gets read off the wrong session (issue #81).
    UserSessionModel offline = session.sessions().getOfflineUserSession(realm, getParentId());
    return offline != null && getParentId().equals(offline.getId()) ? offline : null;
  }

  @Override
  public String getId() {
    return getString("id");
  }

  public String getParentId() {
    return getString("parentId");
  }

  public void setParentId(String parentId) {
    setField("parentId", parentId);
  }

  @Override
  public void setNote(String name, String value) {
    if (value == null) {
      removeNote(name);
    } else {
      getNotes().put(name, value);
    }
  }

  public void setNotes(Map<String, String> notes) {
    Map<String, String> ns = getNotes();
    ns.clear();
    ns.putAll(notes);
  }

  @Override
  public Map<String, String> getNotes() {
    return getMap("notes");
  }

  @Override
  public String getNote(String name) {
    return getNotes().get(name);
  }

  @Override
  public void removeNote(String name) {
    getNotes().remove(name);
  }

  @Override
  public void setTimestamp(int timestamp) {
    setField("timestamp", timestamp);

    RealmModel realm = getRealm();
    if (realm == null) {
      // Every expiration input -- and getClient() itself -- hangs off the realm. The stored
      // expiration, and therefore the hash's Redis TTL, still stands, so leaving it alone degrades
      // to "this session expires when it was always going to" (issue #81).
      log.warnf(
          "Skipping expiration for client session %s: realm %s does not resolve",
          getId(), getRealmId());
      return;
    }

    Boolean offline = resolveOffline();
    if (offline == null && getExpiration() != null) {
      // Nothing can tell us whether this session is offline, and the two branches differ by up to
      // 1440x (30 days vs. the SSO idle timeout). Guessing online would shrink a genuine offline
      // grant to minutes and kill it mid-use, so keep the expiration that is already stored.
      log.warnf(
          "Keeping the stored expiration for client session %s: its offline flag is unknown",
          getId());
      return;
    }
    if (offline == null) {
      // ...but never leave a client session with no expiration at all: RedisHashCas skips
      // PEXPIREAT for a null expiration, ExpirationUtils never reaps one, and a single such member
      // makes the read path PERSIST the shared client-index Set for every co-tenant. With nothing
      // to preserve, the shorter (online) horizon is the fail-safe choice (issue #81).
      log.warnf(
          "Falling back to the online expiration horizon for client session %s: its offline flag"
              + " is unknown and it has no stored expiration",
          getId());
      offline = false;
    }

    setClientSessionExpiration(
        this,
        SessionExpirationData.builder().realm(realm).build(),
        realm.getClientById(getClientUuid()),
        offline);
  }

  /**
   * The offline flag for this client session, or {@code null} when it genuinely cannot be
   * determined.
   *
   * <p>Prefers the flag persisted on the client session itself and only falls back to the parent
   * user session for hashes written before that field existed.
   */
  private Boolean resolveOffline() {
    Boolean offline = isOffline();
    if (offline != null) {
      return offline;
    }
    UserSessionModel parent = getUserSession();
    if (parent == null) {
      return null;
    }
    // Backfill while the parent is still reachable, so a hash written before the flag existed stops
    // depending on it. Safe here because setTimestamp is already a write (issue #81).
    setOffline(parent.isOffline());
    return parent.isOffline();
  }

  @Override
  public int getTimestamp() {
    return getInt("timestamp", 0);
  }

  // Re: refresh token fields
  // might be good to store these as fields, instead of using defaults, which use notes
  // as they will get updated frequently and will benefit from partial field update, rather
  // than having to serialize the whole note map

  @Override
  public void setRefreshToken(String reuseId, String refreshToken) {
    setField(refreshTokenKey("refreshToken", reuseId), refreshToken);
  }

  @Override
  public String getRefreshToken(String reuseId) {
    return getString(refreshTokenKey("refreshToken", reuseId));
  }

  @Override
  public void setRefreshTokenUseCount(String reuseId, int count) {
    String currentCountStr = getNote(REFRESH_TOKEN_USE_PREFIX + reuseId);
    int currentCount =
        currentCountStr == null || currentCountStr.isEmpty()
            ? 0
            : Integer.parseInt(currentCountStr);

    if (count != currentCount) {
      setNote(REFRESH_TOKEN_LAST_USE_PREFIX + reuseId, String.valueOf(Time.currentTimeMillis()));
      setNote(REFRESH_TOKEN_USE_PREFIX + reuseId, String.valueOf(count));
    }
  }

  @Override
  public int getRefreshTokenUseCount(String reuseId) {
    String currentCount = getNote(REFRESH_TOKEN_USE_PREFIX + reuseId);

    if (currentCount == null) {
      return 0;
    }

    String lastUseTimestampString = getNote(REFRESH_TOKEN_LAST_USE_PREFIX + reuseId);
    if (lastUseTimestampString == null) {
      return Integer.parseInt(currentCount);
    }

    long lastUseTimestamp = Long.parseLong(lastUseTimestampString);
    RealmModel realm = getRealm();
    // Without a realm the reuse interval is unknowable. Fall back to its own default of 0, which
    // counts the refresh rather than discounting it: erring toward revoking on reuse is the safe
    // direction, and Keycloak calls this on every refresh when revokeRefreshToken is on (#81).
    long reuseInterval = realm == null ? 0L : realm.getAttribute("refreshTokenReuseInterval", 0L);
    if (lastUseTimestamp > Time.currentTimeMillis() - reuseInterval) {
      return Math.max(0, Integer.parseInt(currentCount) - 1); // do not count refresh
    }

    return Integer.parseInt(currentCount);
  }

  @Override
  public void setRefreshTokenLastRefresh(String reuseId, int refreshTokenLastRefresh) {
    setField(refreshTokenKey("refreshTokenLastRefresh", reuseId), refreshTokenLastRefresh);
  }

  @Override
  public int getRefreshTokenLastRefresh(String reuseId) {
    return getInt(refreshTokenKey("refreshTokenLastRefresh", reuseId), 0);
  }

  static String refreshTokenKey(String key, String reuseId) {
    return String.format("%s:%s", key, reuseId);
  }

  // end: refresh token fields

  @Override
  public RealmModel getRealm() {
    return session.realms().getRealm(getRealmId());
  }

  public void setRealmId(String realmId) {
    setField("realmId", realmId);
  }

  public String getRealmId() {
    return getString("realmId");
  }

  @Override
  public ClientModel getClient() {
    RealmModel realm = getRealm();
    return realm == null ? null : realm.getClientById(getClientUuid());
  }

  public void setClientUuid(String uuid) {
    setField("clientUuid", uuid);
  }

  public String getClientUuid() {
    return getString("clientUuid");
  }

  @Override
  public String getRedirectUri() {
    return getString("redirectUri");
  }

  @Override
  public void setRedirectUri(String uri) {
    setField("redirectUri", uri);
  }

  @Override
  public String getAction() {
    return getString("action");
  }

  @Override
  public void setAction(String action) {
    setField("action", action);
  }

  @Override
  public String getProtocol() {
    return getString("protocol");
  }

  @Override
  public void setProtocol(String protocol) {
    setField("protocol", protocol);
  }

  /**
   * Whether this client session belongs to an offline user session, or {@code null} for a hash
   * written before the flag was persisted.
   *
   * <p>The flag is stored on the client session itself rather than read from the parent user
   * session, because the parent is exactly the thing that is missing when it matters: an orphan
   * that falls back to the online horizon loses up to 1440x of its offline TTL (issue #81).
   */
  public Boolean isOffline() {
    if (isNull("offline")) return null;
    return getBool("offline", false);
  }

  public void setOffline(boolean offline) {
    setField("offline", offline);
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
}
