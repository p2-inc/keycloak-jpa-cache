/*
 * Copyright 2020 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.phasetwo.keycloak.jpacache.testsuite.parameters;

import com.google.common.collect.ImmutableSet;
import io.phasetwo.keycloak.jpacache.authSession.CassandraAuthSessionProviderFactory;
import io.phasetwo.keycloak.jpacache.client.CassandraClientProviderFactory;
import io.phasetwo.keycloak.jpacache.clientScope.CassandraClientScopeProviderFactory;
import io.phasetwo.keycloak.jpacache.group.CassandraGroupProviderFactory;
import io.phasetwo.keycloak.jpacache.loginFailure.CassandraLoginFailureProviderFactory;
import io.phasetwo.keycloak.jpacache.realm.CassandraRealmsProviderFactory;
import io.phasetwo.keycloak.jpacache.role.CassandraRoleProviderFactory;
import io.phasetwo.keycloak.jpacache.testsuite.Config;
import io.phasetwo.keycloak.jpacache.testsuite.KeycloakModelParameters;
import io.phasetwo.keycloak.jpacache.user.CassandraUserProviderFactory;
import io.phasetwo.keycloak.jpacache.userSession.CassandraUserSessionProviderFactory;
import io.phasetwo.keycloak.mapstorage.deploymentstate.MapDeploymentStateProviderFactory;
import io.phasetwo.keycloak.mapstorage.keys.MapPublicKeyStorageProviderFactory;
import org.keycloak.credential.CredentialSpi;
import org.keycloak.credential.OTPCredentialProviderFactory;
import org.keycloak.credential.PasswordCredentialProviderFactory;
import org.keycloak.credential.hash.PasswordHashSpi;
import org.keycloak.credential.hash.Pbkdf2Sha256PasswordHashProviderFactory;
import org.keycloak.device.DeviceRepresentationProviderFactoryImpl;
import org.keycloak.device.DeviceRepresentationSpi;
import org.keycloak.keys.*;
import org.keycloak.models.*;
import org.keycloak.models.locking.NoneGlobalLockProviderFactory;
import org.keycloak.policy.*;
import org.keycloak.provider.ProviderFactory;
import org.keycloak.provider.Spi;
import org.keycloak.services.clientpolicy.ClientPolicyManagerSpi;
import org.keycloak.services.clientpolicy.DefaultClientPolicyManagerFactory;
import org.keycloak.services.clientregistration.policy.ClientRegistrationPolicySpi;
import org.keycloak.services.clientregistration.policy.impl.*;
import org.keycloak.sessions.AuthenticationSessionSpi;

import java.util.Set;

/**
 * @author hmlnarik
 */
public class Map extends KeycloakModelParameters {

    static final Set<Class<? extends Spi>> ALLOWED_SPIS = ImmutableSet.<Class<? extends Spi>>builder()
        .add(AuthenticationSessionSpi.class)
        .add(SingleUseObjectSpi.class)
        .add(PublicKeyStorageSpi.class)
        .add(ClientPolicyManagerSpi.class)
        .add(KeySpi.class)
        .add(ClientRegistrationPolicySpi.class)
        .add(CredentialSpi.class)
        .add(PasswordPolicyManagerSpi.class)
        .add(PasswordHashSpi.class)
        .add(PasswordPolicySpi.class)
        .add(DeviceRepresentationSpi.class)
        .build();

    static final Set<Class<? extends ProviderFactory>> ALLOWED_FACTORIES = ImmutableSet.<Class<? extends ProviderFactory>>builder()
        .add(CassandraClientProviderFactory.class)
        .add(CassandraClientScopeProviderFactory.class)
        .add(CassandraGroupProviderFactory.class)
        .add(CassandraRealmsProviderFactory.class)
        .add(CassandraRoleProviderFactory.class)
        .add(CassandraAuthSessionProviderFactory.class)
        .add(MapDeploymentStateProviderFactory.class)
        .add(CassandraUserProviderFactory.class)
        .add(CassandraUserSessionProviderFactory.class)
        .add(CassandraLoginFailureProviderFactory.class)
        .add(NoneGlobalLockProviderFactory.class)
        .add(SingleUseObjectProviderFactory.class)
        .add(MapPublicKeyStorageProviderFactory.class)
        .add(DefaultClientPolicyManagerFactory.class)
        .add(GeneratedAesKeyProviderFactory.class)
        .add(GeneratedHmacKeyProviderFactory.class)
        .add(GeneratedEcdsaKeyProviderFactory.class)
        .add(ImportedRsaEncKeyProviderFactory.class)
        .add(ImportedRsaKeyProviderFactory.class)
        .add(GeneratedRsaEncKeyProviderFactory.class)
        .add(GeneratedRsaKeyProviderFactory.class)
        .add(ProtocolMappersClientRegistrationPolicyFactory.class)
        .add(ClientDisabledClientRegistrationPolicyFactory.class)
        .add(TrustedHostClientRegistrationPolicyFactory.class)
        .add(ConsentRequiredClientRegistrationPolicyFactory.class)
        .add(ClientScopesClientRegistrationPolicyFactory.class)
        .add(ScopeClientRegistrationPolicyFactory.class)
        .add(MaxClientsClientRegistrationPolicyFactory.class)
        .add(OTPCredentialProviderFactory.class)
        .add(PasswordCredentialProviderFactory.class)
        .add(DefaultPasswordPolicyManagerProviderFactory.class)
        .add(Pbkdf2Sha256PasswordHashProviderFactory.class)
        .add(HashAlgorithmPasswordPolicyProviderFactory.class)
        .add(HashIterationsPasswordPolicyProviderFactory.class)
        .add(HistoryPasswordPolicyProviderFactory.class)
        .add(ForceExpiredPasswordPolicyProviderFactory.class)
        .add(DeviceRepresentationProviderFactoryImpl.class)
        .build();

    public Map() {
        super(ALLOWED_SPIS, ALLOWED_FACTORIES);
    }

    @Override
    public void updateConfig(Config cf) {
        cf.spi("client-policy-manager").defaultProvider("default")
            .spi("password-hashing")
                .provider(Pbkdf2Sha256PasswordHashProviderFactory.ID)
            .spi("password-policy-manager").defaultProvider("default")
            .spi("password-policy")
                .provider(PasswordPolicy.PASSWORD_HISTORY_ID)
                .provider(PasswordPolicy.FORCE_EXPIRED_ID)
                .provider(PasswordPolicy.HASH_ALGORITHM_ID)
                .provider(PasswordPolicy.HASH_ITERATIONS_ID)
            .spi("credential")
                .provider(PasswordCredentialProviderFactory.PROVIDER_ID)
                .provider(OTPCredentialProviderFactory.PROVIDER_ID)
            .spi("keys")
                .provider(GeneratedAesKeyProviderFactory.ID)
                .provider(GeneratedHmacKeyProviderFactory.ID)
                .provider(GeneratedEcdsaKeyProviderFactory.ID)
                .provider(ImportedRsaEncKeyProviderFactory.ID)
                .provider(ImportedRsaKeyProviderFactory.ID)
                .provider(GeneratedRsaEncKeyProviderFactory.ID)
                .provider(GeneratedRsaKeyProviderFactory.ID)
            .spi("client-registration-policy")
                .provider(ProtocolMappersClientRegistrationPolicyFactory.PROVIDER_ID)
                .provider(ClientDisabledClientRegistrationPolicyFactory.PROVIDER_ID)
                .provider(TrustedHostClientRegistrationPolicyFactory.PROVIDER_ID)
                .provider(ConsentRequiredClientRegistrationPolicyFactory.PROVIDER_ID)
                .provider(ClientScopesClientRegistrationPolicyFactory.PROVIDER_ID)
                .provider(ScopeClientRegistrationPolicyFactory.PROVIDER_ID)
                .provider(MaxClientsClientRegistrationPolicyFactory.PROVIDER_ID)
            .spi(DeviceRepresentationSpi.NAME)
                .defaultProvider(DeviceRepresentationProviderFactoryImpl.PROVIDER_ID)
        ;
    }
}
