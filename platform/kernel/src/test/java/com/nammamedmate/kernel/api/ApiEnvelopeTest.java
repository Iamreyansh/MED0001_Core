package com.nammamedmate.kernel.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiEnvelopeTest {

  @Test
  void successAndFailure() {
    ApiResponse<String> ok = ApiResponse.ok("x");
    assertThat(ok.success()).isTrue();
    assertThat(ok.data()).isEqualTo("x");
    assertThat(ok.error()).isNull();

    PaginationMeta meta = PaginationMeta.of(1, 20, 25);
    assertThat(meta.hasNext()).isTrue();
    assertThat(ApiResponse.ok("y", meta).meta()).isEqualTo(meta);

    ApiResponse<Void> fail = ApiResponse.fail("E", "m");
    assertThat(fail.success()).isFalse();
    assertThat(fail.error().code()).isEqualTo("E");
    assertThat(fail.error().retryAfterSeconds()).isNull();
    assertThat(fail.error().details()).isNull();

    ApiResponse<Void> limited = ApiResponse.fail("OTP_RATE_LIMITED", "wait", 42);
    assertThat(limited.error().retryAfterSeconds()).isEqualTo(42);
    assertThat(new ApiError("X", "y").retryAfterSeconds()).isNull();

    java.util.Map<String, Object> details = java.util.Map.of("unlock_at", "2026-07-25T09:00:00Z");
    ApiResponse<Void> withDetails = ApiResponse.fail("ACCOUNT_LOCKED", "locked", null, details);
    assertThat(withDetails.error().details()).containsKey("unlock_at");
    assertThat(new ApiError("X", "y", 5).details()).isNull();
  }

  @Test
  void pageRequestNormalize() {
    PageRequest pr = PageRequest.normalize(null, null, "created_at", "DESC");
    assertThat(pr.page()).isEqualTo(1);
    assertThat(pr.limit()).isEqualTo(20);
    assertThat(pr.order()).isEqualTo("desc");
    assertThat(pr.offset()).isZero();

    PageRequest capped = PageRequest.normalize(2, 500, null, "asc");
    assertThat(capped.limit()).isEqualTo(100);
    assertThat(capped.offset()).isEqualTo(100);

    PageRequest low = PageRequest.normalize(0, 0, null, null);
    assertThat(low.page()).isEqualTo(1);
    assertThat(low.limit()).isEqualTo(1);
  }

  @Test
  void paginationHasNextFalse() {
    assertThat(PaginationMeta.of(2, 20, 20).hasNext()).isFalse();
  }
}
