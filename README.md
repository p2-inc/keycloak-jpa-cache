# Keycloak JPA cache 

Uses JPA instead of Infinispan for remote cache entities. Overrides the `LegacyDatastoreProvider`.

Requires Keycloak >= 23.0.0.

## How to use

- Set `KC_COMMUNITY_JPA_CACHE_ENABLED=true`
- Set `KC_CACHE=local`
