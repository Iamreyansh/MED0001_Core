package com.nammamedmate.settings.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuditRedactionTest {

  @Test
  void redactsSensitiveFieldsRecursively() {
    assertThat(AuditRedaction.redact(null)).isNull();
    assertThat(AuditRedaction.redactMap(null)).isNull();
    Map<String, Object> in =
        Map.of(
            "password",
            "secret",
            "nested",
            Map.of("otp_hash", "abc"),
            "list",
            List.of(Map.of("upi_id", "x@okaxis")),
            "ok",
            1);
    @SuppressWarnings("unchecked")
    Map<String, Object> out = (Map<String, Object>) AuditRedaction.redact(in);
    assertThat(out.get("password")).isEqualTo("[REDACTED]");
    assertThat(out.get("ok")).isEqualTo(1);
    @SuppressWarnings("unchecked")
    Map<String, Object> nested = (Map<String, Object>) out.get("nested");
    assertThat(nested.get("otp_hash")).isEqualTo("[REDACTED]");
  }
}
