package com.nammamedmate.integration.application.port.out;

import java.util.Map;
import java.util.Optional;

/** Local stand-in for AWS Secrets Manager (secrets_manager_key → credential map). */
public interface CommunicationSecretsStore {

  Optional<Map<String, String>> get(String secretsManagerKey);

  void put(String secretsManagerKey, Map<String, String> credentials);
}
