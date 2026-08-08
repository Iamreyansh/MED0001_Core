package com.nammamedmate.settings.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.id.Ids;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcAuditExportJobStoreTest {

  @Test
  void insertCompleteFind() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcAuditExportJobStore store = new JdbcAuditExportJobStore(jdbc, new ObjectMapper());
    UUID id = Ids.newId();
    Instant now = Instant.parse("2026-07-24T01:00:00Z");
    store.insertQueued(id, Map.of("export", true), now);
    store.insertQueued(id, null, now);
    verify(jdbc, org.mockito.Mockito.atLeastOnce()).update(anyString(), eq(id), anyString(), any());

    store.markCompleted(id, "https://s3/x", now);
    verify(jdbc).update(anyString(), eq("https://s3/x"), any(), eq(id));

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getString("status")).thenReturn("COMPLETED");
              when(rs.getString("filters")).thenReturn("{\"a\":1}");
              when(rs.getString("download_url")).thenReturn("https://s3/x");
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findById(id)).isPresent();

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getString("status")).thenReturn("QUEUED");
              when(rs.getString("filters")).thenReturn("not-json");
              when(rs.getString("download_url")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findById(id).orElseThrow().filters()).isEmpty();

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getString("status")).thenReturn("QUEUED");
              when(rs.getString("filters")).thenReturn("");
              when(rs.getString("download_url")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findById(id)).isPresent();

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getString("status")).thenReturn("QUEUED");
              when(rs.getString("filters")).thenReturn(null);
              when(rs.getString("download_url")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findById(id).orElseThrow().filters()).isEmpty();

    var row =
        new com.nammamedmate.settings.application.port.out.AuditExportJobStore.ExportJobRow(
            id, "QUEUED", null, null, now);
    assertThat(row.filters()).isEmpty();

    ObjectMapper broken = mock(ObjectMapper.class);
    when(broken.writeValueAsString(any())).thenThrow(new RuntimeException("x"));
    assertThatThrownBy(
            () -> new JdbcAuditExportJobStore(jdbc, broken).insertQueued(id, Map.of("a", 1), now))
        .isInstanceOf(IllegalStateException.class);
  }
}
