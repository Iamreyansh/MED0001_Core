package com.nammamedmate.integration.adapter.out.persistence;

import com.nammamedmate.integration.application.port.out.CommunicationSecretsStore;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class LocalCommunicationSecretsStore implements CommunicationSecretsStore {

  private final ConcurrentHashMap<String, Map<String, String>> secrets = new ConcurrentHashMap<>();

  public LocalCommunicationSecretsStore() {
    secrets.put("medmate/comms/push", Map.of("api_key", "fcm-stub-key-0001"));
    secrets.put("medmate/comms/sms", Map.of("api_key", "msg91-stub-key", "sender_id", "NMMATE"));
    secrets.put("medmate/comms/whatsapp", Map.of("api_key", "meta-stub-token"));
    secrets.put("medmate/comms/email", Map.of("api_key", "sg-stub-key-0001"));
  }

  @Override
  public Optional<Map<String, String>> get(String secretsManagerKey) {
    Map<String, String> value = secrets.get(secretsManagerKey);
    return value == null ? Optional.empty() : Optional.of(Map.copyOf(value));
  }

  @Override
  public void put(String secretsManagerKey, Map<String, String> credentials) {
    secrets.put(secretsManagerKey, Map.copyOf(credentials));
  }
}
