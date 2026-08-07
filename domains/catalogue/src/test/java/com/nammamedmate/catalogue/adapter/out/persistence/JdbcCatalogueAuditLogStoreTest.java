package com.nammamedmate.catalogue.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.catalogue.application.port.out.AuditLogStore.AuditLogRecord;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class JdbcCatalogueAuditLogStoreTest {

  @Mock private JdbcTemplate jdbc;

  @Test
  void append_writesRow() {
    JdbcAuditLogStore store = new JdbcAuditLogStore(jdbc, new ObjectMapper());
    store.append(
        new AuditLogRecord(
            UUID.randomUUID(),
            "MEDICINE",
            UUID.randomUUID(),
            "MEDICINE_CREATED",
            UUID.randomUUID(),
            "ADMIN_SUPER",
            Map.of("k", "v"),
            " ",
            Instant.parse("2026-08-08T00:00:00Z")));
    store.append(
        new AuditLogRecord(
            UUID.randomUUID(),
            "MEDICINE",
            UUID.randomUUID(),
            "MEDICINE_CREATED",
            UUID.randomUUID(),
            "ADMIN_SUPER",
            Map.of(),
            "10.0.0.1",
            Instant.parse("2026-08-08T00:00:00Z")));
    store.append(
        new AuditLogRecord(
            UUID.randomUUID(),
            "MEDICINE",
            UUID.randomUUID(),
            "MEDICINE_CREATED",
            UUID.randomUUID(),
            "ADMIN_SUPER",
            Map.of(),
            null,
            Instant.parse("2026-08-08T00:00:00Z")));
    verify(jdbc, org.mockito.Mockito.times(3))
        .update(anyString(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void append_payloadFailure() throws Exception {
    ObjectMapper mapper = org.mockito.Mockito.mock(ObjectMapper.class);
    when(mapper.writeValueAsString(any())).thenThrow(new RuntimeException("boom"));
    JdbcAuditLogStore store = new JdbcAuditLogStore(jdbc, mapper);
    assertThatThrownBy(
            () ->
                store.append(
                    new AuditLogRecord(
                        UUID.randomUUID(),
                        "MEDICINE",
                        UUID.randomUUID(),
                        "X",
                        UUID.randomUUID(),
                        "R",
                        Map.of(),
                        null,
                        Instant.now())))
        .isInstanceOf(IllegalStateException.class);
  }
}
