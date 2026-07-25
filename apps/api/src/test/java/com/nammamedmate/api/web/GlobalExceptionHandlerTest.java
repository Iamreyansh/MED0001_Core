package com.nammamedmate.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.nammamedmate.kernel.error.AppException;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  void mapsExceptions() {
    assertThat(handler.handleApp(new AppException("X", "m", 422)).getStatusCode().value())
        .isEqualTo(422);
    var limited = handler.handleApp(new AppException("OTP_RATE_LIMITED", "wait", 429, 30));
    assertThat(limited.getBody()).isNotNull();
    assertThat(limited.getBody().error()).isNotNull();
    assertThat(limited.getBody().error().retryAfterSeconds()).isEqualTo(30);
    assertThat(handler.handleIllegalArgument(new IllegalArgumentException("bad")).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(handler.handleGeneric(new RuntimeException("boom")).getStatusCode())
        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
  }

  @Test
  void mapsValidationErrors() throws Exception {
    BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "req");
    binding.addError(new FieldError("req", "phone", "must match"));
    MethodParameter parameter = mock(MethodParameter.class);
    MethodArgumentNotValidException ex = new MethodArgumentNotValidException(parameter, binding);

    var response = handler.handleValidation(ex);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody().error().code()).isEqualTo("VALIDATION_ERROR");
    assertThat(response.getBody().error().message()).contains("phone");
  }

  @Test
  void mapsUnreadableBodyToValidationError() {
    var response =
        handler.handleUnreadable(
            new HttpMessageNotReadableException("bad json", mock(HttpInputMessage.class)));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().error()).isNotNull();
    assertThat(response.getBody().error().code()).isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void mapsValidationWithoutFieldErrors() throws Exception {
    BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "req");
    MethodParameter parameter = mock(MethodParameter.class);
    MethodArgumentNotValidException ex = new MethodArgumentNotValidException(parameter, binding);

    var response = handler.handleValidation(ex);
    assertThat(response.getBody().error().message()).isEqualTo("Validation failed");
  }
}
