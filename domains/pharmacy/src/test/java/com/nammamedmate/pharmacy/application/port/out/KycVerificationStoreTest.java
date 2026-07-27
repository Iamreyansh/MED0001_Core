package com.nammamedmate.pharmacy.application.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.pharmacy.application.port.out.KycVerificationStore.KycVerificationRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KycVerificationStoreTest {

  @Test
  void recordDefensiveCopyUsesEmptyMapForBlankCollections() {
    Instant now = Instant.parse("2026-07-27T10:00:00Z");
    KycVerificationRecord record =
        new KycVerificationRecord(
            Ids.newId(),
            Ids.newId(),
            Ids.newId(),
            "GSTIN",
            "GSTN_SANDBOX_API",
            Map.of(),
            Map.of(),
            "PASS",
            Map.of(),
            List.of(),
            0,
            null,
            now,
            now);

    assertThat(record.requestPayload()).isEmpty();
    assertThat(record.responsePayload()).isEmpty();
    assertThat(record.details()).isEmpty();
    assertThat(record.adminFlags()).isEmpty();
  }
}
