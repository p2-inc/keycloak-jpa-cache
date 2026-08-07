package io.phasetwo.keycloak.redis.testsuite.parameters;

import com.google.common.collect.ImmutableSet;
import io.phasetwo.keycloak.compatibility.MapPublicKeyStorageProviderFactory;
import io.phasetwo.keycloak.redis.RedisDatastoreProviderFactory;
import io.phasetwo.keycloak.redis.RedisStoreConfig;
import io.phasetwo.keycloak.redis.authSession.RedisAuthenticationSessionProviderFactory;
import io.phasetwo.keycloak.redis.connection.DefaultRedisConnectionProviderFactory;
import io.phasetwo.keycloak.redis.connection.RedisConnectionProviderFactory;
import io.phasetwo.keycloak.redis.connection.RedisConnectionSpi;
import io.phasetwo.keycloak.redis.loginFailure.RedisUserLoginFailureProviderFactory;
import io.phasetwo.keycloak.redis.revokedToken.RedisRevokedTokenProviderFactory;
import io.phasetwo.keycloak.redis.singleUseObject.RedisSingleUseObjectProviderFactory;
import io.phasetwo.keycloak.redis.testsuite.Config;
import io.phasetwo.keycloak.redis.testsuite.KeycloakModelParameters;
import io.phasetwo.keycloak.redis.userSession.RedisUserSessionProviderFactory;
import java.util.Set;
import org.keycloak.authorization.jpa.store.JPAAuthorizationStoreFactory;
import org.keycloak.broker.provider.IdentityProviderFactory;
import org.keycloak.cache.DefaultLocalCacheProviderFactory;
import org.keycloak.cache.LocalCacheSPI;
import org.keycloak.connections.jpa.*;
import org.keycloak.connections.jpa.entityprovider.JpaEntitySpi;
import org.keycloak.connections.jpa.updater.JpaUpdaterProviderFactory;
import org.keycloak.connections.jpa.updater.JpaUpdaterSpi;
import org.keycloak.connections.jpa.updater.liquibase.conn.LiquibaseConnectionProviderFactory;
import org.keycloak.connections.jpa.updater.liquibase.conn.LiquibaseConnectionSpi;
import org.keycloak.connections.jpa.updater.liquibase.lock.LiquibaseDBLockProviderFactory;
import org.keycloak.credential.CredentialSpi;
import org.keycloak.credential.OTPCredentialProviderFactory;
import org.keycloak.credential.PasswordCredentialProviderFactory;
import org.keycloak.credential.hash.PasswordHashSpi;
import org.keycloak.credential.hash.Pbkdf2Sha256PasswordHashProviderFactory;
import org.keycloak.credential.hash.Pbkdf2Sha512PasswordHashProviderFactory;
import org.keycloak.device.DeviceRepresentationProviderFactoryImpl;
import org.keycloak.device.DeviceRepresentationSpi;
import org.keycloak.events.jpa.JpaEventStoreProviderFactory;
import org.keycloak.keys.*;
import org.keycloak.migration.MigrationProviderFactory;
import org.keycloak.migration.MigrationSpi;
import org.keycloak.models.*;
import org.keycloak.models.DeploymentStateSpi;
import org.keycloak.models.dblock.DBLockSpi;
import org.keycloak.models.jpa.*;
import org.keycloak.models.jpa.session.JpaUserSessionPersisterProviderFactory;
import org.keycloak.models.session.UserSessionPersisterSpi;
import org.keycloak.policy.*;
import org.keycloak.protocol.LoginProtocolFactory;
import org.keycloak.protocol.LoginProtocolSpi;
import org.keycloak.provider.ProviderFactory;
import org.keycloak.provider.Spi;
import org.keycloak.services.clientpolicy.ClientPolicyManagerSpi;
import org.keycloak.services.clientpolicy.DefaultClientPolicyManagerFactory;
import org.keycloak.services.clientregistration.policy.ClientRegistrationPolicySpi;
import org.keycloak.services.clientregistration.policy.impl.*;
import org.keycloak.sessions.AuthenticationSessionSpi;
import org.keycloak.storage.DatastoreSpi;
import org.keycloak.storage.datastore.DefaultDatastoreProviderFactory;
import org.keycloak.tracing.NoopTracingProviderFactory;
import org.keycloak.tracing.TracingSpi;
import org.keycloak.userprofile.DeclarativeUserProfileProviderFactory;
import org.keycloak.userprofile.UserProfileSpi;
import org.keycloak.userprofile.validator.AttributeRequiredByMetadataValidator;
import org.keycloak.userprofile.validator.BlankAttributeValidator;
import org.keycloak.userprofile.validator.BrokeringFederatedUsernameHasValueValidator;
import org.keycloak.userprofile.validator.DuplicateEmailValidator;
import org.keycloak.userprofile.validator.DuplicateUsernameValidator;
import org.keycloak.userprofile.validator.EmailExistsAsUsernameValidator;
import org.keycloak.userprofile.validator.ImmutableAttributeValidator;
import org.keycloak.userprofile.validator.MultiValueValidator;
import org.keycloak.userprofile.validator.PersonNameProhibitedCharactersValidator;
import org.keycloak.userprofile.validator.ReadOnlyAttributeUnchangedValidator;
import org.keycloak.userprofile.validator.RegistrationEmailAsUsernameEmailValueValidator;
import org.keycloak.userprofile.validator.RegistrationEmailAsUsernameUsernameValueValidator;
import org.keycloak.userprofile.validator.RegistrationUsernameExistsValidator;
import org.keycloak.userprofile.validator.UsernameHasValueValidator;
import org.keycloak.userprofile.validator.UsernameIDNHomographValidator;
import org.keycloak.userprofile.validator.UsernameMutationValidator;
import org.keycloak.userprofile.validator.UsernameProhibitedCharactersValidator;
import org.keycloak.validate.ValidatorFactory;
import org.keycloak.validate.ValidatorSPI;
import org.testcontainers.containers.GenericContainer;

public class RedisParameters extends KeycloakModelParameters {
  public static final Boolean START_CONTAINER =
      Boolean.valueOf(System.getProperty("keycloak.testsuite.start-redis-container", "true"));

  private final GenericContainer redisContainer = createValkeyContainer();

  static final Set<Class<? extends Spi>> ALLOWED_SPIS =
      ImmutableSet.<Class<? extends Spi>>builder()
          .add(AuthenticationSessionSpi.class)
          .add(ClientPolicyManagerSpi.class)
          .add(ClientRegistrationPolicySpi.class)
          .add(CredentialSpi.class)
          .add(DatastoreSpi.class)
          .add(DeploymentStateSpi.class)
          .add(DeviceRepresentationSpi.class)
          .add(DBLockSpi.class)
          .add(JpaConnectionSpi.class)
          .add(JpaEntitySpi.class)
          .add(JpaUpdaterSpi.class)
          .add(KeySpi.class)
          .add(LiquibaseConnectionSpi.class)
          .add(LoginProtocolSpi.class)
          .add(MigrationSpi.class)
          .add(PasswordHashSpi.class)
          .add(PasswordPolicyManagerSpi.class)
          .add(PasswordPolicySpi.class)
          .add(PublicKeyStorageSpi.class)
          .add(RevokedTokenSpi.class)
          .add(SingleUseObjectSpi.class)
          .add(UserSessionPersisterSpi.class)
          .add(UserProfileSpi.class)
          .add(ValidatorSPI.class)
          .add(TracingSpi.class)
          .add(IdentityProviderStorageSpi.class)
          .add(RedisConnectionSpi.class)
          .add(LocalCacheSPI.class)
          .build();

  static final Set<Class<? extends ProviderFactory>> ALLOWED_FACTORIES =
      ImmutableSet.<Class<? extends ProviderFactory>>builder()
          .add(RedisRevokedTokenProviderFactory.class)
          .add(RedisSingleUseObjectProviderFactory.class)
          .add(RedisUserLoginFailureProviderFactory.class)
          .add(RedisUserSessionProviderFactory.class)
          .add(RedisAuthenticationSessionProviderFactory.class)
          .add(RedisConnectionProviderFactory.class)
          .add(RedisDatastoreProviderFactory.class)
          .add(ClientDisabledClientRegistrationPolicyFactory.class)
          .add(ClientScopesClientRegistrationPolicyFactory.class)
          .add(ConsentRequiredClientRegistrationPolicyFactory.class)
          .add(DefaultClientPolicyManagerFactory.class)
          .add(DefaultDatastoreProviderFactory.class)
          .add(DefaultJpaConnectionProviderFactory.class)
          .add(DefaultPasswordPolicyManagerProviderFactory.class)
          .add(DeviceRepresentationProviderFactoryImpl.class)
          .add(DefaultLocalCacheProviderFactory.class)
          .add(ForceExpiredPasswordPolicyProviderFactory.class)
          .add(GeneratedAesKeyProviderFactory.class)
          .add(GeneratedEcdsaKeyProviderFactory.class)
          .add(GeneratedHmacKeyProviderFactory.class)
          .add(GeneratedRsaEncKeyProviderFactory.class)
          .add(GeneratedRsaKeyProviderFactory.class)
          .add(HashAlgorithmPasswordPolicyProviderFactory.class)
          .add(HashIterationsPasswordPolicyProviderFactory.class)
          .add(HistoryPasswordPolicyProviderFactory.class)
          .add(IdentityProviderFactory.class)
          .add(ImportedRsaEncKeyProviderFactory.class)
          .add(ImportedRsaKeyProviderFactory.class)
          .add(JPAAuthorizationStoreFactory.class)
          .add(JpaClientProviderFactory.class)
          .add(JpaClientScopeProviderFactory.class)
          .add(JpaEventStoreProviderFactory.class)
          .add(JpaGroupProviderFactory.class)
          .add(JpaRealmProviderFactory.class)
          .add(JpaRoleProviderFactory.class)
          .add(JpaUpdaterProviderFactory.class)
          .add(JpaUserProviderFactory.class)
          .add(JpaUserSessionPersisterProviderFactory.class)
          .add(LiquibaseConnectionProviderFactory.class)
          .add(LiquibaseDBLockProviderFactory.class)
          .add(JpaDeploymentStateProviderFactory.class)
          .add(LoginProtocolFactory.class)
          .add(MapPublicKeyStorageProviderFactory.class)
          .add(MaxClientsClientRegistrationPolicyFactory.class)
          .add(MigrationProviderFactory.class)
          .add(OTPCredentialProviderFactory.class)
          .add(PasswordCredentialProviderFactory.class)
          .add(Pbkdf2Sha256PasswordHashProviderFactory.class)
          .add(Pbkdf2Sha512PasswordHashProviderFactory.class)
          .add(ProtocolMappersClientRegistrationPolicyFactory.class)
          .add(ScopeClientRegistrationPolicyFactory.class)
          .add(TrustedHostClientRegistrationPolicyFactory.class)
          .add(DeclarativeUserProfileProviderFactory.class)
          .add(ValidatorFactory.class)
          .add(NoopTracingProviderFactory.class)
          .add(JpaIdentityProviderStorageProviderFactory.class)
          .add(RegistrationWebOriginsPolicyFactory.class)
          .build();

  public RedisParameters() {
    super(ALLOWED_SPIS, ALLOWED_FACTORIES);
  }

  @Override
  public void updateConfig(Config cf) {
    cf.spi("client")
        .defaultProvider("jpa")
        .spi("clientScope")
        .defaultProvider("jpa")
        .spi("group")
        .defaultProvider("jpa")
        .spi("idp")
        .defaultProvider("jpa")
        .spi("role")
        .defaultProvider("jpa")
        .spi("user")
        .defaultProvider("jpa")
        .spi("realm")
        .defaultProvider("jpa")
        .spi("deploymentState")
        .defaultProvider("jpa")
        .spi("dblock")
        .defaultProvider("jpa")
        .provider(Pbkdf2Sha512PasswordHashProviderFactory.ID)
        .spi(UserProfileSpi.ID)
        .defaultProvider(DeclarativeUserProfileProviderFactory.ID)
        .spi("validator")
        .provider(BlankAttributeValidator.ID)
        .provider(AttributeRequiredByMetadataValidator.ID)
        .provider(ReadOnlyAttributeUnchangedValidator.ID)
        .provider(DuplicateUsernameValidator.ID)
        .provider(UsernameHasValueValidator.ID)
        .provider(UsernameIDNHomographValidator.ID)
        .provider(UsernameMutationValidator.ID)
        .provider(DuplicateEmailValidator.ID)
        .provider(EmailExistsAsUsernameValidator.ID)
        .provider(RegistrationEmailAsUsernameUsernameValueValidator.ID)
        .provider(RegistrationUsernameExistsValidator.ID)
        .provider(RegistrationEmailAsUsernameEmailValueValidator.ID)
        .provider(BrokeringFederatedUsernameHasValueValidator.ID)
        .provider(ImmutableAttributeValidator.ID)
        .provider(UsernameProhibitedCharactersValidator.ID)
        .provider(PersonNameProhibitedCharactersValidator.ID)
        .provider(MultiValueValidator.ID);

    cf.spi("datastore")
        .defaultProvider(RedisStoreConfig.DATASTORE_PROVIDER_ID)
        .config("dir", "${project.build.directory:target}");

    cf.spi(RedisConnectionSpi.NAME)
        .provider(DefaultRedisConnectionProviderFactory.PROVIDER_ID)
        .config(
            "nodes",
            START_CONTAINER
                ? redisContainer.getHost() + ":" + redisContainer.getMappedPort(6379)
                : "redis:6379");
  }

  @Override
  public void beforeSuite(Config cf) {
    if (START_CONTAINER) {
      redisContainer.start();
    }
  }

  @Override
  public void afterSuite() {
    if (START_CONTAINER) {
      redisContainer.stop();
    }
  }

  private static GenericContainer createValkeyContainer() {
    return new GenericContainer<>("valkey/valkey:8.1.5")
        .withCommand("valkey-server", "--appendonly", "no", "--protected-mode", "no")
        .withExposedPorts(6379);
  }
}
