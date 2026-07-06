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
 * environment variable ({@code default} | {@code serverless});
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
        ? "s:{" + realmId + "}:us:" + id
        : "user-session:" + id;
  }

  /** {@code compositeId} is the historical {@code <sid>::<clientUuid>} form. */
  public static String clientSession(String realmId, String compositeId) {
    return serverless()
        ? "s:{" + realmId + "}:cs:" + compositeId.replace("::", ":")
        : "authenticated-client:" + compositeId;
  }

  public static String rootAuthSession(String realmId, String id) {
    return serverless()
        ? "s:{" + realmId + "}:as:" + id
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
        ? "s:{" + realmId + "}:ast:" + rootId + ":" + tabId
        : "auth-session:" + clientId + ":" + tabId;
  }

  public static String loginFailure(String realmId, String userId) {
    return serverless()
        ? "s:{" + realmId + "}:lf:" + userId
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
        ? "s:{" + realmId + "}:usx:" + userId
        : "user-session:user-index:" + userId;
  }

  public static String userSessionBrokerUserIndex(String realmId, String brokerUserId) {
    return serverless()
        ? "s:{" + realmId + "}:bux:" + brokerUserId
        : "user-session:broker-user-index:" + brokerUserId;
  }

  public static String userSessionBrokerSessionIndex(String realmId, String brokerSessionId) {
    return serverless()
        ? "s:{" + realmId + "}:bsx:" + brokerSessionId
        : "user-session:broker-session-index:" + brokerSessionId;
  }

  public static String userSessionCorrespondingIndex(String realmId, String sessionId) {
    return serverless()
        ? "s:{" + realmId + "}:cox:" + sessionId
        : "user-session:corresponding-session-index:" + sessionId;
  }

  public static String clientSessionParentIndex(String realmId, String userSessionId) {
    return serverless()
        ? "s:{" + realmId + "}:csp:" + userSessionId
        : "authenticated-client:parent-index:" + userSessionId;
  }

  public static String clientSessionClientIndex(String realmId, String clientUuid) {
    return serverless()
        ? "s:{" + realmId + "}:csx:" + clientUuid
        : "authenticated-client:client-index:" + clientUuid;
  }

  public static String rootAuthSessionRealmIndex(String realmId) {
    return serverless()
        ? "s:{" + realmId + "}:asr"
        : "root-auth-session:realm-index:" + realmId;
  }

  public static String authSessionParentIndex(String realmId, String rootId) {
    return serverless()
        ? "s:{" + realmId + "}:asp:" + rootId
        : "auth-session:parent:" + rootId;
  }

  // ---------------------------------------------------------------------
  // Parsing support (prefix-sniffing; formats are disjoint)
  // ---------------------------------------------------------------------

  /** True when the raw key is in the serverless grammar. */
  public static boolean isServerlessKey(String raw) {
    return raw != null && raw.startsWith("s:{");
  }

  /**
   * Splits {@code s:{<realm>}:<type>:<rest>} into [realm, rest] after
   * validating {@code type}. Throws on malformed input.
   */
  public static String[] parseServerless(String raw, String type) {
    String marker = "}:" + type + ":";
    int close = raw.indexOf(marker);
    if (!isServerlessKey(raw) || close < 0) {
      throw new IllegalArgumentException(
          "Expected format: s:{<realm>}:" + type + ":<id>, got: " + raw);
    }
    return new String[] {raw.substring(3, close), raw.substring(close + marker.length())};
  }
}
