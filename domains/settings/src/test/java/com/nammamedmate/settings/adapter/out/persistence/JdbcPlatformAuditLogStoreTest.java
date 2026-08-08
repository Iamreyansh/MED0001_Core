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
import com.nammamedmate.settings.application.port.out.PlatformAuditLogStore.ListFilter;
import com.nammamedmate.settings.application.port.out.PlatformAuditLogStore.PageResult;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcPlatformAuditLogStoreTest {

  private JdbcTemplate jdbc;
  private JdbcPlatformAuditLogStore store;
  private final Instant now = Instant.parse("2026-07-24T01:00:00Z");

  @BeforeEach
  void setUp() {
    jdbc = mock(JdbcTemplate.class);
    store = new JdbcPlatformAuditLogStore(jdbc, new ObjectMapper());
  }

  @Test
  void appendListFindArchive() throws Exception {
    UUID id = Ids.newId();
    store.append(
        id,
        Ids.newId(),
        "Ayesha",
        "admin_super",
        "ADMIN",
        "pharmacy.suspend",
        "pharmacy",
        Ids.newId(),
        Map.of("status", "ACTIVE", "password", "x"),
        Map.of("status", "SUSPENDED"),
        Map.of("method", "PATCH"),
        " ",
        "ua",
        now);
    store.append(
        Ids.newId(),
        null,
        "  ",
        "role",
        "  ",
        "x",
        null,
        null,
        null,
        null,
        null,
        "10.0.0.1",
        null,
        now);
    store.append(
        Ids.newId(),
        null,
        null,
        null,
        null,
        "y",
        "  ",
        null,
        null,
        Map.of("z", 1),
        Map.of(),
        null,
        null,
        now);
    verify(jdbc, org.mockito.Mockito.atLeastOnce())
        .update(
            anyString(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any());

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    assertThat(
            store
                .list(new ListFilter(null, null, null, null, null, null, null, null, null, 10, 0))
                .total())
        .isZero();

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getObject("actor_id")).thenReturn(Ids.newId());
              when(rs.getString("actor_name")).thenReturn("A");
              when(rs.getString("actor_role")).thenReturn("admin_super");
              when(rs.getString("actor_type")).thenReturn("ADMIN");
              when(rs.getString("action")).thenReturn("pharmacy.suspend");
              when(rs.getString("resource_type")).thenReturn("pharmacy");
              when(rs.getObject("resource_id")).thenReturn(Ids.newId());
              when(rs.getString("before_state")).thenReturn("{\"status\":\"ACTIVE\"}");
              when(rs.getString("after_state")).thenReturn("{\"status\":\"SUSPENDED\"}");
              when(rs.getString("metadata")).thenReturn("{}");
              when(rs.getString("ip_address")).thenReturn("1.1.1.1");
              when(rs.getString("user_agent")).thenReturn("ua");
              when(rs.getTimestamp("timestamp")).thenReturn(Timestamp.from(now));
              return List.of(mapper.mapRow(rs, 0));
            });

    var page =
        store.list(
            new ListFilter(
                Ids.newId(),
                "ADMIN",
                "pharmacy",
                Ids.newId(),
                "pharmacy.suspend",
                now.minusSeconds(10),
                now,
                "action",
                "asc",
                20,
                0));
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.rows()).hasSize(1);

    store.list(
        new ListFilter(null, null, null, null, null, null, null, "resource_type", "desc", 10, 0));
    store.list(new ListFilter(null, null, null, null, null, null, null, null, null, 10, 0));

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getObject("actor_id")).thenReturn(null);
              when(rs.getString("actor_name")).thenReturn("system");
              when(rs.getString("actor_role")).thenReturn("SYSTEM");
              when(rs.getString("actor_type")).thenReturn("SYSTEM");
              when(rs.getString("action")).thenReturn("x");
              when(rs.getString("resource_type")).thenReturn("y");
              when(rs.getObject("resource_id")).thenReturn(null);
              when(rs.getString("before_state")).thenReturn("not-json");
              when(rs.getString("after_state")).thenReturn(null);
              when(rs.getString("metadata")).thenReturn("");
              when(rs.getString("ip_address")).thenReturn("0.0.0.0");
              when(rs.getString("user_agent")).thenReturn(null);
              when(rs.getTimestamp("timestamp")).thenReturn(Timestamp.from(now));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findById(id)).isPresent();

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getObject("actor_id")).thenReturn(null);
              when(rs.getString("actor_name")).thenReturn("system");
              when(rs.getString("actor_role")).thenReturn("SYSTEM");
              when(rs.getString("actor_type")).thenReturn("SYSTEM");
              when(rs.getString("action")).thenReturn("x");
              when(rs.getString("resource_type")).thenReturn("y");
              when(rs.getObject("resource_id")).thenReturn(null);
              when(rs.getString("before_state")).thenReturn(null);
              when(rs.getString("after_state")).thenReturn(null);
              when(rs.getString("metadata")).thenReturn(null);
              when(rs.getString("ip_address")).thenReturn("0.0.0.0");
              when(rs.getString("user_agent")).thenReturn(null);
              when(rs.getTimestamp("timestamp")).thenReturn(Timestamp.from(now));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.listForArchive(now, 10)).hasSize(1);
    assertThat(new PageResult(null, 0).rows()).isEmpty();
    assertThat(
            new com.nammamedmate.settings.application.port.out.PlatformAuditLogStore.AuditLogRow(
                    id, null, "n", "r", "ADMIN", "a", "t", null, null, null, null, "0.0.0.0", null,
                    now)
                .beforeState())
        .isNull();

    store.markArchived(id, now);
    verify(jdbc).update(anyString(), any(), eq(id));

    ObjectMapper broken = mock(ObjectMapper.class);
    when(broken.writeValueAsString(any())).thenThrow(new RuntimeException("boom"));
    JdbcPlatformAuditLogStore bad = new JdbcPlatformAuditLogStore(jdbc, broken);
    assertThatThrownBy(
            () ->
                bad.append(
                    id,
                    null,
                    "n",
                    "r",
                    "ADMIN",
                    "a",
                    "t",
                    null,
                    Map.of("a", 1),
                    null,
                    null,
                    "1.1.1.1",
                    null,
                    now))
        .isInstanceOf(IllegalStateException.class);
  }
}
