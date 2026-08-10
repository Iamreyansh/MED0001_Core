package com.nammamedmate.api.web;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.error.AppException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(AppException.class)
  public ResponseEntity<ApiResponse<Void>> handleApp(AppException ex) {
    ResponseEntity.BodyBuilder builder = ResponseEntity.status(ex.httpStatus());
    if (ex.retryAfterSeconds() != null) {
      builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()));
    }
    return builder.body(
        ApiResponse.fail(ex.code(), ex.getMessage(), ex.retryAfterSeconds(), ex.details()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
    String message =
        ex.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(err -> err.getField() + " " + err.getDefaultMessage())
            .orElse("Validation failed");
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(ApiResponse.fail("VALIDATION_ERROR", message));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException ex) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(ApiResponse.fail("VALIDATION_ERROR", "Malformed request body"));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(ApiResponse.fail("BAD_REQUEST", ex.getMessage()));
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(
      MethodArgumentTypeMismatchException ex) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(ApiResponse.fail("VALIDATION_ERROR", ex.getName() + " is invalid"));
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<ApiResponse<Void>> handleMaxUpload(MaxUploadSizeExceededException ex) {
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .body(ApiResponse.fail("FILE_TOO_LARGE", "File exceeds 10 MB limit"));
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(
      HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
    String uri = request.getRequestURI();
    if (uri != null && uri.contains("/api/v1/admin/roles")) {
      return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
          .body(ApiResponse.fail("METHOD_NOT_ALLOWED", "Admin roles are not customisable"));
    }
    if (uri != null && uri.contains("/compliance/drug-register")) {
      return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
          .body(
              ApiResponse.fail(
                  "METHOD_NOT_ALLOWED", "Drug register entries cannot be modified or deleted"));
    }
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
        .body(ApiResponse.fail("METHOD_NOT_ALLOWED", "Method not allowed"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ApiResponse.fail("INTERNAL_ERROR", "Unexpected error"));
  }
}
