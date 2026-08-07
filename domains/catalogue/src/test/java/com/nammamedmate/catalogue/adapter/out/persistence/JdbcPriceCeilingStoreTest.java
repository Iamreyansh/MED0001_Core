package com.nammamedmate.catalogue.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JdbcPriceCeilingStoreTest {

  @Mock private JdbcTemplate jdbc;
  private JdbcPriceCeilingStore store;

  @BeforeEach
  void setUp() {
    store = new JdbcPriceCeilingStore(jdbc);
  }

  @Test
  void setClearListAndQueries() throws Exception {
    UUID med = UUID.randomUUID();
    UUID admin = UUID.randomUUID();
    Instant now = Instant.parse("2026-08-08T00:00:00Z");
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

    store.setCeiling(
        med, 7200L, LocalDate.parse("2026-07-01"), "nlem", admin, "Kavya", "admin_super", now);
    store.clearCeiling(med, now);

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(med);
              when(rs.getString("name")).thenReturn("Amox");
              when(rs.getString("category_name")).thenReturn("Antibiotics");
              when(rs.getString("schedule")).thenReturn("H");
              when(rs.getLong("mrp_paise")).thenReturn(8500L);
              when(rs.getLong("mrp_ceiling_paise")).thenReturn(7200L);
              when(rs.getLong("above_cnt")).thenReturn(2L);
              when(rs.getDate("mrp_ceiling_effective_from")).thenReturn(Date.valueOf("2026-07-01"));
              when(rs.getString("mrp_ceiling_reason")).thenReturn("nlem");
              when(rs.getObject("mrp_ceiling_set_by")).thenReturn(admin);
              when(rs.getString("mrp_ceiling_set_by_name")).thenReturn("Kavya");
              when(rs.getString("mrp_ceiling_set_by_role")).thenReturn("admin_super");
              when(rs.getTimestamp("mrp_ceiling_set_at")).thenReturn(Timestamp.from(now));
              return List.of(mapper.mapRow(rs, 0));
            });

    var withViolations = store.listCeilings(UUID.randomUUID(), true, 1, 20);
    assertThat(withViolations.total()).isEqualTo(1);
    assertThat(withViolations.rows().getFirst().pharmaciesAboveCeiling()).isEqualTo(2);

    store.listCeilings(null, false, 1, 20);
    store.listCeilings(null, null, 1, 20);

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getString("name")).thenReturn("Admin");
              when(rs.getObject("pharmacy_id")).thenReturn(UUID.randomUUID());
              when(rs.getObject("master_medicine_id")).thenReturn(med);
              when(rs.getLong("pharmacy_price_paise")).thenReturn(8000L);
              when(rs.getLong("mrp_ceiling_paise")).thenReturn(7200L);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findAdminName(admin)).contains("Admin");
    assertThat(store.findAdminName(null)).isEmpty();
    when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of());
    assertThat(store.findAdminName(admin)).isEmpty();

    when(jdbc.query(anyString(), any(RowMapper.class), eq(med)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("pharmacy_id")).thenReturn(UUID.randomUUID());
              when(rs.getObject("master_medicine_id")).thenReturn(med);
              when(rs.getLong("pharmacy_price_paise")).thenReturn(8000L);
              when(rs.getLong("mrp_ceiling_paise")).thenReturn(7200L);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findAboveCeilingMappings(med)).hasSize(1);

    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("pharmacy_id")).thenReturn(UUID.randomUUID());
              when(rs.getObject("master_medicine_id")).thenReturn(med);
              when(rs.getLong("pharmacy_price_paise")).thenReturn(8000L);
              when(rs.getLong("mrp_ceiling_paise")).thenReturn(7200L);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findAllAboveCeilingMappings()).hasSize(1);

    when(jdbc.queryForObject(anyString(), eq(Long.class), eq(med))).thenReturn(3L);
    assertThat(store.countAboveCeiling(med)).isEqualTo(3L);
    when(jdbc.queryForObject(anyString(), eq(Long.class), eq(med))).thenReturn(null);
    assertThat(store.countAboveCeiling(med)).isEqualTo(0L);

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(med);
              when(rs.getString("name")).thenReturn("Amox");
              when(rs.getString("category_name")).thenReturn("Antibiotics");
              when(rs.getString("schedule")).thenReturn("H");
              when(rs.getLong("mrp_paise")).thenReturn(8500L);
              when(rs.getLong("mrp_ceiling_paise")).thenReturn(7200L);
              when(rs.getLong("above_cnt")).thenReturn(0L);
              when(rs.getDate("mrp_ceiling_effective_from")).thenReturn(null);
              when(rs.getString("mrp_ceiling_reason")).thenReturn(null);
              when(rs.getObject("mrp_ceiling_set_by")).thenReturn(null);
              when(rs.getString("mrp_ceiling_set_by_name")).thenReturn(null);
              when(rs.getString("mrp_ceiling_set_by_role")).thenReturn(null);
              when(rs.getTimestamp("mrp_ceiling_set_at")).thenReturn(null);
              return List.of(mapper.mapRow(rs, 0));
            });
    var nullMeta = store.listCeilings(null, true, 1, 20);
    assertThat(nullMeta.total()).isEqualTo(0);
    assertThat(nullMeta.rows().getFirst().effectiveFrom()).isNull();
    assertThat(nullMeta.rows().getFirst().setAt()).isNull();
  }
}
