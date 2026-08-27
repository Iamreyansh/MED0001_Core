package com.nammamedmate.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class HealthIT extends AbstractApiIT {

  @Autowired private TestRestTemplate rest;

  @Test
  void healthReturnsUpEnvelope() {
    ResponseEntity<Map> response = rest.getForEntity(baseUrl() + "/api/v1/health", Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<?, ?> body = Objects.requireNonNull(response.getBody());
    assertThat(body.get("success")).isEqualTo(true);
    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) body.get("data");
    assertThat(data).containsEntry("status", "UP");
  }

  @Test
  void corsAllowsNammaMedmateOrigin() {
    HttpHeaders headers = new HttpHeaders();
    headers.setOrigin("https://app.nammamedmate.com");
    ResponseEntity<Map> response =
        rest.exchange(
            baseUrl() + "/api/v1/health", HttpMethod.GET, new HttpEntity<>(headers), Map.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getAccessControlAllowOrigin())
        .isEqualTo("https://app.nammamedmate.com");
  }

  @Test
  void corsPreflightAllowsNammaMedmateOrigin() {
    HttpHeaders headers = new HttpHeaders();
    headers.setOrigin("https://admin.nammamedmate.com");
    headers.set("Access-Control-Request-Method", "POST");
    headers.set("Access-Control-Request-Headers", "Authorization,Content-Type");
    ResponseEntity<Void> response =
        rest.exchange(
            baseUrl() + "/api/v1/auth/customer/send-otp",
            HttpMethod.OPTIONS,
            new HttpEntity<>(headers),
            Void.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getAccessControlAllowOrigin())
        .isEqualTo("https://admin.nammamedmate.com");
  }

  @Test
  void corsRejectsForeignOrigin() {
    HttpHeaders headers = new HttpHeaders();
    headers.setOrigin("https://evil.example");
    ResponseEntity<Void> response =
        rest.exchange(
            baseUrl() + "/api/v1/health", HttpMethod.GET, new HttpEntity<>(headers), Void.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNull();
  }
}
