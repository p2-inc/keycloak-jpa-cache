/*
 * Copyright 2023 IT-Systemhaus der Bundesagentur fuer Arbeit
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.phasetwo.keycloak.compatibility;

import static io.phasetwo.keycloak.common.Constants.PROVIDER_PRIORITY;
import static io.phasetwo.keycloak.common.ProviderHelpers.createProviderCached;

import com.google.auto.service.AutoService;
import io.phasetwo.keycloak.common.IsSupported;
import lombok.extern.jbosslog.JBossLog;
import org.infinispan.Cache;
import org.infinispan.client.hotrod.RemoteCache;
import org.keycloak.Config;
import org.keycloak.connections.infinispan.InfinispanConnectionProvider;
import org.keycloak.connections.infinispan.InfinispanConnectionProviderFactory;
import org.keycloak.connections.infinispan.TopologyInfo;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

@JBossLog
@AutoService(InfinispanConnectionProviderFactory.class)
public class NullQuarkusInfinispanConnectionProviderFactory
    implements InfinispanConnectionProviderFactory, IsSupported {

  @Override
  public InfinispanConnectionProvider create(KeycloakSession session) {
    return createProviderCached(
        session,
        InfinispanConnectionProvider.class,
        () ->
            new InfinispanConnectionProvider() {
              @Override
              public <K, V> Cache<K, V> getCache(String s) {
                return null;
              }

              @Override
              public <K, V> Cache<K, V> getCache(String s, boolean b) {
                return null;
              }

              @Override
              public <K, V> RemoteCache<K, V> getRemoteCache(String s) {
                return null;
              }

              @Override
              public TopologyInfo getTopologyInfo() {
                return null;
              }

              @Override
              public void close() {}
            });
  }

  @Override
  public void init(Config.Scope config) {
    log.info("Infinispan (quarkus) deactivated...");
  }

  @Override
  public void postInit(KeycloakSessionFactory factory) {}

  @Override
  public void close() {}

  @Override
  public int order() {
    return PROVIDER_PRIORITY + 1;
  }

  @Override
  public String getId() {
    return "quarkus";
  }
}
