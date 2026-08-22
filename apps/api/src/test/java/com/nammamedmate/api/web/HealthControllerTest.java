package com.nammamedmate.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.ApiResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

class HealthControllerTest {

  @Test
  void returnsUpWhenDatabaseAnswers() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
    ResponseEntity<ApiResponse<Map<String, String>>> response = new HealthController(jdbc).health();
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().success()).isTrue();
    assertThat(response.getBody().data()).containsEntry("status", "UP");
  }

  @Test
  void returnsDownWhenDatabaseFails() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), eq(Integer.class)))
        .thenThrow(new RuntimeException("db down"));
    ResponseEntity<ApiResponse<Map<String, String>>> response = new HealthController(jdbc).health();
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody().data()).containsEntry("status", "DOWN");
  }
}
