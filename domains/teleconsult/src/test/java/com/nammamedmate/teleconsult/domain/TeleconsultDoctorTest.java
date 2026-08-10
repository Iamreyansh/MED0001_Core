package com.nammamedmate.teleconsult.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.kernel.id.Ids;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TeleconsultDoctorTest {

  @Test
  void profileCompleteAndNullLanguages() {
    Instant now = Instant.parse("2026-07-24T10:00:00Z");
    TeleconsultDoctor incomplete =
        new TeleconsultDoctor(
            Ids.newId(),
            "Dr",
            "MBBS",
            "KA1",
            "GP",
            null,
            1,
            " ",
            null,
            "c",
            false,
            null,
            0,
            0,
            null,
            now,
            now,
            null);
    assertThat(incomplete.languagesSpoken()).isEmpty();
    assertThat(incomplete.profileComplete()).isFalse();

    TeleconsultDoctor ok =
        new TeleconsultDoctor(
            Ids.newId(),
            "Dr",
            "MBBS",
            "KA2",
            "GP",
            List.of("English"),
            1,
            "https://cdn",
            "bio",
            "c",
            true,
            null,
            0,
            0,
            null,
            now,
            now,
            null);
    assertThat(ok.profileComplete()).isTrue();
  }
}
