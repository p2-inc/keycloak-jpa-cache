> :bug: **This is alpha software** Do not use this in production.

# Keycloak JPA cache 

Uses JPA instead of [Infinispan](https://infinispan.org/) for remote cache entities. Overrides the [`LegacyDatastoreProvider`](https://www.keycloak.org/docs-api/23.0.4/javadocs/org/keycloak/storage/datastore/LegacyDatastoreProvider.html).

Requires [Keycloak](https://keycloak.org) >= 23.0.4.

Heavily inspired by [keycloak-cassandra-extension](https://github.com/opdt/keycloak-cassandra-extension). Enormous thanks to these amazing engineers.

## Why

From our post on Keycloak Discussions, [Future of seamless upgrades? #24655](https://github.com/keycloak/keycloak/discussions/24655):

> ... the one thing that causes peoples' jaws to hit the floor is when I tell them that **Keycloak upgrades may not be seamless (e.g. without downtime), and there is a risk of losing sessions**. We've found that many companies abandon plans to use Keycloak at this point, even if they were 99% sold on every other aspect. **In many ways, it's the only feature that matters**, as Keycloak provides feature parity (and in many cases superior functionality) to most other tools it is evaluated against.

To have a prayer of solving the above problem, at minimum you have to use external Infinispan. Infinispan is hard to operate, even with all of the awesome operators and tools that team has built. And, this configuration for Keycloak and Infinispan is poorly documented and highly complext to get right. Furthermore, upgrading Infinispan itself is a daunting and error-prone task.

The present day expectation of "cloud native" apps is that they are stateless, ephemeral images that come up and down quickly. This really isn't possible when using embedded or external Infinispan with Keycloak.

By replacing the cache with a JPA implementation, it massively simplifies operation; specifically restarts and upgrades. Because of the performace loss (maybe? benchmark TBD), this is indended for users of Keycloak with small to medium deployments. **Such deployments don't have fully staffed, 24x7 NOCs, but their IAM system should still have great operational characteristics.** This intends to solve that.

## How to use

### Setup

Applies to any deployment type:

- Set `KC_COMMUNITY_JPA_CACHE_ENABLED=true`
- Set `KC_CACHE=local`
- Set `KC_FEATURES_DISABLED=authorization`

### DIY

- Build the jar with `mvn clean install -DskipTests`
- Put the `target/keycloak-jpa-cache-<version>.jar` in your Keycloak `providers/` directory

### Docker

TBD. You can currently try after building with the included `docker-compose.yml` file. Just run `docker compose up`.

## Known issues

- `ClusterProvider` implementation still relies on the replicated `work` cache using Infinispan. This is used to propagate evictions to the Realm or User local caches between instances. Todo replace this with something simpler, probably via the database.
- Keycloak Authorization must be disabled (this is essential as otherwise Keycloak tries to use `InfinispanStoreFactory` in a lot of places).
- Some tests are still skipped or failing. We need to understand if this is because the test fails to do everything in a single transaction (Keycloak doesn't do this internally) or if there is something we are missing.
- Hasn't been benchmarked to look for issues under load.

-----

Portions of the code are taken from [keycloak](https://github.com/keycloak/keycloak) and the [keycloak-cassandra-extension](https://github.com/opdt/keycloak-cassandra-extension) and the copyright is held by their respective owners.

All other documentation, source code and other files in this repository are Copyright 2024 Phase Two, Inc.
