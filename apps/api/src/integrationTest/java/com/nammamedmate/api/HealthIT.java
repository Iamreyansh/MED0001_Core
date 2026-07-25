package com.nammamedmate.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
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
}
