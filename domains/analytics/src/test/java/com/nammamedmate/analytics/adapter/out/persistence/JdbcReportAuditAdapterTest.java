package com.nammamedmate.analytics.adapter.out.persistence;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class JdbcReportAuditAdapterTest {

  @Mock JdbcTemplate jdbc;

  @Test
  void insertsAuditRow() {
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    JdbcReportAuditAdapter adapter = new JdbcReportAuditAdapter(jdbc, new ObjectMapper());
    adapter.recordGeneration(
        UUID.randomUUID(),
        "admin@x.com",
        "admin_finance",
        "GMV_COMMISSION_PAYOUTS",
        UUID.randomUUID(),
        "2026-07-01",
        "2026-07-31",
        10,
        "https://example/x",
        Instant.parse("2026-07-24T01:30:00Z"));
    verify(jdbc).update(anyString(), any(Object[].class));
  }

  @Test
  void schedulerActorDefaults() {
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    new JdbcReportAuditAdapter(jdbc, new ObjectMapper())
        .recordGeneration(
            null,
            null,
            null,
            "COHORT_RETENTION",
            UUID.randomUUID(),
            "2026-07-01",
            "2026-07-07",
            1,
            null,
            Instant.now());
    verify(jdbc).update(anyString(), any(Object[].class));
  }

  @Test
  void brokenObjectMapperFallsBack() {
    ObjectMapper broken =
        new ObjectMapper() {
          @Override
          public String writeValueAsString(Object value)
              throws com.fasterxml.jackson.core.JsonProcessingException {
            throw new com.fasterxml.jackson.core.JsonProcessingException("x") {};
          }
        };
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    new JdbcReportAuditAdapter(jdbc, broken)
        .recordGeneration(
            UUID.randomUUID(),
            "  ",
            "admin",
            "X",
            UUID.randomUUID(),
            "a",
            "b",
            0,
            "u",
            Instant.now());
    verify(jdbc).update(anyString(), any(Object[].class));
  }
}
