package com.nammamedmate.integration.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class LocalCommunicationSecretsStoreTest {

  @Test
  void getPutAndMissingKey() {
    LocalCommunicationSecretsStore store = new LocalCommunicationSecretsStore();
    assertThat(store.get("medmate/comms/sms")).isPresent();
    assertThat(store.get("missing")).isEmpty();
    store.put("medmate/comms/sms", Map.of("api_key", "rotated", "sender_id", "NMMATE"));
    assertThat(store.get("medmate/comms/sms").orElseThrow().get("api_key")).isEqualTo("rotated");
    assertThat(store.get("medmate/comms/push").orElseThrow().get("api_key"))
        .isEqualTo("fcm-stub-key-0001");
  }
}
