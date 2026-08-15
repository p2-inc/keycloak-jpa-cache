package io.phasetwo.keycloak.redis;

/**
 * Key-naming strategy for every Redis key this extension writes.
 *
 * <p>{@link #DEFAULT} renders the historical key layout byte-for-byte, so
 * existing deployments upgrade with their live data intact. {@link #SERVERLESS}
 * renders the multi-tenant grammar {@code s:{<realmId>}:<type>:<id...>}: the
 * realm id is wrapped in braces so Redis Cluster hash-tags co-locate a realm's
 * keys in one slot (making multi-key ops and per-realm operational moves
 * possible) and prefix-based ACLs ({@code s:{<realmId>}:*}) can scope a
 * credential to a single realm's session keyspace. That concentration is
 * exactly wrong for a single-realm deployment — which is why the format is
 * opt-in and never the default.
 *
 * <p>Selected once per process from the {@value #ENV} system property or
 * environment variable ({@code default} | {@code serverless}).
 *
 * <p><b>Hash tags in the serverless grammar are chosen for distribution.</b> They were originally
 * all {@code s:\{realmId\}:…}, which co-located a realm's whole session set in one hash slot — so a
 * realm could never span more than one shard however large the cluster, and one busy tenant pinned
 * one node's memory and one node's CPU while the rest idled. Each key is now tagged by its own
 * subject instead: a session by its id, an index by the user, client or root session it indexes.
 *
 * <p>This is only safe in cluster mode <em>because</em> index maintenance no longer needs the entity
 * and its indexes in the same slot — cluster writes skip {@code MULTI}, reads reconcile dangling
 * members, and every index Set carries a TTL backstop. In standalone every key is on one node, so
 * the tags are inert there.
 *
 * <p>Two keys keep the realm tag and always will: {@code rsx} (every session in a realm) and
 * {@code asr}. They are single fan-in Sets whose whole purpose is realm-wide enumeration, so no
 * tagging makes them distribute. Session <em>data</em> spreads; realm-wide <em>enumeration</em>
 * does not.
 *
 * {@link #setActive} exists for tests and embedded wiring. The two formats'
 * key prefixes are disjoint, so parsing sniffs the prefix rather than trusting
 * the mode — stray keys from the other format fail loudly instead of
 * mis-parsing.
 */
public enum KeyFormat {
  DEFAULT,
  SERVERLESS;

  public static final String ENV = "KC_COMMUNITY_REDIS_CACHE_KEY_FORMAT";

  private static volatile KeyFormat active = detect();

  private static KeyFormat detect() {
    String v = System.getProperty(ENV);
    if (v == null || v.isEmpty()) v = System.getenv(ENV);
    return "serverless".equalsIgnoreCase(v) ? SERVERLESS : DEFAULT;
  }

  public static KeyFormat active() {
    return active;
  }

  /** Test / embedded-wiring override. */
  public static void setActive(KeyFormat format) {
    active = format;
  }

  private static boolean serverless() {
    return active == SERVERLESS;
  }

  // ---------------------------------------------------------------------
  // Value keys
  // ---------------------------------------------------------------------

  public static String userSession(String realmId, String id) {
    return serverless()
        ? "s:" + realmId + ":us:{" + id + "}"
        : "user-session:" + id;
  }

  /**
   * {@code compositeId} is the historical {@code <sid>::<clientUuid>} form.
   *
   * <p>Tagged by the <em>session</em> id alone, not the whole composite, so a user session, all of
   * its client sessions and its parent index land in one slot. They are always read together.
   */
  public static String clientSession(String realmId, String compositeId) {
    if (!serverless()) return "authenticated-client:" + compositeId;
    String flat = compositeId.replace("::", ":");
    int sep = flat.indexOf(':');
    return sep > 0
        ? "s:" + realmId + ":cs:{" + flat.substring(0, sep) + "}:" + flat.substring(sep + 1)
        : "s:" + realmId + ":cs:{" + flat + "}";
  }

  public static String rootAuthSession(String realmId, String id) {
    return serverless()
        ? "s:" + realmId + ":as:{" + id + "}"
        : "root-auth-session:" + realmId + ":" + id;
  }

  /**
   * Per-tab authentication session. DEFAULT keeps the historical
   * {@code auth-session:<clientId>:<tabId>} layout — note it has a latent
   * cross-root collision (two root sessions authenticating the same client
   * can generate the same tabId); preserved here because changing it breaks
   * live data, fixed structurally in SERVERLESS by scoping under the root
   * session id. Worth raising upstream as its own change.
   */
  public static String authSessionTab(
      String realmId, String rootId, String clientId, String tabId) {
    return serverless()
        ? "s:" + realmId + ":ast:{" + rootId + "}:" + tabId
        : "auth-session:" + clientId + ":" + tabId;
  }

  public static String loginFailure(String realmId, String userId) {
    return serverless()
        ? "s:" + realmId + ":lf:{" + userId + "}"
        : "login-failure:" + realmId + ":" + userId;
  }

  // ---------------------------------------------------------------------
  // Secondary-index keys
  // ---------------------------------------------------------------------

  public static String userSessionRealmIndex(String realmId) {
    return serverless()
        ? "s:{" + realmId + "}:rsx"
        : "user-session:realm-index:" + realmId;
  }

  public static String userSessionUserIndex(String realmId, String userId) {
    return serverless()
        ? "s:" + realmId + ":usx:{" + userId + "}"
        : "user-session:user-index:" + userId;
  }

  public static String userSessionBrokerUserIndex(String realmId, String brokerUserId) {
    return serverless()
        ? "s:" + realmId + ":bux:{" + brokerUserId + "}"
        : "user-session:broker-user-index:" + brokerUserId;
  }

  public static String userSessionBrokerSessionIndex(String realmId, String brokerSessionId) {
    return serverless()
        ? "s:" + realmId + ":bsx:{" + brokerSessionId + "}"
        : "user-session:broker-session-index:" + brokerSessionId;
  }

  public static String userSessionCorrespondingIndex(String realmId, String sessionId) {
    return serverless()
        ? "s:" + realmId + ":cox:{" + sessionId + "}"
        : "user-session:corresponding-session-index:" + sessionId;
  }

  public static String clientSessionParentIndex(String realmId, String userSessionId) {
    return serverless()
        ? "s:" + realmId + ":csp:{" + userSessionId + "}"
        : "authenticated-client:parent-index:" + userSessionId;
  }

  public static String clientSessionClientIndex(String realmId, String clientUuid) {
    return serverless()
        ? "s:" + realmId + ":csx:{" + clientUuid + "}"
        : "authenticated-client:client-index:" + clientUuid;
  }

  public static String rootAuthSessionRealmIndex(String realmId) {
    return serverless()
        ? "s:{" + realmId + "}:asr"
        : "root-auth-session:realm-index:" + realmId;
  }

  public static String authSessionParentIndex(String realmId, String rootId) {
    return serverless()
        ? "s:" + realmId + ":asp:{" + rootId + "}"
        : "auth-session:parent:" + rootId;
  }

  // ---------------------------------------------------------------------
  // Parsing support (prefix-sniffing; formats are disjoint)
  // ---------------------------------------------------------------------

  /**
   * True when the raw key is in the serverless grammar.
   *
   * <p>Was {@code startsWith("s:{")} when every key was hash-tagged by realm. The tag has since
   * moved off the realm and onto each key's own subject, so the brace is no longer at a fixed
   * offset — but the {@code s:} prefix still separates the two grammars, because DEFAULT keys all
   * begin with a spelled-out entity name ({@code user-session:}, {@code auth-session:}, …).
   */
  public static boolean isServerlessKey(String raw) {
    return raw != null && raw.startsWith("s:") && raw.indexOf('{') > 0;
  }

  /**
   * Splits {@code s:<realm>:<type>:<rest>} into {@code [realm, rest]} after validating {@code type}.
   * Throws on malformed input.
   *
   * <p>{@code rest} is returned with its hash-tag braces STRIPPED, so callers get the same logical
   * id they passed to the builder above and round-tripping holds. This matters more than it looks:
   * index Sets store each member as the referenced entity's own Redis key, so every by-index read
   * parses keys back through here.
   *
   * <p>Tolerates the previous {@code s:\{<realm>\}:<type>:<id>} layout, where the tag was on the
   * realm. Not for migration — the keys are volatile and were flushed — but because a Set written
   * moments before a rollout can still be read moments after one, and a parse failure there is an
   * exception on a read path rather than a miss.
   */
  public static String[] parseServerless(String raw, String type) {
    if (raw != null && raw.startsWith("s:")) {
      // Current layout: s:<realm>:<type>:<rest>, tag somewhere inside <rest>.
      String marker = ":" + type + ":";
      int at = raw.indexOf(marker, 2);
      if (at > 2) {
        String realm = raw.substring(2, at);
        String rest = raw.substring(at + marker.length());
        if (!realm.isEmpty() && realm.indexOf('{') < 0) {
          return new String[] {realm, untag(rest)};
        }
      }
      // Legacy layout: s:{<realm>}:<type>:<rest>.
      String legacy = "}:" + type + ":";
      int close = raw.indexOf(legacy);
      if (raw.startsWith("s:{") && close > 0) {
        return new String[] {raw.substring(3, close), raw.substring(close + legacy.length())};
      }
    }
    throw new IllegalArgumentException(
        "Expected format: s:<realm>:" + type + ":<id>, got: " + raw);
  }

  /** Removes hash-tag braces wherever they appear in an id, leaving the logical value. */
  private static String untag(String rest) {
    if (rest.indexOf('{') < 0) return rest;
    return rest.replace("{", "").replace("}", "");
  }
}
