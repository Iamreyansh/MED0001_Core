package com.nammamedmate.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.nammamedmate.kernel.error.AppException;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
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
    assertThat(limited.getBody().error().details()).isNull();
    assertThat(handler.handleIllegalArgument(new IllegalArgumentException("bad")).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(handler.handleGeneric(new RuntimeException("boom")).getStatusCode())
        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

    var typeMismatch =
        handler.handleTypeMismatch(
            new org.springframework.web.method.annotation.MethodArgumentTypeMismatchException(
                "not-a-uuid",
                java.util.UUID.class,
                "sessionId",
                mock(MethodParameter.class),
                new IllegalArgumentException("bad uuid")));
    assertThat(typeMismatch.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(typeMismatch.getBody().error().code()).isEqualTo("VALIDATION_ERROR");
    assertThat(typeMismatch.getBody().error().message()).contains("sessionId");

    var withDetails =
        handler.handleApp(
            new AppException(
                "ACCOUNT_LOCKED",
                "locked",
                403,
                null,
                java.util.Map.of("unlock_at", "2026-07-25T09:00:00Z")));
    assertThat(withDetails.getBody().error().details()).containsKey("unlock_at");
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

  @Test
  void mapsMethodNotAllowedForAdminRoles() {
    MockHttpServletRequest rolesReq =
        new MockHttpServletRequest("POST", "/api/v1/admin/roles/x/permissions");
    var roles =
        handler.handleMethodNotSupported(
            new HttpRequestMethodNotSupportedException("POST"), rolesReq);
    assertThat(roles.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    assertThat(roles.getBody().error().code()).isEqualTo("METHOD_NOT_ALLOWED");
    assertThat(roles.getBody().error().message()).contains("not customisable");

    MockHttpServletRequest otherReq =
        new MockHttpServletRequest("DELETE", "/api/v1/orders/1/note/2");
    var other =
        handler.handleMethodNotSupported(
            new HttpRequestMethodNotSupportedException("DELETE"), otherReq);
    assertThat(other.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    assertThat(other.getBody().error().message()).isEqualTo("Method not allowed");

    jakarta.servlet.http.HttpServletRequest nullUri =
        mock(jakarta.servlet.http.HttpServletRequest.class);
    org.mockito.Mockito.when(nullUri.getRequestURI()).thenReturn(null);
    var nullPath =
        handler.handleMethodNotSupported(
            new HttpRequestMethodNotSupportedException("POST"), nullUri);
    assertThat(nullPath.getBody().error().message()).isEqualTo("Method not allowed");
  }
}
