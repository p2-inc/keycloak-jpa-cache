/*
 * Copyright 2023 IT-Systemhaus der Bundesagentur fuer Arbeit
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.phasetwo.keycloak.redis.testsuite;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.phasetwo.keycloak.redis.RedisDatastoreProvider;
import org.junit.Test;
import org.keycloak.common.Profile;
import org.keycloak.common.crypto.CryptoIntegration;
import org.keycloak.models.*;
import org.keycloak.services.managers.ApplianceBootstrap;
import org.keycloak.services.resources.KeycloakApplication;
import org.keycloak.storage.DatastoreProvider;

public class StartupTest extends KeycloakModelTest {

  /**
   * The extension refuses to boot without the {@code stateless} feature, so this guards against the
   * test suite silently reverting to the pre-26.7 Infinispan-backed arrangement.
   */
  @Test
  public void testStatelessProfileAndRedisDatastore() {
    assertTrue(
        "the redis datastore requires the stateless feature",
        Profile.isFeatureEnabled(Profile.Feature.STATELESS));
    inComittedTransaction(
        (KeycloakSession session) ->
            assertTrue(
                "session providers should come from the redis datastore",
                session.getProvider(DatastoreProvider.class) instanceof RedisDatastoreProvider));
  }

  @Test
  public void testCreateMasterRealm() {
    inComittedTransaction(
        session -> {
          ApplianceBootstrap applianceBootstrap = new ApplianceBootstrap(session);
          CryptoIntegration.init(KeycloakApplication.class.getClassLoader());
          applianceBootstrap.createMasterRealm();
          applianceBootstrap.createMasterRealmUser("admin", "admin", false);

          assertNotNull(session.realms().getRealmByName("master"));
        });

    inComittedTransaction(
        session -> {
          RealmModel masterRealm = session.realms().getRealmByName("master");
          session.getContext().setRealm(masterRealm);

          assertNotNull(masterRealm);

          UserModel admin = session.users().getUserByUsername(masterRealm, "admin");
          assertNotNull(admin);

          assertTrue(admin.credentialManager().isValid(UserCredentialModel.password("admin")));

          session.realms().removeRealm(masterRealm.getId());
        });
  }
}
