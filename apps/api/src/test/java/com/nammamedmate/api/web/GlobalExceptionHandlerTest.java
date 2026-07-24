package com.nammamedmate.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.kernel.error.AppException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  void mapsExceptions() {
    assertThat(handler.handleApp(new AppException("X", "m", 422)).getStatusCode().value())
        .isEqualTo(422);
    assertThat(handler.handleIllegalArgument(new IllegalArgumentException("bad")).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(handler.handleGeneric(new RuntimeException("boom")).getStatusCode())
        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
  }
}
