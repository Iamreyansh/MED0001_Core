package com.nammamedmate.automation.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcDedupAdapterTest {

  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final Clock clock = Clock.fixed(Instant.parse("2026-08-22T12:00:00Z"), ZoneOffset.UTC);
  private final JdbcDedupAdapter adapter = new JdbcDedupAdapter(jdbc, clock);

  @Test
  void duplicateWindowAndRecord() {
    UUID rule = UUID.randomUUID();
    UUID entity = UUID.randomUUID();
    assertThat(adapter.isDuplicate(null, entity, Duration.ofSeconds(60))).isFalse();
    assertThat(adapter.isDuplicate(rule, null, Duration.ofSeconds(60))).isFalse();
    assertThat(adapter.isDuplicate(rule, entity, Duration.ZERO)).isFalse();
    assertThat(adapter.isDuplicate(rule, entity, null)).isFalse();
    assertThat(adapter.isDuplicate(rule, entity, Duration.ofSeconds(-1))).isFalse();
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any(), any())).thenReturn(0L);
    assertThat(adapter.isDuplicate(rule, entity, Duration.ofSeconds(300))).isFalse();
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any(), any())).thenReturn(1L);
    assertThat(adapter.isDuplicate(rule, entity, Duration.ofSeconds(300))).isTrue();
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any(), any())).thenReturn(null);
    assertThat(adapter.isDuplicate(rule, entity, Duration.ofSeconds(300))).isFalse();
    adapter.recordFire(null, entity);
    adapter.recordFire(rule, null);
    adapter.recordFire(rule, entity);
    verify(jdbc).update(anyString(), eq(rule), eq(entity), any());
  }
}
