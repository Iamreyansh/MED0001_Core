package com.nammamedmate.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.auth.domain.MagicOtp;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class CustomerOtpAuthIT extends AbstractApiIT {

  private static final String MAGIC_PHONE = "+919999900000";

  @Autowired private TestRestTemplate rest;

  @Test
  void sendAndVerifyMagicOtpHappyPath() {
    ResponseEntity<Map> send =
        rest.postForEntity(
            baseUrl() + "/api/v1/auth/customer/send-otp",
            jsonBody(
                Map.of(
                    "phone",
                    MAGIC_PHONE,
                    "device_info",
                    Map.of("platform", "android", "app_version", "1.0.0"))),
            Map.class);

    assertThat(send.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> sendBody = Objects.requireNonNull(send.getBody());
    assertThat(sendBody.get("success")).isEqualTo(true);
    @SuppressWarnings("unchecked")
    Map<String, Object> sendData = (Map<String, Object>) sendBody.get("data");
    assertThat(sendData.get("session_id")).isNotNull();
    assertThat(sendData.get("phone")).isEqualTo(MAGIC_PHONE);

    String sessionId = String.valueOf(sendData.get("session_id"));

    ResponseEntity<Map> verify =
        rest.postForEntity(
            baseUrl() + "/api/v1/auth/customer/verify-otp",
            jsonBody(
                Map.of(
                    "session_id",
                    sessionId,
                    "phone",
                    MAGIC_PHONE,
                    "otp",
                    MagicOtp.CODE,
                    "device_token",
                    "it-device-token")),
            Map.class);

    assertThat(verify.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> verifyBody = Objects.requireNonNull(verify.getBody());
    assertThat(verifyBody.get("success")).isEqualTo(true);
    @SuppressWarnings("unchecked")
    Map<String, Object> verifyData = (Map<String, Object>) verifyBody.get("data");
    assertThat(verifyData.get("access_token")).isInstanceOf(String.class);
    assertThat(verifyData.get("refresh_token")).isInstanceOf(String.class);
    assertThat(verifyData.get("token_type")).isEqualTo("Bearer");
    assertThat(verifyData.get("is_new_user")).isEqualTo(true);
  }

  @Test
  void sendOtpRejectsInvalidPhone() {
    ResponseEntity<Map> send =
        rest.postForEntity(
            baseUrl() + "/api/v1/auth/customer/send-otp",
            jsonBody(Map.of("phone", "+441234567890")),
            Map.class);

    assertThat(send.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    Map<?, ?> body = Objects.requireNonNull(send.getBody());
    assertThat(body.get("success")).isEqualTo(false);
    @SuppressWarnings("unchecked")
    Map<String, Object> error = (Map<String, Object>) body.get("error");
    assertThat(error.get("code")).isIn("VALIDATION_ERROR", "BAD_REQUEST");
  }

  @Test
  void verifyOtpRejectsWrongCode() {
    ResponseEntity<Map> send =
        rest.postForEntity(
            baseUrl() + "/api/v1/auth/customer/send-otp",
            jsonBody(Map.of("phone", "+919999900001")),
            Map.class);
    assertThat(send.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> sendBody = Objects.requireNonNull(send.getBody());
    @SuppressWarnings("unchecked")
    Map<String, Object> sendData = (Map<String, Object>) sendBody.get("data");
    String sessionId = String.valueOf(sendData.get("session_id"));

    ResponseEntity<Map> verify =
        rest.postForEntity(
            baseUrl() + "/api/v1/auth/customer/verify-otp",
            jsonBody(
                Map.of(
                    "session_id",
                    sessionId,
                    "phone",
                    "+919999900001",
                    "otp",
                    "000000",
                    "device_token",
                    "it-device")),
            Map.class);

    assertThat(verify.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    Map<?, ?> verifyBody = Objects.requireNonNull(verify.getBody());
    assertThat(verifyBody.get("success")).isEqualTo(false);
    @SuppressWarnings("unchecked")
    Map<String, Object> error = (Map<String, Object>) verifyBody.get("error");
    assertThat(error.get("code")).isEqualTo("OTP_INVALID");
  }

  @Test
  void verifyOtpRejectsUnknownSession() {
    ResponseEntity<Map> verify =
        rest.postForEntity(
            baseUrl() + "/api/v1/auth/customer/verify-otp",
            jsonBody(
                Map.of(
                    "session_id",
                    UUID.randomUUID().toString(),
                    "phone",
                    MAGIC_PHONE,
                    "otp",
                    MagicOtp.CODE,
                    "device_token",
                    "it-device")),
            Map.class);

    assertThat(verify.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    Map<?, ?> body = Objects.requireNonNull(verify.getBody());
    @SuppressWarnings("unchecked")
    Map<String, Object> error = (Map<String, Object>) body.get("error");
    assertThat(error.get("code")).isEqualTo("OTP_SESSION_NOT_FOUND");
  }

  private static HttpEntity<Map<String, Object>> jsonBody(Map<String, Object> body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return new HttpEntity<>(body, headers);
  }
}
