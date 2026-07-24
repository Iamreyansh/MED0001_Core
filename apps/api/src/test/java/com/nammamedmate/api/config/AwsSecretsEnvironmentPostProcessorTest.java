package com.nammamedmate.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.StandardEnvironment;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

class AwsSecretsEnvironmentPostProcessorTest {

  @Test
  void skipsNonDeployedProfiles() {
    StandardEnvironment env = new StandardEnvironment();
    env.setActiveProfiles("local");
    new AwsSecretsEnvironmentPostProcessor(() -> mock(SecretsManagerClient.class))
        .postProcessEnvironment(env, new SpringApplication());
    assertThat(env.getPropertySources().contains("medmateAwsSecrets")).isFalse();
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
            new org.springframework.core.env.MapPropertySource(
                "test",
                java.util.Map.of(
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
  void failsWhenSecretFieldMissing() {
    SecretsManagerClient client = mock(SecretsManagerClient.class);
    when(client.getSecretValue(any(GetSecretValueRequest.class)))
        .thenReturn(GetSecretValueResponse.builder().secretString("{\"username\":\"u\"}").build());

    StandardEnvironment env = new StandardEnvironment();
    env.setActiveProfiles("prod");
    env.getPropertySources()
        .addFirst(
            new org.springframework.core.env.MapPropertySource(
                "test", java.util.Map.of("MEDMATE_SECRETS_DB_ARN", "arn:db")));

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

    StandardEnvironment local = new StandardEnvironment();
    local.setActiveProfiles("local");
    assertThat(AwsSecretsEnvironmentPostProcessor.isDeployed(local)).isFalse();
  }
}
