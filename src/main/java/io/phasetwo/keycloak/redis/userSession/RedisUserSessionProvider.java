package io.phasetwo.keycloak.redis.userSession;

import static io.phasetwo.keycloak.redis.userSession.expiration.RedisSessionExpiration.*;
import static org.keycloak.common.util.StackUtil.getShortStackTrace;
import static org.keycloak.models.UserSessionModel.CORRESPONDING_SESSION_ID;
import static org.keycloak.models.UserSessionModel.SessionPersistenceState.TRANSIENT;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import io.phasetwo.keycloak.redis.RedisChangelogTransaction;
import io.phasetwo.keycloak.redis.connection.RedisMode;
import io.phasetwo.keycloak.redis.userSession.expiration.SessionExpirationData;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.common.util.ConcurrentMultivaluedHashMap;
import org.keycloak.common.util.MultivaluedMap;
import org.keycloak.common.util.Time;
import org.keycloak.device.DeviceActivityManager;
import org.keycloak.models.*;
import org.keycloak.models.utils.KeycloakModelUtils;
import redis.clients.jedis.AbstractPipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.UnifiedJedis;

@JBossLog
public class RedisUserSessionProvider implements UserSessionProvider {
  private final KeycloakSession session;
  private final UnifiedJedis jedis;

  private final RedisChangelogTransaction<UserSessionKey, RedisUserSessionAdapter> userSessionTrx;
  private final RedisChangelogTransaction<
          AuthenticatedClientSessionKey, RedisAuthenticatedClientSessionAdapter>
      clientSessionTrx;

  private final Map<String, RedisUserSessionAdapter> transientUserSessions = new HashMap<>();

  private final MultivaluedMap<String, RedisUserSessionAdapter> brokerSessionIdSessions =
      new ConcurrentMultivaluedHashMap<>();
  private final MultivaluedMap<String, RedisUserSessionAdapter> brokerUserIdSessions =
      new ConcurrentMultivaluedHashMap<>();

  public RedisUserSessionProvider(
      KeycloakSession session, UnifiedJedis jedis, RedisMode redisMode) {
    this.session = session;
    this.jedis = jedis;

    this.clientSessionTrx =
        RedisChangelogTransaction.create(
            "clientSession",
            jedis,
            redisMode,
            new AuthenticatedClientSessionAdapterSupplier(session, jedis));
    this.userSessionTrx =
        RedisChangelogTransaction.create(
            "userSession",
            jedis,
            redisMode,
            new UserSessionAdapterSupplier(session, jedis, this.clientSessionTrx));
    session.getTransactionManager().enlistAfterCompletion(this.userSessionTrx);
    session.getTransactionManager().enlistAfterCompletion(this.clientSessionTrx);
  }

  @Override
  public KeycloakSession getKeycloakSession() {
    return session;
  }

  // xx
  @Override
  public AuthenticatedClientSessionModel createClientSession(
      RealmModel realm, ClientModel client, UserSessionModel userSession) {
    log.tracef(
        "createClientSession(%s, %s, %s)%s", realm, client, userSession, getShortStackTrace());

    if (userSession == null) {
      throw new IllegalStateException("User session is null.");
    }

    RedisUserSessionAdapter userSessionEntity = getUserSessionAdapter(userSession);
    if (userSessionEntity == null) {
      throw new IllegalStateException("User session entity does not exist: " + userSession.getId());
    }

    if (userSessionEntity.getAuthenticatedClientSessionByClient(client.getId()) != null) {
      userSessionEntity.removeAuthenticatedClientSessions(List.of(client.getId()));
    }

    RedisAuthenticatedClientSessionAdapter entity =
        createAuthenticatedClientSessionEntityInstance(
            null,
            userSession.getId(),
            realm.getId(),
            client.getId(),
            isTransient(userSession),
            false);

    String started = String.valueOf(entity.getTimestamp());
    entity.setNote(AuthenticatedClientSessionModel.STARTED_AT_NOTE, started);
    entity.setNote(
        AuthenticatedClientSessionModel.USER_SESSION_STARTED_AT_NOTE,
        String.valueOf(userSession.getStarted()));
    if (userSession.isRememberMe()) {
      entity.setNote(AuthenticatedClientSessionModel.USER_SESSION_REMEMBER_ME_NOTE, "true");
    }

    setClientSessionExpiration(
        entity, SessionExpirationData.builder().realm(realm).build(), client, false);

    userSessionEntity.getAuthenticatedClientSessions().put(client.getId(), entity);
    return entity;
  }

  /**
   * Registers a client session for deletion at commit, addressed by its key.
   *
   * <p>Used by {@link RedisAuthenticatedClientSessionAdapter#detachFromUserSession()} for an
   * orphan, which has no parent to delegate the removal to. Resolving the key through the
   * transaction first matters: only the instance the transaction has cached is honoured at commit,
   * and the caller is not necessarily holding it (issue #81).
   */
  void removeClientSession(RedisAuthenticatedClientSessionAdapter clientSession) {
    RedisAuthenticatedClientSessionAdapter cached =
        clientSessionTrx.getIfPresent(clientSession.getKey());
    clientSessionTrx.addForDelete(cached != null ? cached : clientSession);
  }

  /** Convert the UserSessionModel to a RedisUserSessionAdapter or load it from the transaction */
  private RedisUserSessionAdapter getUserSessionAdapter(UserSessionModel userSession) {
    if (userSession instanceof RedisUserSessionAdapter) {
      return (RedisUserSessionAdapter) userSession;
    }
    // KC may wrap the adapter (e.g. UserSessionUtil$1 during token-exchange:v2)
    return getUserSessionById(userSession.getId());
  }

  // xx
  @Override
  public AuthenticatedClientSessionModel getClientSession(
      UserSessionModel userSession, ClientModel client, String clientSessionId, boolean offline) {
    log.tracef(
        "getClientSession(%s, %s, %s, %s)%s",
        userSession, client, clientSessionId, offline, getShortStackTrace());

    if (userSession == null) {
      return null;
    }

    return userSession.getAuthenticatedClientSessionByClient(client.getId());
  }

  @Override
  public AuthenticatedClientSessionModel getClientSession(
      UserSessionModel userSession, ClientModel client, boolean offline) {
    log.tracef(
        "getClientSession(%s, %s, %b)%s", userSession, client, offline, getShortStackTrace());

    if (userSession == null) {
      return null;
    }

    return userSession.getAuthenticatedClientSessionByClient(client.getId());
  }

  // xx
  @SuppressWarnings("deprecation")
  @Override
  public UserSessionModel createUserSession(
      RealmModel realm,
      UserModel user,
      String loginUsername,
      String ipAddress,
      String authMethod,
      boolean rememberMe,
      String brokerSessionId,
      String brokerUserId) {
    return createUserSession(
        null,
        realm,
        user,
        loginUsername,
        ipAddress,
        authMethod,
        rememberMe,
        brokerSessionId,
        brokerUserId,
        UserSessionModel.SessionPersistenceState.PERSISTENT);
  }

  // xx
  @Override
  public UserSessionModel createUserSession(
      String id,
      RealmModel realm,
      UserModel user,
      String loginUsername,
      String ipAddress,
      String authMethod,
      boolean rememberMe,
      String brokerSessionId,
      String brokerUserId,
      UserSessionModel.SessionPersistenceState persistenceState) {
    log.tracef(
        "createUserSession(%s, %s, %s, %s)%s",
        id, realm, loginUsername, persistenceState, getShortStackTrace());
    id = id == null ? KeycloakModelUtils.generateId() : id;

    RedisUserSessionAdapter entity =
        createUserSessionEntityInstance(
            id,
            realm.getId(),
            user.getId(),
            loginUsername,
            ipAddress,
            authMethod,
            rememberMe,
            brokerSessionId,
            brokerUserId,
            false);

    entity.setPersistenceState(persistenceState);
    setUserSessionExpiration(entity, SessionExpirationData.builder().realm(realm).build());
    if (TRANSIENT == persistenceState) {
      transientUserSessions.put(entity.getId(), entity);
      userSessionTrx.addForDelete(entity);
    }

    DeviceActivityManager.attachDevice(entity, session);

    return entity;
  }

  // xx
  @Override
  public RedisUserSessionAdapter getUserSession(RealmModel realm, String id) {
    Objects.requireNonNull(realm, "The provided realm can't be null!");
    log.tracef("getUserSession(%s, %s)%s", realm, id, getShortStackTrace());
    if (id == null) return null;

    RedisUserSessionAdapter a = transientUserSessions.get(id);
    if (a != null) {
      return a;
    }

    // https://github.com/keycloak/keycloak/blob/archive/map-store/model/map/src/main/java/org/keycloak/models/map/userSession/MapUserSessionProvider.java#L193-L199
    a = userSessionTrx.getIfPresent(new UserSessionKey(id));
    if (a != null && Objects.equals(a.getRealmId(), realm.getId()) && !a.isOffline()) {
      log.tracef("found userSession at %s %s", id, a);
      return a;
    }

    return null;
  }

  private Stream<RedisUserSessionAdapter> getUserSessionsStreamByIndexKey(
      String indexKey, RealmModel realm, boolean offline) {
    return getUserSessionsStreamByIndexKey(new String[] {indexKey}, realm, offline);
  }

  private Stream<RedisUserSessionAdapter> getUserSessionsStreamByIndexKey(
      String[] indexKeys, RealmModel realm, boolean offline) {
    log.tracef("[redis] SMEMBERS %s", Arrays.toString(indexKeys));
    try {
      // Read the index Sets in one pipelined batch, then close that pipeline (return its pooled
      // connection) BEFORE resolving members via getAll — which opens its own pipeline. Holding
      // both
      // open at once would pin two pooled connections per call on this hot read path and can
      // exhaust
      // the connection pool under concurrency (the very failure getAll's try-with-resources
      // guards).
      List<Set<String>> membersPerIndex = Lists.newArrayList();
      Set<String> allStrIds = Sets.newLinkedHashSet();
      try (AbstractPipeline pipeline = jedis.pipelined()) {
        List<Response<Set<String>>> responses = Lists.newArrayList();
        for (String indexKey : indexKeys) {
          responses.add(pipeline.smembers(indexKey));
        }
        pipeline.sync(); // This executes the batch
        for (Response<Set<String>> response : responses) {
          Set<String> members = response.get();
          members = members == null ? Set.of() : members;
          membersPerIndex.add(members);
          allStrIds.addAll(members);
        }
      }
      if (allStrIds.isEmpty()) {
        return Stream.empty();
      }

      // Resolve all members in one pipelined batch (getAll), then reconcile. A member whose session
      // hash has expired/vanished does not resolve and is dangling: it is registered for reap-at-
      // commit so the by-index Sets self-heal without any write on the read path, in every mode
      // (issue #78). Dead members are attributed per index (rather than over the union) so each is
      // only removed from the Set it actually came from. Live members are de-duplicated across
      // indexes by getAll's result map.
      Map<UserSessionKey, RedisUserSessionAdapter> resolved =
          userSessionTrx.getAll(
              allStrIds.stream().map(UserSessionKey::fromString).collect(Collectors.toList()));

      // Per index: register any dangling member for reap-at-commit, and push the Set's TTL up to
      // cover the longest-lived live member it holds (issue #78, review finding A). The write-path
      // backstop stamps the Set from whichever session last wrote it, so a longer-lived member that
      // is never re-written could be stranded under a shorter co-tenant's TTL and silently dropped
      // from the index; reads grow the TTL back (GT), or PERSIST the Set when a live member never
      // expires (null expiration). Both are deferred to commit — never written on the read path.
      for (int i = 0; i < indexKeys.length; i++) {
        List<Long> liveExpirations = Lists.newArrayList();
        for (String strId : membersPerIndex.get(i)) {
          RedisUserSessionAdapter live = resolved.get(UserSessionKey.fromString(strId));
          if (live == null) {
            userSessionTrx.reapIndexMemberOnCommit(indexKeys[i], strId);
          } else {
            liveExpirations.add(live.getExpiration());
          }
        }
        userSessionTrx.extendIndexTtlToCoverLiveMembers(indexKeys[i], liveExpirations);
      }

      return resolved.values().stream()
          .filter(s -> s.getRealmId().equals(realm.getId()))
          .filter(s -> offline == s.isOffline());
    } catch (Exception e) {
      log.error("Pipeline failed", e);
    }

    return Stream.empty();
  }

  // xx
  @Override
  public Stream<UserSessionModel> getUserSessionsStream(RealmModel realm, UserModel user) {
    log.tracef("getUserSessionsStream(%s, %s)%s", realm, user, getShortStackTrace());

    String indexKey = String.format("user-session:user-index:%s", user.getId());
    return getUserSessionsStreamByIndexKey(indexKey, realm, false).map(s -> (UserSessionModel) s);
  }

  // xx
  @Override
  public Stream<UserSessionModel> getUserSessionsStream(RealmModel realm, ClientModel client) {
    log.tracef("getUserSessionsStream(%s, %s)%s", realm, client, getShortStackTrace());

    return getClientSessionsByClientIndex(realm, client)
        .map(RedisAuthenticatedClientSessionAdapter::getUserSession)
        .filter(Objects::nonNull)
        .filter(us -> !us.isOffline());
  }

  /**
   * Reads the authenticated-client:client-index Set for a client and resolves its members to live
   * client-session adapters (matching realm and client). In every mode this eagerly resolves so
   * that dangling members — whose client-session hash has expired/vanished — are reconciled ({@code
   * SREM}'d at commit) out of the Set, making the by-client reads self-healing (issue #78). Members
   * are read in a stable (sorted) order, preserving pagination consistency for callers.
   */
  private Stream<RedisAuthenticatedClientSessionAdapter> getClientSessionsByClientIndex(
      RealmModel realm, ClientModel client) {
    String indexKey = String.format("authenticated-client:client-index:%s", client.getId());
    log.tracef("[redis] SMEMBERS %s", indexKey);
    try {
      // Guard a null reply and swallow transient Redis failures exactly as
      // getUserSessionsStreamByIndexKey does, so one malformed member or a connection blip returns
      // an empty stream instead of throwing uncaught out of getUserSessionsStream(realm, client),
      // getOfflineSessionsCount and getOfflineUserSessionsStream (issue #78 review).
      Set<String> strIds = jedis.smembers(indexKey);
      if (strIds == null || strIds.isEmpty()) {
        return Stream.empty();
      }
      // Resolve all members in one pipelined batch (getAll). A member whose client-session hash has
      // expired/vanished does not resolve and is dangling: it is registered for reap-at-commit so
      // the client-index self-heals without writing on the read path, in every mode (issue #78).
      Map<AuthenticatedClientSessionKey, RedisAuthenticatedClientSessionAdapter> resolved =
          clientSessionTrx.getAll(
              strIds.stream()
                  .map(AuthenticatedClientSessionKey::fromString)
                  .collect(Collectors.toList()));
      // Reap dangling members and grow the client-index TTL to cover its longest-lived live member
      // (issue #78, review finding A); see getUserSessionsStreamByIndexKey for the rationale.
      List<Long> liveExpirations = Lists.newArrayList();
      for (String strId : strIds) {
        RedisAuthenticatedClientSessionAdapter live =
            resolved.get(AuthenticatedClientSessionKey.fromString(strId));
        if (live == null) {
          clientSessionTrx.reapIndexMemberOnCommit(indexKey, strId);
        } else {
          liveExpirations.add(live.getExpiration());
        }
      }
      clientSessionTrx.extendIndexTtlToCoverLiveMembers(indexKey, liveExpirations);
      // getAll's result map orders cached entries before uncached ones, so it is not globally
      // sorted; re-impose the stable member-key order the callers' skip/limit pagination relies on.
      return resolved.values().stream()
          .sorted(Comparator.comparing(c -> c.getKey().key()))
          .filter(c -> c.getRealmId().equals(realm.getId()))
          .filter(c -> c.getClientUuid().equals(client.getId()));
    } catch (Exception e) {
      log.error("Pipeline failed", e);
      return Stream.empty();
    }
  }

  // xx
  @Override
  public Stream<UserSessionModel> getUserSessionsStream(
      RealmModel realm, ClientModel client, Integer firstResult, Integer maxResults) {
    log.tracef(
        "getUserSessionsStream(%s, %s, %s, %s)%s",
        realm, client, firstResult, maxResults, getShortStackTrace());

    return getUserSessionsStream(realm, client)
        .skip(firstResult != null && firstResult > 0 ? firstResult : 0)
        .limit(maxResults != null && maxResults > 0 ? maxResults : Long.MAX_VALUE);
  }

  @Override
  public Stream<UserSessionModel> getUserSessionByBrokerUserIdStream(
      RealmModel realm, String brokerUserId) {
    log.tracef(
        "getUserSessionByBrokerUserIdStream(%s, %s)%s", realm, brokerUserId, getShortStackTrace());

    // local to transaction
    List<RedisUserSessionAdapter> as = brokerUserIdSessions.getList(brokerUserId);
    if (as == null) as = Lists.newArrayList();
    // from the store
    String indexKey = String.format("user-session:broker-user-index:%s", brokerUserId);
    return mergeAndDeduplicate(
        as.stream().map(a -> (UserSessionModel) a).filter(a -> !a.isOffline()),
        getUserSessionsStreamByIndexKey(indexKey, realm, false).map(s -> (UserSessionModel) s),
        UserSessionModel::getId);
  }

  static <T> Stream<T> mergeAndDeduplicate(
      Stream<T> stream1, Stream<T> stream2, Function<T, Object> idExtractor) {
    return Stream.concat(stream1, stream2)
        .collect(
            Collectors.toMap(idExtractor, Function.identity(), (existing, replacement) -> existing))
        .values()
        .stream();
  }

  @Override
  public UserSessionModel getUserSessionByBrokerSessionId(
      RealmModel realm, String brokerSessionId) {
    log.tracef(
        "getUserSessionByBrokerSessionId(%s, %s)%s", realm, brokerSessionId, getShortStackTrace());

    // local to transaction
    RedisUserSessionAdapter a = brokerSessionIdSessions.getFirst(brokerSessionId);
    if (a != null && !a.isMarkedForDelete()) return a;

    String indexKey = String.format("user-session:broker-session-index:%s", brokerSessionId);
    return getUserSessionsStreamByIndexKey(indexKey, realm, false).findFirst().orElse(null);
  }

  // xx
  @Override
  public UserSessionModel getUserSessionWithPredicate(
      RealmModel realm, String id, boolean offline, Predicate<UserSessionModel> predicate) {
    log.tracef(
        "getUserSessionWithPredicate(%s, %s, %s)%s", realm, id, offline, getShortStackTrace());

    Stream<UserSessionModel> userSessionEntityStream;
    if (offline) {
      userSessionEntityStream =
          getOfflineUserSessionEntityStream(realm, id)
              .filter(Objects::nonNull)
              .map(offlineSession -> (UserSessionModel) offlineSession);
    } else {
      UserSessionModel userSession = getUserSession(realm, id);
      userSessionEntityStream = userSession != null ? Stream.of(userSession) : Stream.empty();
    }
    return userSessionEntityStream.filter(predicate).findFirst().orElse(null);
  }

  // xx
  @Override
  public long getActiveUserSessions(RealmModel realm, ClientModel client) {
    log.tracef("getActiveUserSessions(%s, %s)%s", realm, client, getShortStackTrace());

    // TODO a more efficient way?
    return getUserSessionsStream(realm, client).count();
  }

  @Override
  public Map<String, Long> getActiveClientSessionStats(RealmModel realm, boolean offline) {
    log.tracef("getActiveClientSessionStats(%s, %s)%s", realm, offline, getShortStackTrace());
    String indexKey = String.format("user-session:realm-index:%s", realm.getId());
    return getUserSessionsStreamByIndexKey(indexKey, realm, offline)
        .map(this::getUserSessionAdapter)
        .filter(Objects::nonNull)
        .map(UserSessionModel::getAuthenticatedClientSessions)
        .map(Map::keySet)
        .flatMap(Collection::stream)
        .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
  }

  // xx
  @Override
  public void removeUserSession(RealmModel realm, UserSessionModel session) {
    Objects.requireNonNull(session, "The provided user session can't be null!");

    log.tracef("removeUserSession(%s, %s)%s", realm, session, getShortStackTrace());

    RedisUserSessionAdapter a;
    if (session instanceof RedisUserSessionAdapter) {
      a = (RedisUserSessionAdapter) session;
    } else {
      a = userSessionTrx.getIfPresent(new UserSessionKey(session.getId()));
    }
    // https://github.com/keycloak/keycloak/blob/archive/map-store/model/map/src/main/java/org/keycloak/models/map/userSession/MapUserSessionProvider.java#L326-L332
    if (a != null && Objects.equals(session.getRealm(), realm) && !session.isOffline()) {
      log.infof("adding for delete %s", a);
      userSessionTrx.addForDelete(a);
    }
  }

  // xx
  @Override
  public void removeUserSessions(RealmModel realm, UserModel user) {
    log.tracef("removeUserSessions(%s, %s)%s", realm, user, getShortStackTrace());

    getUserSessionsStream(realm, user).forEach(this::removeSession);
  }

  private void removeSession(UserSessionModel a) {
    for (AuthenticatedClientSessionModel c : a.getAuthenticatedClientSessions().values()) {
      if (c instanceof RedisAuthenticatedClientSessionAdapter) {
        RedisAuthenticatedClientSessionAdapter rc = (RedisAuthenticatedClientSessionAdapter) c;
        clientSessionTrx.addForDelete(rc);
      } else {
        // TODO?
        log.warnf("Incorrect type for AuthenticatedClientSessionModel %s", c.getId());
      }
    }
    if (a instanceof RedisUserSessionAdapter) {
      RedisUserSessionAdapter ra = (RedisUserSessionAdapter) a;
      userSessionTrx.addForDelete(ra);
    } else {
      // TODO?
      log.warnf("Incorrect type for UserSessionModel %s", a.getId());
    }
  }

  // xx
  @Override
  public void removeAllExpired() {
    log.tracef("removeAllExpired()%s", getShortStackTrace());
  }

  // xx
  @Override
  public void removeExpired(RealmModel realm) {
    log.tracef("removeExpired(%s)%s", realm, getShortStackTrace());
  }

  // xx
  @Override
  public void removeUserSessions(RealmModel realm) {
    log.tracef("removeUserSessions(%s)%s", realm, getShortStackTrace());
    // TODO make this efficient. maybe with a SCAN/COUNT?

    String indexKey = String.format("user-session:realm-index:%s", realm.getId());
    getUserSessionsStreamByIndexKey(indexKey, realm, false).forEach(a -> removeSession(a));
    // TODO better way to do offline/online
    getUserSessionsStreamByIndexKey(indexKey, realm, true).forEach(a -> removeSession(a));
  }

  // xx
  @Override
  public void onRealmRemoved(RealmModel realm) {
    log.tracef("onRealmRemoved(%s)%s", realm, getShortStackTrace());
    removeUserSessions(realm);
  }

  // TODO this isn't getting called when the client gets removed.
  // http://github.com/keycloak/keycloak/blob/release/26.3/services/src/main/java/org/keycloak/services/managers/ClientManager.java#L104
  @Override
  public void onClientRemoved(RealmModel realm, ClientModel client) {
    log.tracef("onClientRemoved(%s-%s)%s", realm, client, getShortStackTrace());
    getUserSessionsStream(realm, client).forEach(a -> removeSession(a));
  }

  // xx
  @Override
  public UserSessionModel createOfflineUserSession(UserSessionModel userSession) {
    log.tracef("createOfflineUserSession(%s)%s", userSession, getShortStackTrace());
    if (userSession.getNote(CORRESPONDING_SESSION_ID) != null) {
      return getUserSession(userSession.getRealm(), userSession.getNote(CORRESPONDING_SESSION_ID));
    }

    RedisUserSessionAdapter offlineUserSession = createUserSessionEntityInstance(userSession, true);

    // set a reference for the offline user session to the original online user session
    userSession.setNote(CORRESPONDING_SESSION_ID, userSession.getId());

    int currentTime = Time.currentTime();
    offlineUserSession.setTimestamp(currentTime);
    offlineUserSession.setLastSessionRefresh(currentTime);
    offlineUserSession.setPersistenceState(userSession.getPersistenceState());
    setUserSessionExpiration(
        offlineUserSession, SessionExpirationData.builder().realm(userSession.getRealm()).build());

    // set a reference for the offline user session to the original online user session
    RedisUserSessionAdapter orgUserSessionAdapter =
        getUserSession(userSession.getRealm(), userSession.getId());
    orgUserSessionAdapter.setNote(CORRESPONDING_SESSION_ID, offlineUserSession.getId());

    return offlineUserSession;
  }

  @Override
  public UserSessionModel getOfflineUserSession(RealmModel realm, String userSessionId) {
    log.tracef("getOfflineUserSession(%s, %s)%s", realm, userSessionId, getShortStackTrace());

    var correspondingSessionIndex =
        "user-session:corresponding-session-index:%s".formatted(userSessionId);
    return getOfflineUserSessionEntityStream(realm, userSessionId)
        .filter(Objects::nonNull)
        .findFirst()
        .orElseGet(
            () ->
                getUserSessionsStreamByIndexKey(correspondingSessionIndex, realm, true)
                    .findFirst()
                    .orElse(null));
  }

  // xx
  @Override
  public void removeOfflineUserSession(RealmModel realm, UserSessionModel userSession) {
    Objects.requireNonNull(userSession, "The provided user session can't be null!");

    log.tracef("removeOfflineUserSession(%s, %s)%s", realm, userSession, getShortStackTrace());

    String uk = null;
    if (userSession.isOffline()) {
      uk = userSession.getId();
    } else if (userSession.getNote(CORRESPONDING_SESSION_ID) != null) {
      uk = userSession.getNote(CORRESPONDING_SESSION_ID);
    }
    RedisUserSessionAdapter entity = userSessionTrx.getIfPresent(new UserSessionKey(uk));
    removeSession(entity);
  }

  static boolean isTransient(UserSessionModel userSession) {
    if (userSession == null || userSession.getPersistenceState() == null) return false;
    return (userSession.getPersistenceState()
        == UserSessionModel.SessionPersistenceState.TRANSIENT);
  }

  static boolean isPersistent(UserSessionModel userSession) {
    return !isTransient(userSession);
  }

  @Override
  public AuthenticatedClientSessionModel createOfflineClientSession(
      AuthenticatedClientSessionModel clientSession, UserSessionModel offlineUserSession) {
    log.tracef(
        "createOfflineClientSession(%s, %s)%s",
        clientSession, offlineUserSession, getShortStackTrace());

    RealmModel realm = clientSession.getRealm();
    Optional<RedisUserSessionAdapter> userSessionEntity =
        getOfflineUserSessionEntityStream(realm, offlineUserSession.getId()).findFirst();
    if (userSessionEntity.isEmpty()) {
      return null;
    }
    RedisUserSessionAdapter userSession = userSessionEntity.get();
    String clientId = clientSession.getClient().getId();

    // Remove the predecessor BEFORE creating the replacement, as the online path does. Keyed on the
    // client UUID, not the client-session id. The replacement lands on the same deterministic key,
    // and creating over a pending delete replaces the hash rather than merging into it -- so the
    // successor cannot inherit the predecessor's refresh tokens (issue #81).
    if (userSession.getAuthenticatedClientSessionByClient(clientId) != null) {
      userSession.removeAuthenticatedClientSessions(List.of(clientId));
    }

    RedisAuthenticatedClientSessionAdapter clientSessionEntity =
        createAuthenticatedClientSessionInstance(clientSession, offlineUserSession);
    int currentTime = Time.currentTime();
    clientSessionEntity.setNote(
        AuthenticatedClientSessionModel.STARTED_AT_NOTE, String.valueOf(currentTime));
    clientSessionEntity.setNote(
        AuthenticatedClientSessionModel.USER_SESSION_STARTED_AT_NOTE,
        String.valueOf(offlineUserSession.getStarted()));
    clientSessionEntity.setTimestamp(currentTime);
    setClientSessionExpiration(
        clientSessionEntity,
        SessionExpirationData.builder().realm(realm).build(),
        clientSession.getClient(),
        true);

    userSession.addAuthenticatedClientSession(clientSessionEntity);
    return clientSessionEntity;
  }

  // xx
  @Override
  public Stream<UserSessionModel> getOfflineUserSessionsStream(RealmModel realm, UserModel user) {
    log.tracef("getOfflineUserSessionsStream(%s, %s)%s", realm, user, getShortStackTrace());

    String indexKey = String.format("user-session:user-index:%s", user.getId());
    return getUserSessionsStreamByIndexKey(indexKey, realm, true).map(s -> (UserSessionModel) s);
  }

  // xx
  @Override
  public Stream<UserSessionModel> getOfflineUserSessionByBrokerUserIdStream(
      RealmModel realm, String brokerUserId) {
    log.tracef(
        "getOfflineUserSessionByBrokerUserIdStream(%s, %s)%s",
        realm, brokerUserId, getShortStackTrace());

    // local to transaction
    List<RedisUserSessionAdapter> as = brokerUserIdSessions.getList(brokerUserId);
    if (as == null) as = Lists.newArrayList();
    // from the store
    String indexKey = String.format("user-session:broker-user-index:%s", brokerUserId);
    return mergeAndDeduplicate(
        as.stream().map(a -> (UserSessionModel) a).filter(a -> a.isOffline()),
        getUserSessionsStreamByIndexKey(indexKey, realm, true).map(s -> (UserSessionModel) s),
        UserSessionModel::getId);
  }

  @Override
  public long getOfflineSessionsCount(RealmModel realm, ClientModel client) {
    log.tracef("getOfflineSessionsCount(%s, %s)%s", realm, client, getShortStackTrace());

    return getClientSessionsByClientIndex(realm, client)
        .map(RedisAuthenticatedClientSessionAdapter::getUserSession)
        .filter(Objects::nonNull)
        .filter(UserSessionModel::isOffline)
        .count();
  }

  // xx
  @Override
  public Stream<UserSessionModel> getOfflineUserSessionsStream(
      RealmModel realm, ClientModel client, Integer firstResult, Integer maxResults) {
    log.tracef(
        "getOfflineUserSessionsStream(%s, %s, %s, %s)%s",
        realm, client, firstResult, maxResults, getShortStackTrace());

    return getClientSessionsByClientIndex(realm, client)
        .map(RedisAuthenticatedClientSessionAdapter::getUserSession)
        .filter(Objects::nonNull)
        .filter(UserSessionModel::isOffline)
        .skip(firstResult != null && firstResult > 0 ? firstResult : 0)
        .limit(maxResults != null && maxResults > 0 ? maxResults : Long.MAX_VALUE);
  }

  @SuppressWarnings("removal")
  @Override
  public void importUserSessions(
      Collection<UserSessionModel> persistentUserSessions, boolean offline) {
    if (persistentUserSessions == null || persistentUserSessions.isEmpty()) {
      return;
    }
    /*
        persistentUserSessions.stream()
                .map(pus -> {
                    UserSession userSessionEntity = createUserSessionEntityInstance(
                            null,
                            pus.getRealm().getId(),
                            pus.getUser().getId(),
                            pus.getLoginUsername(),
                            pus.getIpAddress(),
                            pus.getAuthMethod(),
                            pus.isRememberMe(),
                            pus.getBrokerSessionId(),
                            pus.getBrokerUserId(),
                            offline);
                    userSessionEntity.setPersistenceState(UserSessionModel.SessionPersistenceState.PERSISTENT);

                    for (Map.Entry<String, AuthenticatedClientSessionModel> entry :
                            pus.getAuthenticatedClientSessions().entrySet()) {
                        AuthenticatedClientSessionValue clientSession =
                                createAuthenticatedClientSessionInstance(entry.getValue(), offline);

                        // Update timestamp to same value as userSession. LastSessionRefresh of userSession
                        // from DB will have correct value
                        clientSession.setTimestamp(userSessionEntity.getLastSessionRefresh());

                        RealmModel realm = session.realms().getRealm(userSessionEntity.getRealmId());
                        userSessionRepository.insert(realm, userSessionEntity);
                        userSessionRepository.addClientSession(realm, userSessionEntity, clientSession);

                        CassandraUserSessionAdapter adapter =
                                entityToAdapterFunc(pus.getRealm()).apply(userSessionEntity);
                        sessionModels.put(adapter.getId(), adapter);
                    }

                    return userSessionEntity;
                })
                .forEach(userSession ->
                        userSessionRepository.insert(session.realms().getRealm(userSession.getRealmId()), userSession));
    */

  }

  @Override
  public void close() {
    // Nothing to do
  }

  @Override
  public int getStartupTime(RealmModel realm) {
    return realm.getNotBefore();
  }

  private Stream<RedisUserSessionAdapter> getOfflineUserSessionEntityStream(
      RealmModel realm, String userSessionId) {
    if (userSessionId == null) {
      return Stream.empty();
    }

    // first get a user entity by ID
    // check if it's an offline user session
    RedisUserSessionAdapter userSessionEntity = getUserSessionById(userSessionId);
    if (userSessionEntity != null) {
      if (userSessionEntity.isOffline()) {
        return Stream.of(userSessionEntity);
      }
    } else {
      // no session found by the given ID, try to find by corresponding session ID
      var correspondingSessionIndex =
          "user-session:corresponding-session-index:%s".formatted(userSessionId);
      return getUserSessionsStreamByIndexKey(correspondingSessionIndex, realm, true);
    }

    // it's online user session so lookup offline user session by corresponding session id reference
    String offlineUserSessionId = userSessionEntity.getNote(CORRESPONDING_SESSION_ID);
    if (offlineUserSessionId != null) {
      return Stream.ofNullable(getUserSessionById(offlineUserSessionId));
    }

    return Stream.empty();
  }

  private RedisUserSessionAdapter getUserSessionById(String id) {
    if (id == null) return null;

    RedisUserSessionAdapter userSessionEntity = transientUserSessions.get(id);

    if (userSessionEntity == null) {
      return userSessionTrx.getIfPresent(new UserSessionKey(id));
    }
    return userSessionEntity;
  }

  private RedisUserSessionAdapter createUserSessionEntityInstance(
      UserSessionModel userSession, boolean offline) {
    RedisUserSessionAdapter entity =
        createUserSessionEntityInstance(
            null,
            userSession.getRealm().getId(),
            userSession.getUser().getId(),
            userSession.getLoginUsername(),
            userSession.getIpAddress(),
            userSession.getAuthMethod(),
            userSession.isRememberMe(),
            userSession.getBrokerSessionId(),
            userSession.getBrokerUserId(),
            offline);

    entity.setNotes(userSession.getNotes());
    entity.setNote(CORRESPONDING_SESSION_ID, userSession.getId());
    entity.setState(userSession.getState());
    entity.setTimestamp(userSession.getStarted());
    entity.setLastSessionRefresh(userSession.getLastSessionRefresh());
    return entity;
  }

  private RedisUserSessionAdapter createUserSessionEntityInstance(
      String id,
      String realmId,
      String userId,
      String loginUsername,
      String ipAddress,
      String authMethod,
      boolean rememberMe,
      String brokerSessionId,
      String brokerUserId,
      boolean offline) {
    int timestamp = Time.currentTime();
    id = id == null ? KeycloakModelUtils.generateId() : id;
    RedisUserSessionAdapter entity = userSessionTrx.get(new UserSessionKey(id));
    entity.setUserId(userId);
    entity.setRealmId(realmId);
    entity.setLoginUsername(loginUsername);
    entity.setIpAddress(ipAddress);
    entity.setAuthMethod(authMethod);
    entity.setRememberMe(rememberMe);
    entity.setBrokerSessionId(brokerSessionId);
    entity.setBrokerUserId(brokerUserId);
    entity.setOffline(offline);
    entity.setTimestamp(timestamp);
    entity.setLastSessionRefresh(timestamp);
    if (brokerSessionId != null) {
      brokerSessionIdSessions.add(brokerSessionId, entity);
    }
    if (brokerUserId != null) {
      brokerUserIdSessions.add(brokerUserId, entity);
    }
    return entity;
  }

  private RedisAuthenticatedClientSessionAdapter createAuthenticatedClientSessionEntityInstance(
      String id,
      String userSessionId,
      String realmId,
      String clientId,
      boolean stateTransient,
      boolean offline) {
    int timestamp = Time.currentTime();
    id = id == null ? createAuthenticatedClientId(userSessionId, clientId) : id;
    RedisAuthenticatedClientSessionAdapter entity =
        clientSessionTrx.get(new AuthenticatedClientSessionKey(id));
    entity.setRealmId(realmId);
    entity.setClientUuid(clientId);
    entity.setParentId(userSessionId);
    // Both create paths know the literal value, so store it instead of making every later read ask
    // a parent user session that may be gone by then (issue #81). Set before setTimestamp(), which
    // uses it to pick the expiration branch.
    entity.setOffline(offline);
    entity.setTimestamp(timestamp);
    entity.setNotes(new HashMap<>());
    if (stateTransient) {
      clientSessionTrx.addForDelete(entity);
    }
    return entity;
  }

  private static String createAuthenticatedClientId(String userSessionId, String clientId) {
    return userSessionId + "::" + clientId;
  }

  private RedisAuthenticatedClientSessionAdapter createAuthenticatedClientSessionInstance(
      AuthenticatedClientSessionModel clientSession, UserSessionModel offlineUserSession) {
    RedisAuthenticatedClientSessionAdapter entity =
        createAuthenticatedClientSessionEntityInstance(
            null,
            offlineUserSession.getId(),
            clientSession.getRealm().getId(),
            clientSession.getClient().getId(),
            isTransient(offlineUserSession),
            true);
    entity.setAction(clientSession.getAction());
    entity.setProtocol(clientSession.getProtocol());
    entity.setNotes(new HashMap<>(clientSession.getNotes()));
    entity.setRedirectUri(clientSession.getRedirectUri());
    entity.setTimestamp(clientSession.getTimestamp());
    return entity;
  }
}
