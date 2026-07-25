package com.nammamedmate.api.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

/**
 * For staging/prod: load DB + JWT + MFA material from Secrets Manager ARNs set on the ECS task.
 * Avoids stuffing PEMs / keys into task environment variables.
 */
public class AwsSecretsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Set<String> DEPLOYED = Set.of("staging", "prod");

  private final Supplier<SecretsManagerClient> clientSupplier;

  public AwsSecretsEnvironmentPostProcessor() {
    this(SecretsManagerClient::create);
  }

  AwsSecretsEnvironmentPostProcessor(Supplier<SecretsManagerClient> clientSupplier) {
    this.clientSupplier = clientSupplier;
  }

  @Override
  public void postProcessEnvironment(
      ConfigurableEnvironment environment, SpringApplication application) {
    if (!isDeployed(environment)) {
      return;
    }

    Map<String, Object> props = new HashMap<>();
    try (SecretsManagerClient client = clientSupplier.get()) {
      String dbArn = environment.getProperty("MEDMATE_SECRETS_DB_ARN");
      if (dbArn != null && !dbArn.isBlank()) {
        JsonNode db = MAPPER.readTree(getSecret(client, dbArn));
        props.put("spring.datasource.username", text(db, "username"));
        props.put("spring.datasource.password", text(db, "password"));
      }
      String jwtArn = environment.getProperty("MEDMATE_SECRETS_JWT_ARN");
      if (jwtArn != null && !jwtArn.isBlank()) {
        JsonNode jwt = MAPPER.readTree(getSecret(client, jwtArn));
        props.put("medmate.jwt.private-key-pem", text(jwt, "private_key_pem"));
        props.put("medmate.jwt.public-key-pem", text(jwt, "public_key_pem"));
      }
      String mfaArn = environment.getProperty("MEDMATE_SECRETS_MFA_ARN");
      if (mfaArn != null && !mfaArn.isBlank()) {
        JsonNode mfa = MAPPER.readTree(getSecret(client, mfaArn));
        props.put("medmate.mfa.encryption-key-base64", text(mfa, "encryption_key_base64"));
      }
    } catch (Exception e) {
      throw new IllegalStateException("Failed to load secrets for deployed profile", e);
    }
    if (!props.isEmpty()) {
      environment.getPropertySources().addFirst(new MapPropertySource("medmateAwsSecrets", props));
    }
  }

  static boolean isDeployed(ConfigurableEnvironment environment) {
    for (String p : environment.getActiveProfiles()) {
      if (DEPLOYED.contains(p)) {
        return true;
      }
    }
    // EPP may run before profiles are copied into getActiveProfiles(); also honor the property/env.
    String raw = environment.getProperty("spring.profiles.active");
    if (raw == null || raw.isBlank()) {
      raw = environment.getProperty("SPRING_PROFILES_ACTIVE");
    }
    if (raw != null) {
      for (String p : raw.split(",")) {
        if (DEPLOYED.contains(p.trim())) {
          return true;
        }
      }
    }
    return false;
  }

  private static String getSecret(SecretsManagerClient client, String arn) {
    return client
        .getSecretValue(GetSecretValueRequest.builder().secretId(arn).build())
        .secretString();
  }

  private static String text(JsonNode node, String field) {
    JsonNode v = node.get(field);
    if (v == null || v.isNull() || v.asText().isBlank()) {
      throw new IllegalStateException("Secret missing field: " + field);
    }
    return v.asText();
  }

  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
}
