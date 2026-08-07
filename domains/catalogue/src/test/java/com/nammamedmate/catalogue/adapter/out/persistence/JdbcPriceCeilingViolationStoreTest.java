package com.nammamedmate.catalogue.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
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
class JdbcPriceCeilingViolationStoreTest {

  @Mock private JdbcTemplate jdbc;
  private JdbcPriceCeilingViolationStore store;

  @BeforeEach
  void setUp() {
    store = new JdbcPriceCeilingViolationStore(jdbc);
  }

  @Test
  void upsertResolveListNotify() throws Exception {
    UUID id = UUID.randomUUID();
    UUID med = UUID.randomUUID();
    UUID pharmacy = UUID.randomUUID();
    Instant now = Instant.parse("2026-08-08T00:00:00Z");
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

    store.upsertOpen(id, med, pharmacy, 7200L, 8000L, now);
    store.resolveOpenForMedicine(med, now);
    store.resolveStale(now);
    store.markNotified(null, now);
    store.markNotified(List.of(), now);
    store.markNotified(List.of(id), now);

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getObject("medicine_id")).thenReturn(med);
              when(rs.getString("medicine_name")).thenReturn("Amox");
              when(rs.getLong("ceiling_price_paise")).thenReturn(7200L);
              when(rs.getObject("pharmacy_id")).thenReturn(pharmacy);
              when(rs.getString("pharmacy_name")).thenReturn("City");
              when(rs.getLong("pharmacy_price_paise")).thenReturn(8000L);
              when(rs.getLong("overage_amount_paise")).thenReturn(800L);
              when(rs.getString("zone_name")).thenReturn("Zone");
              when(rs.getTimestamp("detected_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("last_notified_at")).thenReturn(null);
              when(rs.getString("status")).thenReturn("OPEN");
              return List.of(mapper.mapRow(rs, 0));
            });

    var page = store.list(med, UUID.randomUUID(), 1, 20);
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.rows().getFirst().pharmacyName()).isEqualTo("City");

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    assertThat(store.list(null, null, 1, 20).total()).isEqualTo(0);

    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getObject("medicine_id")).thenReturn(med);
              when(rs.getString("medicine_name")).thenReturn("Amox");
              when(rs.getObject("pharmacy_id")).thenReturn(pharmacy);
              when(rs.getLong("ceiling_price_paise")).thenReturn(7200L);
              when(rs.getLong("pharmacy_price_paise")).thenReturn(8000L);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.listOpen(med)).hasSize(1);

    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    // listOpen with null medicine uses no args array path
    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getObject("medicine_id")).thenReturn(med);
              when(rs.getString("medicine_name")).thenReturn("Amox");
              when(rs.getObject("pharmacy_id")).thenReturn(pharmacy);
              when(rs.getLong("ceiling_price_paise")).thenReturn(7200L);
              when(rs.getLong("pharmacy_price_paise")).thenReturn(8000L);
              return List.of(mapper.mapRow(rs, 0));
            });
    // Actually listOpen(null) still uses args.toArray() which may be empty Object[]
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getObject("medicine_id")).thenReturn(med);
              when(rs.getString("medicine_name")).thenReturn("Amox");
              when(rs.getObject("pharmacy_id")).thenReturn(pharmacy);
              when(rs.getLong("ceiling_price_paise")).thenReturn(7200L);
              when(rs.getLong("pharmacy_price_paise")).thenReturn(8000L);
              when(rs.getTimestamp("detected_at")).thenReturn(null);
              when(rs.getTimestamp("last_notified_at")).thenReturn(Timestamp.from(now));
              when(rs.getLong("overage_amount_paise")).thenReturn(1L);
              when(rs.getString("zone_name")).thenReturn(null);
              when(rs.getString("pharmacy_name")).thenReturn("P");
              when(rs.getString("status")).thenReturn("NOTIFIED");
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getLong("ceiling_price_paise")).thenReturn(100L);
              when(rs.getLong("pharmacy_price_paise")).thenReturn(200L);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.listOpen(null)).hasSize(1);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
    assertThat(store.list(null, null, 1, 20).rows().getFirst().lastNotifiedAt()).isEqualTo(now);
  }
}
