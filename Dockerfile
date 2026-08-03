FROM quay.io/phasetwo/keycloak-crdb:26.6.3 AS builder

ENV KC_COMMUNITY_REDIS_CACHE_ENABLED: true
ENV KC_CACHE: local

COPY ./target/*withdeps.jar /opt/keycloak/providers/

RUN /opt/keycloak/bin/kc.sh --verbose build

FROM quay.io/phasetwo/keycloak-crdb:26.6.3

USER 1000

COPY --from=builder /opt/keycloak/lib/quarkus/ /opt/keycloak/lib/quarkus/
COPY --from=builder /opt/keycloak/providers/ /opt/keycloak/providers/

WORKDIR /opt/keycloak