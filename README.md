# Keycloak JPA cache 

Uses JPA instead of Infinispan for remote cache entities. Overrides the `LegacyDatastoreProvider`.

Requires Keycloak >= 23.0.4.

Heavily inspired by (https://github.com/opdt/keycloak-cassandra-extension)

## How to use

- Build the fat-jar with `mvn clean install -DskipTests`
- Put it in your Keycloak `providers/` directory
- Set `KC_COMMUNITY_JPA_CACHE_ENABLED=true`
- Set `KC_CACHE=local`
