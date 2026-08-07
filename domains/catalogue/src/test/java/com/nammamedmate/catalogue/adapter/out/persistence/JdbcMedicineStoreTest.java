package com.nammamedmate.catalogue.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.catalogue.application.port.out.MedicineStore.ListFilter;
import com.nammamedmate.catalogue.application.port.out.MedicineStore.MedicineRow;
import com.nammamedmate.catalogue.application.port.out.MedicineStore.SummaryStats;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Connection;
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
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JdbcMedicineStoreTest {

  @Mock private JdbcTemplate jdbc;
  private JdbcMedicineStore store;

  @BeforeEach
  void setUp() {
    store = new JdbcMedicineStore(jdbc);
  }

  @Test
  void insertFindListSummaryAndMutations() throws Exception {
    UUID id = UUID.randomUUID();
    UUID cat = UUID.randomUUID();
    Instant now = Instant.parse("2026-08-08T00:00:00Z");
    MedicineRow row =
        new MedicineRow(
            id,
            "Augmentin",
            "Amox",
            "GSK",
            cat,
            "Antibiotics",
            "TABLET",
            new BigDecimal("10.00"),
            "TABLET",
            "H",
            "30041090",
            12,
            21850L,
            null,
            true,
            false,
            null,
            0,
            0,
            List.of(),
            "d",
            UUID.randomUUID(),
            now,
            now);

    Array arr = mock(Array.class);
    when(jdbc.execute(any(ConnectionCallback.class)))
        .thenAnswer(
            inv -> {
              ConnectionCallback<?> cb = inv.getArgument(0);
              Connection conn = mock(Connection.class);
              when(conn.createArrayOf(eq("uuid"), any())).thenReturn(arr);
              return cb.doInConnection(conn);
            });
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    store.insert(row);

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mockRs(id, cat, now, false);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findById(id)).isPresent();

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mockRs(id, cat, now, true);
              when(rs.getString("ban_reason")).thenReturn("ban");
              when(rs.getBoolean("is_banned")).thenReturn(true);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(
            store
                .list(
                    new ListFilter(
                        cat, "H", 12, true, true, "aug", "monthly_demand", "desc", 1, 20))
                .total())
        .isEqualTo(1);

    store.list(
        new ListFilter(null, null, null, null, false, null, "mapped_pharmacy_count", "asc", 1, 10));
    store.list(new ListFilter(null, null, null, null, false, "  ", "created_at", "asc", 1, 10));
    store.list(new ListFilter(null, null, null, null, false, null, "name", "asc", 1, 10));
    store.list(new ListFilter(null, null, null, null, false, null, "mrp", "asc", 1, 10));

    when(jdbc.query(anyString(), any(ResultSetExtractor.class)))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<?> ex = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.next()).thenReturn(true);
              when(rs.getLong("total_skus")).thenReturn(1L);
              when(rs.getLong("category_count")).thenReturn(1L);
              when(rs.getLong("rx_only_count")).thenReturn(1L);
              when(rs.getLong("otc_count")).thenReturn(0L);
              when(rs.getLong("banned_count")).thenReturn(0L);
              when(rs.getLong("schedule_h_count")).thenReturn(1L);
              when(rs.getLong("schedule_h1_count")).thenReturn(0L);
              when(rs.getLong("schedule_x_count")).thenReturn(0L);
              when(rs.getLong("avg_mrp_paise")).thenReturn(21850L);
              when(rs.wasNull()).thenReturn(false);
              when(rs.getLong("total_pharmacy_mappings")).thenReturn(0L);
              return ex.extractData(rs);
            });
    SummaryStats stats = store.summary(now);
    assertThat(stats.totalSkus()).isEqualTo(1);

    when(jdbc.query(anyString(), any(ResultSetExtractor.class)))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<?> ex = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.next()).thenReturn(false);
              return ex.extractData(rs);
            });
    assertThat(store.summary(now).avgMrpPaise()).isNull();

    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(1);
    assertThat(store.hsnExists("30041090")).isTrue();
    assertThat(store.categoryActive(cat)).isTrue();
    assertThat(store.countExistingIds(List.of(id))).isEqualTo(1);
    assertThat(store.countExistingIds(List.of())).isZero();
    assertThat(store.countActiveByCategoryId(cat)).isEqualTo(1);

    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    assertThat(store.findSubstituteRefs(List.of())).isEmpty();
    store.findSubstituteRefs(List.of(id));

    when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of(id));
    assertThat(store.listAllIds()).containsExactly(id);

    when(jdbc.execute(any(ConnectionCallback.class)))
        .thenAnswer(
            inv -> {
              ConnectionCallback<?> cb = inv.getArgument(0);
              Connection c = mock(Connection.class);
              when(c.createArrayOf(eq("uuid"), any())).thenReturn(arr);
              return cb.doInConnection(c);
            });
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    store.update(id, "n", "d", cat, "H", 12, 100L, true, List.of(id), now);
    store.update(id, null, null, null, null, null, null, null, null, now);
    store.setBanned(id, true, "r", now);
    store.updateMonthlyDemand(id, 3, now);
  }

  @Test
  void readUuidArrayVariants() throws Exception {
    UUID id = UUID.randomUUID();
    UUID cat = UUID.randomUUID();
    Instant now = Instant.parse("2026-08-08T00:00:00Z");
    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mockRs(id, cat, now, false);
              Array array = mock(Array.class);
              when(array.getArray()).thenReturn(new Object[] {id, id.toString(), null});
              when(rs.getArray("substitutes")).thenReturn(array);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findById(id).orElseThrow().substitutes()).hasSize(2);

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mockRs(id, cat, now, false);
              when(rs.getArray("substitutes")).thenReturn(null);
              when(rs.getLong("mrp_ceiling_paise")).thenReturn(0L);
              when(rs.wasNull()).thenReturn(true);
              when(rs.getTimestamp("created_at")).thenReturn(null);
              when(rs.getTimestamp("updated_at")).thenReturn(null);
              when(rs.getString("hsn_code")).thenReturn(null);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findById(id).orElseThrow().mrpCeilingPaise()).isNull();

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mockRs(id, cat, now, false);
              Array array = mock(Array.class);
              when(array.getArray()).thenReturn(new UUID[] {id});
              when(rs.getArray("substitutes")).thenReturn(array);
              when(rs.getLong("mrp_ceiling_paise")).thenReturn(100L);
              when(rs.wasNull()).thenReturn(false);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findById(id).orElseThrow().mrpCeilingPaise()).isEqualTo(100L);

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mockRs(id, cat, now, false);
              Array array = mock(Array.class);
              when(array.getArray()).thenReturn("not-array");
              when(rs.getArray("substitutes")).thenReturn(array);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findById(id).orElseThrow().substitutes()).isEmpty();

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    assertThat(
            store
                .list(new ListFilter(null, null, null, null, false, null, null, null, 1, 10))
                .total())
        .isZero();

    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(null);
    assertThat(store.hsnExists("x")).isFalse();
    assertThat(store.categoryActive(cat)).isFalse();
    assertThat(store.countExistingIds(List.of(id))).isZero();
    assertThat(store.countExistingIds(null)).isZero();
    assertThat(store.countActiveByCategoryId(cat)).isZero();
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(0);
    assertThat(store.hsnExists("30041090")).isFalse();
    assertThat(store.categoryActive(cat)).isFalse();

    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getString("name")).thenReturn("n");
              when(rs.getString("manufacturer")).thenReturn("m");
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findSubstituteRefs(List.of(id))).hasSize(1);
    assertThat(store.findSubstituteRefs(null)).isEmpty();

    when(jdbc.query(anyString(), any(ResultSetExtractor.class)))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<?> ex = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.next()).thenReturn(true);
              when(rs.getLong("avg_mrp_paise")).thenReturn(0L);
              when(rs.wasNull()).thenReturn(true);
              when(rs.getLong("total_skus")).thenReturn(0L);
              when(rs.getLong("category_count")).thenReturn(0L);
              when(rs.getLong("rx_only_count")).thenReturn(0L);
              when(rs.getLong("otc_count")).thenReturn(0L);
              when(rs.getLong("banned_count")).thenReturn(0L);
              when(rs.getLong("schedule_h_count")).thenReturn(0L);
              when(rs.getLong("schedule_h1_count")).thenReturn(0L);
              when(rs.getLong("schedule_x_count")).thenReturn(0L);
              when(rs.getLong("total_pharmacy_mappings")).thenReturn(0L);
              return ex.extractData(rs);
            });
    assertThat(store.summary(now).avgMrpPaise()).isNull();

    when(jdbc.query(anyString(), any(RowMapper.class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.listAllIds()).containsExactly(id);

    MedicineRow withCeiling =
        new MedicineRow(
            id,
            "n",
            "s",
            "m",
            cat,
            "c",
            "TABLET",
            BigDecimal.ONE,
            "TABLET",
            "OTC",
            "30041090",
            5,
            100L,
            50L,
            false,
            false,
            null,
            0,
            0,
            List.of(id),
            null,
            null,
            now,
            now);
    store.insert(withCeiling);
  }

  private static ResultSet mockRs(UUID id, UUID cat, Instant now, boolean banned) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getString("name")).thenReturn("Augmentin");
    when(rs.getString("salt_composition")).thenReturn("Amox");
    when(rs.getString("manufacturer")).thenReturn("GSK");
    when(rs.getObject("category_id")).thenReturn(cat);
    when(rs.getString("category_name")).thenReturn("Antibiotics");
    when(rs.getString("form")).thenReturn("TABLET");
    when(rs.getBigDecimal("pack_size")).thenReturn(new BigDecimal("10.00"));
    when(rs.getString("pack_unit")).thenReturn("TABLET");
    when(rs.getString("schedule")).thenReturn("H");
    when(rs.getString("hsn_code")).thenReturn("30041090");
    when(rs.getInt("gst_pct")).thenReturn(12);
    when(rs.getLong("mrp_paise")).thenReturn(21850L);
    when(rs.getLong("mrp_ceiling_paise")).thenReturn(0L);
    when(rs.wasNull()).thenReturn(true);
    when(rs.getBoolean("is_rx_only")).thenReturn(true);
    when(rs.getBoolean("is_banned")).thenReturn(banned);
    when(rs.getString("ban_reason")).thenReturn(null);
    when(rs.getInt("monthly_demand")).thenReturn(0);
    when(rs.getInt("mapped_pharmacy_count")).thenReturn(0);
    Array array = mock(Array.class);
    when(array.getArray()).thenReturn(new UUID[] {});
    when(rs.getArray("substitutes")).thenReturn(array);
    when(rs.getString("description")).thenReturn("d");
    when(rs.getObject("created_by")).thenReturn(UUID.randomUUID());
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
    return rs;
  }
}
