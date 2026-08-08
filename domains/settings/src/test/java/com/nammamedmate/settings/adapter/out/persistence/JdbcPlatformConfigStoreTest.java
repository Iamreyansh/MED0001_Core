package com.nammamedmate.settings.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.settings.application.port.out.PlatformConfigStore.ConfigRow;
import com.nammamedmate.settings.application.port.out.PlatformConfigStore.HistoryRow;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcPlatformConfigStoreTest {

  private JdbcTemplate jdbc;
  private JdbcPlatformConfigStore store;
  private final Instant now = Instant.parse("2026-07-24T02:00:00Z");

  @BeforeEach
  void setUp() {
    jdbc = mock(JdbcTemplate.class);
    store = new JdbcPlatformConfigStore(jdbc);
  }

  @Test
  void listFindUpdateHistory() throws Exception {
    UUID by = Ids.newId();
    stubConfigQuery(by);

    assertThat(store.listAll()).hasSize(1);
    assertThat(store.listByDomain("orders")).hasSize(1);
    assertThat(store.findByKey("orders.delivery_fee")).isPresent();

    store.updateValue("orders.delivery_fee", "30", by, now);
    verify(jdbc).update(anyString(), eq("30"), eq(by), any(), eq("orders.delivery_fee"));

    UUID hid = Ids.newId();
    store.insertHistory(hid, "orders.delivery_fee", "25", "30", by, now, "note");
    verify(jdbc)
        .update(
            anyString(),
            eq(hid),
            eq("orders.delivery_fee"),
            eq("25"),
            eq("30"),
            eq(by),
            any(),
            eq("note"));

    when(jdbc.query(anyString(), any(RowMapper.class), eq("orders.delivery_fee")))
        .thenAnswer(
            inv -> {
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(hid);
              when(rs.getString("key")).thenReturn("orders.delivery_fee");
              when(rs.getString("old_value")).thenReturn("25");
              when(rs.getString("new_value")).thenReturn("30");
              when(rs.getObject("changed_by")).thenReturn(by);
              when(rs.getString("changed_by_name")).thenReturn("Ayesha");
              when(rs.getTimestamp("changed_at")).thenReturn(Timestamp.from(now));
              when(rs.getString("notes")).thenReturn("note");
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    List<HistoryRow> history = store.listHistory("orders.delivery_fee");
    assertThat(history).hasSize(1);
    assertThat(history.get(0).changedByName()).isEqualTo("Ayesha");
  }

  @Test
  void findEmptyAndNullTimestamps() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class), eq("missing"))).thenReturn(List.of());
    assertThat(store.findByKey("missing")).isEmpty();

    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              ResultSet rs = mock(ResultSet.class);
              when(rs.getString("key")).thenReturn("k");
              when(rs.getString("value")).thenReturn("v");
              when(rs.getString("type")).thenReturn("string");
              when(rs.getString("unit")).thenReturn(null);
              when(rs.getString("domain")).thenReturn("orders");
              when(rs.getBoolean("immutable")).thenReturn(true);
              when(rs.getString("description")).thenReturn("d");
              when(rs.getObject("updated_by")).thenReturn(null);
              when(rs.getTimestamp("updated_at")).thenReturn(null);
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    ConfigRow row = store.listAll().get(0);
    assertThat(row.updatedAt()).isNull();
    assertThat(row.immutable()).isTrue();

    when(jdbc.query(anyString(), any(RowMapper.class), eq("k")))
        .thenAnswer(
            inv -> {
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(Ids.newId());
              when(rs.getString("key")).thenReturn("k");
              when(rs.getString("old_value")).thenReturn(null);
              when(rs.getString("new_value")).thenReturn("v");
              when(rs.getObject("changed_by")).thenReturn(Ids.newId());
              when(rs.getString("changed_by_name")).thenReturn(null);
              when(rs.getTimestamp("changed_at")).thenReturn(null);
              when(rs.getString("notes")).thenReturn(null);
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.listHistory("k").get(0).changedAt()).isNull();
  }

  @SuppressWarnings("unchecked")
  private void stubConfigQuery(UUID by) throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(inv -> List.of(mapConfig(inv.getArgument(1), by)));
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(inv -> List.of(mapConfig(inv.getArgument(1), by)));
  }

  private ConfigRow mapConfig(RowMapper<?> mapper, UUID by) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getString("key")).thenReturn("orders.delivery_fee");
    when(rs.getString("value")).thenReturn("25");
    when(rs.getString("type")).thenReturn("integer");
    when(rs.getString("unit")).thenReturn("INR");
    when(rs.getString("domain")).thenReturn("orders");
    when(rs.getBoolean("immutable")).thenReturn(false);
    when(rs.getString("description")).thenReturn("desc");
    when(rs.getObject("updated_by")).thenReturn(by);
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
    return (ConfigRow) mapper.mapRow(rs, 0);
  }
}
