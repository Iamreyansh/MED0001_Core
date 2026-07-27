package com.nammamedmate.pharmacy.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KycGovernmentApiPortTest {

  @Test
  void licenceExpiringSoonHandlesNullExpiry() {
    assertThat(KycGovernmentApiPort.isLicenceExpiringSoon(null, LocalDate.now())).isFalse();
  }

  @Test
  void kycCheckResultNormalisesNullCollections() {
    KycGovernmentApiPort.KycCheckResult result =
        new KycGovernmentApiPort.KycCheckResult("PASS", "PROVIDER", null, null, null, null, false);
    assertThat(result.requestPayload()).isEmpty();
    assertThat(result.responsePayload()).isNull();
    assertThat(result.details()).isNull();
    assertThat(result.adminFlags()).isEmpty();

    KycGovernmentApiPort.KycCheckResult populated =
        new KycGovernmentApiPort.KycCheckResult(
            "PASS",
            "PROVIDER",
            Map.of("a", 1),
            Map.of("b", 2),
            Map.of("c", 3),
            List.of(Map.of("flag", "X")),
            false);
    assertThat(populated.requestPayload()).containsEntry("a", 1);
    assertThat(populated.responsePayload()).containsEntry("b", 2);
    assertThat(populated.details()).containsEntry("c", 3);
    assertThat(populated.adminFlags()).hasSize(1);

    KycGovernmentApiPort.KycCheckResult emptyMaps =
        new KycGovernmentApiPort.KycCheckResult(
            "PASS", "PROVIDER", Map.of(), Map.of(), Map.of(), List.of(), false);
    assertThat(emptyMaps.requestPayload()).isEmpty();
    assertThat(emptyMaps.responsePayload()).isEmpty();
    assertThat(emptyMaps.details()).isEmpty();
  }
}
