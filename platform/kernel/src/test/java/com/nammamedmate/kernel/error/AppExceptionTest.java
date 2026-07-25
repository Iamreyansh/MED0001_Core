package com.nammamedmate.kernel.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AppExceptionTest {

  @Test
  void exposesCodeAndStatus() {
    AppException ex = new AppException("NOT_FOUND", "missing", 404);
    assertThat(ex.code()).isEqualTo("NOT_FOUND");
    assertThat(ex.httpStatus()).isEqualTo(404);
    assertThat(ex.getMessage()).isEqualTo("missing");
    assertThat(ex.retryAfterSeconds()).isNull();
    assertThat(ex.details()).isNull();

    AppException limited = new AppException("OTP_RATE_LIMITED", "wait", 429, 12);
    assertThat(limited.retryAfterSeconds()).isEqualTo(12);
    assertThat(limited.details()).isNull();

    Map<String, Object> details = Map.of("unlock_at", "2026-07-25T09:00:00Z");
    AppException withDetails = new AppException("ACCOUNT_LOCKED", "locked", 403, null, details);
    assertThat(withDetails.details()).containsKey("unlock_at");
  }
}
