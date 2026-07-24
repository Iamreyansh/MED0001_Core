package com.nammamedmate.kernel.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AppExceptionTest {

  @Test
  void exposesCodeAndStatus() {
    AppException ex = new AppException("NOT_FOUND", "missing", 404);
    assertThat(ex.code()).isEqualTo("NOT_FOUND");
    assertThat(ex.httpStatus()).isEqualTo(404);
    assertThat(ex.getMessage()).isEqualTo("missing");
  }
}
