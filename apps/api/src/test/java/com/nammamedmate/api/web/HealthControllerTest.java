package com.nammamedmate.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.ApiResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

class HealthControllerTest {

  @Test
  void returnsUpWhenDatabaseAnswers() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1, 0);
    ResponseEntity<ApiResponse<Map<String, String>>> response = new HealthController(jdbc).health();
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().success()).isTrue();
    assertThat(response.getBody().data()).containsEntry("status", "UP");
    assertThat(response.getBody().data()).containsEntry("outbox", "UP");
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

  @Test
  void reportsDegradedOutboxAndRedis() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1, 2);
    ResponseEntity<ApiResponse<Map<String, String>>> stale = new HealthController(jdbc).health();
    assertThat(stale.getBody().data()).containsEntry("outbox", "DEGRADED");
    assertThat(stale.getBody().data()).containsEntry("status", "DEGRADED");

    when(jdbc.queryForObject(anyString(), eq(Integer.class)))
        .thenReturn(1)
        .thenThrow(new RuntimeException("outbox down"));
    ResponseEntity<ApiResponse<Map<String, String>>> outboxFail =
        new HealthController(jdbc).health();
    assertThat(outboxFail.getBody().data()).containsEntry("outbox", "DEGRADED");

    when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1, null);
    ObjectProvider<RedisConnectionFactory> redis = mock(ObjectProvider.class);
    when(redis.getIfAvailable()).thenReturn(null);
    assertThat(new HealthController(jdbc, redis).health().getBody().data())
        .containsEntry("status", "UP");

    RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
    RedisConnection connection = mock(RedisConnection.class);
    when(redis.getIfAvailable()).thenReturn(factory);
    when(factory.getConnection()).thenReturn(connection);
    when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1, 0);
    assertThat(new HealthController(jdbc, redis).health().getBody().data())
        .containsEntry("redis", "UP");

    when(factory.getConnection()).thenThrow(new RuntimeException("redis down"));
    assertThat(new HealthController(jdbc, redis).health().getBody().data())
        .containsEntry("redis", "DEGRADED");
  }
}
