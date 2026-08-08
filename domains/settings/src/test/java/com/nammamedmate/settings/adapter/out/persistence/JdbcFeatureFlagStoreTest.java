package com.nammamedmate.settings.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.settings.application.port.out.FeatureFlagStore.EnvCounts;
import com.nammamedmate.settings.application.port.out.FeatureFlagStore.FeatureFlagRow;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcFeatureFlagStoreTest {

  private JdbcTemplate jdbc;
  private JdbcFeatureFlagStore store;
  private final Instant now = Instant.parse("2026-07-24T02:00:00Z");

  @BeforeEach
  void setUp() {
    jdbc = mock(JdbcTemplate.class);
    store = new JdbcFeatureFlagStore(jdbc);
  }

  @Test
  void listFindUpdateCount() throws Exception {
    UUID id = Ids.newId();
    UUID by = Ids.newId();
    stubQuery(id, by);

    assertThat(store.listByEnvironment("production")).hasSize(1);
    assertThat(store.findByNameAndEnvironment("cod_enabled", "production")).isPresent();
    assertThat(store.listAll()).hasSize(1);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getString("name")).thenReturn("cod_enabled");
              when(rs.getString("description")).thenReturn("desc");
              when(rs.getString("environment")).thenReturn("production");
              when(rs.getBoolean("enabled")).thenReturn(false);
              when(rs.getInt("rollout_percentage")).thenReturn(0);
              when(rs.getString("notes")).thenReturn("off");
              when(rs.getObject("updated_by")).thenReturn(by);
              when(rs.getString("updated_by_name")).thenReturn("Ayesha");
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    FeatureFlagRow updated = store.update(id, false, 0, "off", by, now);
    assertThat(updated.enabled()).isFalse();
    verify(jdbc).update(anyString(), eq(false), eq(0), eq("off"), eq(by), any(), eq(id));

    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              ResultSet rs = mock(ResultSet.class);
              when(rs.getString("environment")).thenReturn("production");
              when(rs.getLong("total")).thenReturn(3L);
              when(rs.getLong("enabled")).thenReturn(2L);
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    List<EnvCounts> counts = store.countByEnvironment();
    assertThat(counts).containsExactly(new EnvCounts("production", 3, 2));
  }

  @Test
  void findEmptyAndUpdateMissing() {
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(List.of());
    assertThat(store.findByNameAndEnvironment("x", "production")).isEmpty();

    UUID id = Ids.newId();
    when(jdbc.query(anyString(), any(RowMapper.class), eq(id))).thenReturn(List.of());
    assertThatThrownBy(() -> store.update(id, true, 100, null, null, now))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void mapNullTimestampsAndNullUpdatedByName() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class), eq("production")))
        .thenAnswer(
            inv -> {
              ResultSet rs = mock(ResultSet.class);
              UUID id = Ids.newId();
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getString("name")).thenReturn("x");
              when(rs.getString("description")).thenReturn("d");
              when(rs.getString("environment")).thenReturn("production");
              when(rs.getBoolean("enabled")).thenReturn(true);
              when(rs.getInt("rollout_percentage")).thenReturn(1);
              when(rs.getString("notes")).thenReturn(null);
              when(rs.getObject("updated_by")).thenReturn(null);
              when(rs.getString("updated_by_name")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(null);
              when(rs.getTimestamp("updated_at")).thenReturn(null);
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(rs, 0));
            });
    FeatureFlagRow row = store.listByEnvironment("production").get(0);
    assertThat(row.createdAt()).isNull();
    assertThat(row.updatedBy()).isNull();
  }

  private void stubQuery(UUID id, UUID by) {
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(inv -> List.of(map(inv.getArgument(1), id, by)));
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(inv -> List.of(map(inv.getArgument(1), id, by)));
    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(inv -> List.of(map(inv.getArgument(1), id, by)));
  }

  private FeatureFlagRow map(RowMapper<?> mapper, UUID id, UUID by) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getString("name")).thenReturn("cod_enabled");
    when(rs.getString("description")).thenReturn("desc");
    when(rs.getString("environment")).thenReturn("production");
    when(rs.getBoolean("enabled")).thenReturn(true);
    when(rs.getInt("rollout_percentage")).thenReturn(100);
    when(rs.getString("notes")).thenReturn(null);
    when(rs.getObject("updated_by")).thenReturn(by);
    when(rs.getString("updated_by_name")).thenReturn("Ayesha");
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
    return (FeatureFlagRow) mapper.mapRow(rs, 0);
  }
}
