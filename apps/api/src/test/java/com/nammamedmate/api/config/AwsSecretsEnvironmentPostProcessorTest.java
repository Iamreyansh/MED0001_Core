package com.nammamedmate.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

class AwsSecretsEnvironmentPostProcessorTest {

  @Test
  void defaultConstructorAndOrder() {
    AwsSecretsEnvironmentPostProcessor processor = new AwsSecretsEnvironmentPostProcessor();
    assertThat(processor.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
  }

  @Test
  void skipsNonDeployedProfiles() {
    StandardEnvironment env = new StandardEnvironment();
    env.setActiveProfiles("local");
    SecretsManagerClient client = mock(SecretsManagerClient.class);
    new AwsSecretsEnvironmentPostProcessor(() -> client)
        .postProcessEnvironment(env, new SpringApplication());
    assertThat(env.getPropertySources().contains("medmateAwsSecrets")).isFalse();
    verify(client, never()).getSecretValue(any(GetSecretValueRequest.class));
  }

  @Test
  void deployedWithNoArnsAddsNothing() {
    SecretsManagerClient client = mock(SecretsManagerClient.class);
    StandardEnvironment env = new StandardEnvironment();
    env.setActiveProfiles("staging");

    new AwsSecretsEnvironmentPostProcessor(() -> client)
        .postProcessEnvironment(env, new SpringApplication());

    assertThat(env.getPropertySources().contains("medmateAwsSecrets")).isFalse();
    verify(client, never()).getSecretValue(any(GetSecretValueRequest.class));
  }

  @Test
  void blankArnsAreSkipped() {
    SecretsManagerClient client = mock(SecretsManagerClient.class);
    StandardEnvironment env = new StandardEnvironment();
    env.setActiveProfiles("prod");
    Map<String, Object> props = new HashMap<>();
    props.put("MEDMATE_SECRETS_DB_ARN", "   ");
    props.put("MEDMATE_SECRETS_JWT_ARN", "");
    props.put("MEDMATE_SECRETS_MFA_ARN", " ");
    props.put("MEDMATE_SECRETS_RAZORPAY_ARN", " ");
    props.put("MEDMATE_SECRETS_RAZORPAYX_ARN", " ");
    props.put("MEDMATE_SECRETS_KYC_ARN", " ");
    env.getPropertySources().addFirst(new MapPropertySource("test", props));

    new AwsSecretsEnvironmentPostProcessor(() -> client)
        .postProcessEnvironment(env, new SpringApplication());

    assertThat(env.getPropertySources().contains("medmateAwsSecrets")).isFalse();
    verify(client, never()).getSecretValue(any(GetSecretValueRequest.class));
  }

  @Test
  void loadsDbAndJwtFromSecretsManager() {
    SecretsManagerClient client = mock(SecretsManagerClient.class);
    when(client.getSecretValue(any(GetSecretValueRequest.class)))
        .thenAnswer(
            inv -> {
              String id = inv.getArgument(0, GetSecretValueRequest.class).secretId();
              String body =
                  id.contains("jwt")
                      ? "{\"private_key_pem\":\"PRIV\",\"public_key_pem\":\"PUB\"}"
                      : "{\"username\":\"u\",\"password\":\"p\"}";
              return GetSecretValueResponse.builder().secretString(body).build();
            });

    StandardEnvironment env = new StandardEnvironment();
    env.setActiveProfiles("staging");
    env.getPropertySources()
        .addFirst(
            new MapPropertySource(
                "test",
                Map.of(
                    "MEDMATE_SECRETS_DB_ARN", "arn:db",
                    "MEDMATE_SECRETS_JWT_ARN", "arn:jwt")));

    new AwsSecretsEnvironmentPostProcessor(() -> client)
        .postProcessEnvironment(env, new SpringApplication());

    assertThat(env.getProperty("spring.datasource.username")).isEqualTo("u");
    assertThat(env.getProperty("spring.datasource.password")).isEqualTo("p");
    assertThat(env.getProperty("medmate.jwt.private-key-pem")).isEqualTo("PRIV");
    assertThat(env.getProperty("medmate.jwt.public-key-pem")).isEqualTo("PUB");
  }

  @Test
  void loadsDbJwtAndMfaFromSecretsManager() {
    SecretsManagerClient client = mock(SecretsManagerClient.class);
    when(client.getSecretValue(any(GetSecretValueRequest.class)))
        .thenAnswer(
            inv -> {
              String id = inv.getArgument(0, GetSecretValueRequest.class).secretId();
              String body;
              if (id.contains("jwt")) {
                body = "{\"private_key_pem\":\"PRIV\",\"public_key_pem\":\"PUB\"}";
              } else if (id.contains("mfa")) {
                body =
                    "{\"encryption_key_base64\":\"QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=\","
                        + "\"payment_encryption_key_base64\":\"QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI=\","
                        + "\"teleconsult_encryption_key_base64\":\"Q0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0M=\"}";
              } else {
                body = "{\"username\":\"u\",\"password\":\"p\"}";
              }
              return GetSecretValueResponse.builder().secretString(body).build();
            });

    StandardEnvironment env = new StandardEnvironment();
    env.setActiveProfiles("staging");
    env.getPropertySources()
        .addFirst(
            new MapPropertySource(
                "test",
                Map.of(
                    "MEDMATE_SECRETS_DB_ARN", "arn:db",
                    "MEDMATE_SECRETS_JWT_ARN", "arn:jwt",
                    "MEDMATE_SECRETS_MFA_ARN", "arn:mfa")));

    new AwsSecretsEnvironmentPostProcessor(() -> client)
        .postProcessEnvironment(env, new SpringApplication());

    assertThat(env.getProperty("spring.datasource.username")).isEqualTo("u");
    assertThat(env.getProperty("medmate.jwt.private-key-pem")).isEqualTo("PRIV");
    assertThat(env.getProperty("medmate.mfa.encryption-key-base64"))
        .isEqualTo("QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=");
    assertThat(env.getProperty("medmate.payment.encryption-key-base64"))
        .isEqualTo("QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI=");
    assertThat(env.getProperty("medmate.teleconsult.encryption-key-base64"))
        .isEqualTo("Q0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0M=");
  }

  @Test
  void loadsKycWebhookSecretFromSecretsManager() {
    SecretsManagerClient client = mock(SecretsManagerClient.class);
    when(client.getSecretValue(any(GetSecretValueRequest.class)))
        .thenReturn(
            GetSecretValueResponse.builder()
                .secretString("{\"webhook_secret\":\"prod-kyc-hmac-secret\"}")
                .build());

    StandardEnvironment env = new StandardEnvironment();
    env.setActiveProfiles("prod");
    env.getPropertySources()
        .addFirst(new MapPropertySource("test", Map.of("MEDMATE_SECRETS_KYC_ARN", "arn:kyc")));

    new AwsSecretsEnvironmentPostProcessor(() -> client)
        .postProcessEnvironment(env, new SpringApplication());

    assertThat(env.getProperty("medmate.kyc.webhook-secret")).isEqualTo("prod-kyc-hmac-secret");
  }

  @Test
  void loadsRazorpayFromSecretsManager() {
    SecretsManagerClient client = mock(SecretsManagerClient.class);
    when(client.getSecretValue(any(GetSecretValueRequest.class)))
        .thenReturn(
            GetSecretValueResponse.builder()
                .secretString(
                    "{\"key_id\":\"rzp_test\",\"key_secret\":\"sec\",\"webhook_secret\":\"whsec\"}")
                .build());

    StandardEnvironment env = new StandardEnvironment();
    env.setActiveProfiles("prod");
    env.getPropertySources()
        .addFirst(
            new MapPropertySource("test", Map.of("MEDMATE_SECRETS_RAZORPAY_ARN", "arn:razorpay")));

    new AwsSecretsEnvironmentPostProcessor(() -> client)
        .postProcessEnvironment(env, new SpringApplication());

    assertThat(env.getProperty("medmate.razorpay.key-id")).isEqualTo("rzp_test");
    assertThat(env.getProperty("medmate.razorpay.key-secret")).isEqualTo("sec");
    assertThat(env.getProperty("medmate.razorpay.webhook-secret")).isEqualTo("whsec");
  }

  @Test
  void loadsRazorpayxWebhookSecretFromSecretsManager() {
    SecretsManagerClient client = mock(SecretsManagerClient.class);
    when(client.getSecretValue(any(GetSecretValueRequest.class)))
        .thenReturn(
            GetSecretValueResponse.builder()
                .secretString(
                    "{\"key_id\":\"rzp_x\",\"key_secret\":\"xsec\",\"webhook_secret\":\"prod-razorpayx-hmac-secret\"}")
                .build());

    StandardEnvironment env = new StandardEnvironment();
    env.setActiveProfiles("staging");
    env.getPropertySources()
        .addFirst(
            new MapPropertySource(
                "test", Map.of("MEDMATE_SECRETS_RAZORPAYX_ARN", "arn:razorpayx")));

    new AwsSecretsEnvironmentPostProcessor(() -> client)
        .postProcessEnvironment(env, new SpringApplication());

    assertThat(env.getProperty("medmate.razorpayx.key-id")).isEqualTo("rzp_x");
    assertThat(env.getProperty("medmate.razorpayx.key-secret")).isEqualTo("xsec");
    assertThat(env.getProperty("medmate.razorpayx.webhook-secret"))
        .isEqualTo("prod-razorpayx-hmac-secret");
  }

  @Test
  void mfaWithoutPaymentKey_skipsPaymentProperty() {
    SecretsManagerClient client = mock(SecretsManagerClient.class);
    when(client.getSecretValue(any(GetSecretValueRequest.class)))
        .thenReturn(
            GetSecretValueResponse.builder()
                .secretString(
                    "{\"encryption_key_base64\":\"QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=\"}")
                .build());

    StandardEnvironment env = new StandardEnvironment();
    env.setActiveProfiles("staging");
    env.getPropertySources()
        .addFirst(new MapPropertySource("test", Map.of("MEDMATE_SECRETS_MFA_ARN", "arn:mfa")));

    new AwsSecretsEnvironmentPostProcessor(() -> client)
        .postProcessEnvironment(env, new SpringApplication());

    assertThat(env.getProperty("medmate.mfa.encryption-key-base64")).isNotBlank();
    assertThat(env.getProperty("medmate.payment.encryption-key-base64")).isNull();
  }

  @Test
  void mfaWithNullOrBlankPaymentKey_skipsPaymentProperty() {
    SecretsManagerClient client = mock(SecretsManagerClient.class);
    when(client.getSecretValue(any(GetSecretValueRequest.class)))
        .thenReturn(
            GetSecretValueResponse.builder()
                .secretString(
                    "{\"encryption_key_base64\":\"QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=\","
                        + "\"payment_encryption_key_base64\":null,"
                        + "\"teleconsult_encryption_key_base64\":null}")
                .build())
        .thenReturn(
            GetSecretValueResponse.builder()
                .secretString(
                    "{\"encryption_key_base64\":\"QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=\","
                        + "\"payment_encryption_key_base64\":\"   \","
                        + "\"teleconsult_encryption_key_base64\":\"   \"}")
                .build());

    StandardEnvironment envNull = new StandardEnvironment();
    envNull.setActiveProfiles("staging");
    envNull
        .getPropertySources()
        .addFirst(new MapPropertySource("test", Map.of("MEDMATE_SECRETS_MFA_ARN", "arn:mfa-null")));
    new AwsSecretsEnvironmentPostProcessor(() -> client)
        .postProcessEnvironment(envNull, new SpringApplication());
    assertThat(envNull.getProperty("medmate.payment.encryption-key-base64")).isNull();
    assertThat(envNull.getProperty("medmate.teleconsult.encryption-key-base64")).isNull();

    StandardEnvironment envBlank = new StandardEnvironment();
    envBlank.setActiveProfiles("prod");
    envBlank
        .getPropertySources()
        .addFirst(
            new MapPropertySource("test", Map.of("MEDMATE_SECRETS_MFA_ARN", "arn:mfa-blank")));
    new AwsSecretsEnvironmentPostProcessor(() -> client)
        .postProcessEnvironment(envBlank, new SpringApplication());
    assertThat(envBlank.getProperty("medmate.payment.encryption-key-base64")).isNull();
    assertThat(envBlank.getProperty("medmate.teleconsult.encryption-key-base64")).isNull();
  }

  @Test
  void loadsOnlyJwtWhenDbArnAbsent() {
    SecretsManagerClient client = mock(SecretsManagerClient.class);
    when(client.getSecretValue(any(GetSecretValueRequest.class)))
        .thenReturn(
            GetSecretValueResponse.builder()
                .secretString("{\"private_key_pem\":\"PRIV\",\"public_key_pem\":\"PUB\"}")
                .build());

    StandardEnvironment env = new StandardEnvironment();
    env.setActiveProfiles("staging");
    env.getPropertySources()
        .addFirst(new MapPropertySource("test", Map.of("MEDMATE_SECRETS_JWT_ARN", "arn:jwt")));

    new AwsSecretsEnvironmentPostProcessor(() -> client)
        .postProcessEnvironment(env, new SpringApplication());

    assertThat(env.getProperty("medmate.jwt.private-key-pem")).isEqualTo("PRIV");
    assertThat(env.getProperty("spring.datasource.username")).isNull();
  }

  @Test
  void failsWhenSecretJsonUnreadable() {
    SecretsManagerClient client = mock(SecretsManagerClient.class);
    when(client.getSecretValue(any(GetSecretValueRequest.class)))
        .thenReturn(GetSecretValueResponse.builder().secretString("not-json").build());

    StandardEnvironment env = new StandardEnvironment();
    env.setActiveProfiles("staging");
    env.getPropertySources()
        .addFirst(new MapPropertySource("test", Map.of("MEDMATE_SECRETS_DB_ARN", "arn:db")));

    assertThatThrownBy(
            () ->
                new AwsSecretsEnvironmentPostProcessor(() -> client)
                    .postProcessEnvironment(env, new SpringApplication()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Failed to load secrets")
        .cause()
        .isInstanceOf(java.io.IOException.class);
  }

  @Test
  void failsWhenSecretFieldMissing() {
    SecretsManagerClient client = mock(SecretsManagerClient.class);
    when(client.getSecretValue(any(GetSecretValueRequest.class)))
        .thenReturn(GetSecretValueResponse.builder().secretString("{\"username\":\"u\"}").build());

    StandardEnvironment env = new StandardEnvironment();
    env.setActiveProfiles("prod");
    env.getPropertySources()
        .addFirst(new MapPropertySource("test", Map.of("MEDMATE_SECRETS_DB_ARN", "arn:db")));

    assertThatThrownBy(
            () ->
                new AwsSecretsEnvironmentPostProcessor(() -> client)
                    .postProcessEnvironment(env, new SpringApplication()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Failed to load secrets");
  }

  @Test
  void failsWhenSecretFieldBlank() {
    SecretsManagerClient client = mock(SecretsManagerClient.class);
    when(client.getSecretValue(any(GetSecretValueRequest.class)))
        .thenReturn(
            GetSecretValueResponse.builder()
                .secretString("{\"username\":\"u\",\"password\":\"  \"}")
                .build());

    StandardEnvironment env = new StandardEnvironment();
    env.setActiveProfiles("staging");
    env.getPropertySources()
        .addFirst(new MapPropertySource("test", Map.of("MEDMATE_SECRETS_DB_ARN", "arn:db")));

    assertThatThrownBy(
            () ->
                new AwsSecretsEnvironmentPostProcessor(() -> client)
                    .postProcessEnvironment(env, new SpringApplication()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Failed to load secrets");
  }

  @Test
  void failsWhenSecretFieldNull() {
    SecretsManagerClient client = mock(SecretsManagerClient.class);
    when(client.getSecretValue(any(GetSecretValueRequest.class)))
        .thenReturn(
            GetSecretValueResponse.builder()
                .secretString("{\"username\":\"u\",\"password\":null}")
                .build());

    StandardEnvironment env = new StandardEnvironment();
    env.setActiveProfiles("staging");
    env.getPropertySources()
        .addFirst(new MapPropertySource("test", Map.of("MEDMATE_SECRETS_DB_ARN", "arn:db")));

    assertThatThrownBy(
            () ->
                new AwsSecretsEnvironmentPostProcessor(() -> client)
                    .postProcessEnvironment(env, new SpringApplication()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Failed to load secrets");
  }

  @Test
  void isDeployedDetectsProfiles() {
    StandardEnvironment staging = new StandardEnvironment();
    staging.setActiveProfiles("staging");
    assertThat(AwsSecretsEnvironmentPostProcessor.isDeployed(staging)).isTrue();

    StandardEnvironment prod = new StandardEnvironment();
    prod.setActiveProfiles("prod");
    assertThat(AwsSecretsEnvironmentPostProcessor.isDeployed(prod)).isTrue();

    StandardEnvironment local = new StandardEnvironment();
    local.setActiveProfiles("local");
    assertThat(AwsSecretsEnvironmentPostProcessor.isDeployed(local)).isFalse();

    StandardEnvironment none = new StandardEnvironment();
    assertThat(AwsSecretsEnvironmentPostProcessor.isDeployed(none)).isFalse();
  }

  @Test
  void isDeployedReadsProfileProperties() {
    ConfigurableEnvironment commaSeparated = mock(ConfigurableEnvironment.class);
    when(commaSeparated.getActiveProfiles()).thenReturn(new String[0]);
    when(commaSeparated.getProperty("spring.profiles.active")).thenReturn("local, prod");
    assertThat(AwsSecretsEnvironmentPostProcessor.isDeployed(commaSeparated)).isTrue();

    ConfigurableEnvironment environmentVariable = mock(ConfigurableEnvironment.class);
    when(environmentVariable.getActiveProfiles()).thenReturn(new String[0]);
    when(environmentVariable.getProperty("spring.profiles.active")).thenReturn(" ");
    when(environmentVariable.getProperty("SPRING_PROFILES_ACTIVE")).thenReturn("staging");
    assertThat(AwsSecretsEnvironmentPostProcessor.isDeployed(environmentVariable)).isTrue();

    ConfigurableEnvironment local = mock(ConfigurableEnvironment.class);
    when(local.getActiveProfiles()).thenReturn(new String[0]);
    when(local.getProperty("spring.profiles.active")).thenReturn("local,dev");
    assertThat(AwsSecretsEnvironmentPostProcessor.isDeployed(local)).isFalse();
  }
}
