package com.nammamedmate.catalogue.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.catalogue.application.port.out.AuditLogStore.AuditLogRecord;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class JdbcCatalogueAuditLogStoreTest {

  @Mock private JdbcTemplate jdbc;

  @Test
  void append_writesRow() {
    when(jdbc.query(
            org.mockito.ArgumentMatchers.contains("pharmacy_staff"), any(RowMapper.class), any()))
        .thenReturn(List.of());
    when(jdbc.query(
            org.mockito.ArgumentMatchers.contains("admin_staff"), any(RowMapper.class), any()))
        .thenReturn(List.of());
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
    store.append(
        new AuditLogRecord(
            UUID.randomUUID(),
            null,
            UUID.randomUUID(),
            "X",
            UUID.randomUUID(),
            "R",
            Map.of("before", Map.of("a", 1), "after", Map.of("a", 2)),
            "1.1.1.1",
            Instant.parse("2026-08-08T00:00:00Z")));
    store.append(
        new AuditLogRecord(
            UUID.randomUUID(),
            "MEDICINE",
            UUID.randomUUID(),
            "X",
            UUID.randomUUID(),
            "R",
            Map.of("before", Map.of("a", 1)),
            "1.1.1.1",
            Instant.parse("2026-08-08T00:00:00Z")));
    store.append(
        new AuditLogRecord(
            UUID.randomUUID(),
            "MEDICINE",
            UUID.randomUUID(),
            "X",
            UUID.randomUUID(),
            "R",
            Map.of("after", Map.of("a", 2)),
            "1.1.1.1",
            Instant.parse("2026-08-08T00:00:00Z")));
    store.append(
        new AuditLogRecord(
            UUID.randomUUID(),
            "MEDICINE",
            UUID.randomUUID(),
            "X",
            null,
            null,
            Map.of(),
            "1.1.1.1",
            Instant.parse("2026-08-08T00:00:00Z")));

    stubNameQuery("pharmacy_staff", "Pharmacist");
    store.append(namedRecord("PHARMACY_OWNER"));

    stubNameQuery("pharmacy_staff", "  ");
    stubNameQuery("admin_staff", "Ops");
    store.append(namedRecord("ADMIN_OPS"));

    stubNameQuery("pharmacy_staff", null);
    stubNameQuery("admin_staff", null);
    store.append(namedRecord("ADMIN_FINANCE"));

    when(jdbc.query(
            org.mockito.ArgumentMatchers.contains("pharmacy_staff"), any(RowMapper.class), any()))
        .thenReturn(null);
    store.append(namedRecord(null));

    when(jdbc.query(
            org.mockito.ArgumentMatchers.contains("pharmacy_staff"), any(RowMapper.class), any()))
        .thenReturn(List.of());
    when(jdbc.query(
            org.mockito.ArgumentMatchers.contains("admin_staff"), any(RowMapper.class), any()))
        .thenReturn(null);
    store.append(namedRecord("ADMIN_SUPER"));

    stubNameQuery("pharmacy_staff", "  ");
    stubNameQuery("admin_staff", "  ");
    store.append(namedRecord("ADMIN_OPS"));
  }

  private static AuditLogRecord namedRecord(String role) {
    return new AuditLogRecord(
        UUID.randomUUID(),
        "MEDICINE",
        UUID.randomUUID(),
        "X",
        UUID.randomUUID(),
        role,
        Map.of(),
        "1.1.1.1",
        Instant.parse("2026-08-08T00:00:00Z"));
  }

  @SuppressWarnings("unchecked")
  private void stubNameQuery(String table, String label) {
    when(jdbc.query(org.mockito.ArgumentMatchers.contains(table), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<String> mapper = inv.getArgument(1);
              java.sql.ResultSet rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
              org.mockito.Mockito.when(rs.getString("label")).thenReturn(label);
              return java.util.Collections.singletonList(mapper.mapRow(rs, 0));
            });
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
