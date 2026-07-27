package com.nammamedmate.pharmacy.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KycRequestSanitizerTest {

  @Test
  void redactsNestedSecrets() {
    assertThat(KycRequestSanitizer.sanitise(Map.of("nested", Map.of("secret", "x", "field", "ok"))))
        .containsKey("nested");
    assertThat(KycRequestSanitizer.sanitise(Map.of("Authorization", "Bearer x", "gstin", "27TEST")))
        .containsEntry("Authorization", "[REDACTED]");
    Map<String, Object> withNullKey = new LinkedHashMap<>();
    withNullKey.put(null, "skip");
    withNullKey.put("gstin", "27TEST");
    assertThat(KycRequestSanitizer.sanitise(withNullKey)).containsEntry("gstin", "27TEST");
  }
}
