package io.phasetwo.keycloak.common;

public class CommunityProfiles {
  private static final String ENV_JPA_CACHE_ENABLED = "KC_COMMUNITY_JPA_CACHE_ENABLED";
  private static final String PROP_JPA_CACHE_ENABLED = "kc.community.jpa.cache.enabled";

  private static final boolean isJpaCacheEnabled;
  
  static {
    isJpaCacheEnabled = Boolean.parseBoolean(System.getenv(ENV_JPA_CACHE_ENABLED)) ||
                        Boolean.parseBoolean(System.getProperty(PROP_JPA_CACHE_ENABLED));
  }
  
  public static boolean isJpaCacheEnabled() {
    return isJpaCacheEnabled;
  }
}
