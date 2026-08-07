package com.nammamedmate.catalogue.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore.AdminListFilter;
import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore.CatalogueStats;
import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore.ListResult;
import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore.MappingRow;
import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore.PharmacyListFilter;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JdbcMedicineMappingStoreTest {

  @Mock private JdbcTemplate jdbc;
  private JdbcMedicineMappingStore store;
  private final Instant now = Instant.parse("2026-08-08T00:00:00Z");

  @BeforeEach
  void setUp() {
    store = new JdbcMedicineMappingStore(jdbc, new ObjectMapper());
  }

  @Test
  void crudAndQueries() throws Exception {
    UUID id = UUID.randomUUID();
    UUID pharmacy = UUID.randomUUID();
    UUID medicine = UUID.randomUUID();
    MappingRow row = new MappingRow(id, pharmacy, medicine, 21500L, 48, true, now, now);

    store.insert(row);
    verify(jdbc).update(anyString(), any(), any(), any(), any(), any(), any(), any(), any());

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(inv -> List.of(mapRow(inv.getArgument(1), id, pharmacy, medicine)));
    assertThat(store.findById(id)).isPresent();

    when(jdbc.query(anyString(), any(RowMapper.class), eq(pharmacy), eq(medicine)))
        .thenAnswer(inv -> List.of(mapRow(inv.getArgument(1), id, pharmacy, medicine)));
    assertThat(store.findByPharmacyAndMedicine(pharmacy, medicine)).isPresent();

    when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(pharmacy), eq(medicine)))
        .thenReturn(1);
    assertThat(store.exists(pharmacy, medicine)).isTrue();

    store.update(id, 21000L, 40, false, now);
    store.delete(id);
    store.incrementMappedCount(medicine, 1);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(medicine)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(medicine);
              when(rs.getString("name")).thenReturn("Augmentin");
              when(rs.getLong("mrp_paise")).thenReturn(21850L);
              when(rs.getObject("mrp_ceiling_paise")).thenReturn(null);
              when(rs.getString("schedule")).thenReturn("H");
              when(rs.getBoolean("is_banned")).thenReturn(false);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findMedicine(medicine)).isPresent();

    when(jdbc.query(anyString(), any(RowMapper.class), eq(pharmacy)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getString("status")).thenReturn("ACTIVE");
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.pharmacyStatus(pharmacy)).contains("ACTIVE");

    when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(medicine))).thenReturn(3);
    assertThat(store.hideAllForMedicine(medicine)).isEqualTo(3);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(pharmacy))).thenReturn(2);
    assertThat(store.hideAllForPharmacy(pharmacy)).isEqualTo(2);
    store.restoreAllForPharmacy(pharmacy);

    when(jdbc.query(anyString(), any(ResultSetExtractor.class), eq(pharmacy)))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<?> ex = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.next()).thenReturn(true);
              when(rs.getInt("mapped")).thenReturn(5);
              when(rs.getInt("in_stock")).thenReturn(3);
              when(rs.getInt("oos")).thenReturn(2);
              return ex.extractData(rs);
            });
    CatalogueStats stats = store.statsForPharmacy(pharmacy);
    assertThat(stats.mappedSkus()).isEqualTo(5);
  }

  @Test
  void listForPharmacyAndAdmin() throws Exception {
    UUID pharmacy = UUID.randomUUID();
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(UUID.randomUUID());
              when(rs.getObject("master_medicine_id")).thenReturn(UUID.randomUUID());
              when(rs.getString("name")).thenReturn("A");
              when(rs.getString("salt_composition")).thenReturn("s");
              when(rs.getString("manufacturer")).thenReturn("m");
              when(rs.getString("category_name")).thenReturn("c");
              when(rs.getString("form")).thenReturn("TABLET");
              when(rs.getBigDecimal("pack_size")).thenReturn(new BigDecimal("10"));
              when(rs.getString("schedule")).thenReturn("H");
              when(rs.getBoolean("is_rx_only")).thenReturn(true);
              when(rs.getLong("mrp_paise")).thenReturn(100L);
              when(rs.getObject("mrp_ceiling_paise")).thenReturn(null);
              when(rs.getLong("pharmacy_price_paise")).thenReturn(90L);
              when(rs.getInt("stock_quantity")).thenReturn(1);
              when(rs.getBoolean("is_visible")).thenReturn(true);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
              return List.of(mapper.mapRow(rs, 0));
            });

    ListResult result =
        store.listForPharmacy(
            new PharmacyListFilter(
                pharmacy, true, true, UUID.randomUUID(), "aug", "stock_quantity", "desc", 1, 20));
    assertThat(result.total()).isEqualTo(1);
    store.listForPharmacy(
        new PharmacyListFilter(pharmacy, null, false, null, null, "created_at", "asc", 1, 10));
    store.listForPharmacy(
        new PharmacyListFilter(pharmacy, null, null, null, null, "pharmacy_price", "asc", 1, 10));

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any(), any())).thenReturn(1L);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(2L);
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(UUID.randomUUID());
              when(rs.getObject("pharmacy_id")).thenReturn(pharmacy);
              when(rs.getString("pharmacy_name")).thenReturn("Sharma");
              when(rs.getString("zone_name")).thenReturn("Zone");
              when(rs.getLong("pharmacy_price_paise")).thenReturn(21000L);
              when(rs.getInt("stock_quantity")).thenReturn(1);
              when(rs.getBoolean("is_visible")).thenReturn(true);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              when(rs.getObject("mrp_ceiling_paise")).thenReturn(20000L);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(
            store
                .listForAdmin(
                    new AdminListFilter(UUID.randomUUID(), UUID.randomUUID(), true, true, 1, 20))
                .totalStocking())
        .isEqualTo(2);
  }

  @Test
  void bulkJobLifecycle() throws Exception {
    UUID jobId = UUID.randomUUID();
    UUID pharmacy = UUID.randomUUID();
    store.insertBulkJob(jobId, List.of(pharmacy), Map.of("k", "v"), UUID.randomUUID(), now);
    store.markBulkJobRunning(jobId, now);
    store.markBulkJobCompleted(jobId, 1, 1, 0, 0, List.of(), now);

    Array arr = mock(Array.class);
    when(arr.getArray()).thenReturn(new Object[] {pharmacy});
    when(jdbc.query(anyString(), any(RowMapper.class), eq(jobId)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(jobId);
              when(rs.getString("action")).thenReturn("BULK_MAP");
              when(rs.getString("status")).thenReturn("QUEUED");
              when(rs.getArray("pharmacy_ids")).thenReturn(arr);
              when(rs.getString("payload")).thenReturn("{\"a\":1}");
              when(rs.getObject("initiated_by")).thenReturn(UUID.randomUUID());
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findBulkJob(jobId)).isPresent();

    when(jdbc.query(anyString(), any(RowMapper.class), eq(5)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(jobId);
              when(rs.getString("action")).thenReturn("BULK_MAP");
              when(rs.getString("status")).thenReturn("QUEUED");
              when(rs.getArray("pharmacy_ids")).thenReturn(null);
              when(rs.getString("payload")).thenReturn(null);
              when(rs.getObject("initiated_by")).thenReturn(UUID.randomUUID());
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findQueuedBulkMapJobs(5)).hasSize(1);
  }

  @Test
  void emptyOptionalAndStatsEmpty() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of());
    assertThat(store.findById(UUID.randomUUID())).isEmpty();
    assertThat(store.findByPharmacyAndMedicine(UUID.randomUUID(), UUID.randomUUID())).isEmpty();
    assertThat(store.findMedicine(UUID.randomUUID())).isEmpty();
    assertThat(store.pharmacyStatus(UUID.randomUUID())).isEmpty();
    assertThat(store.findBulkJob(UUID.randomUUID())).isEmpty();

    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(null);
    assertThat(store.hideAllForMedicine(UUID.randomUUID())).isZero();
    assertThat(store.hideAllForPharmacy(UUID.randomUUID())).isZero();

    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(null);
    assertThat(store.exists(UUID.randomUUID(), UUID.randomUUID())).isFalse();

    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any()))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<?> ex = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.next()).thenReturn(false);
              return ex.extractData(rs);
            });
    assertThat(store.statsForPharmacy(UUID.randomUUID()).mappedSkus()).isZero();

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    assertThat(
            store
                .listForPharmacy(
                    new PharmacyListFilter(
                        UUID.randomUUID(), null, null, null, null, "name", "asc", 1, 10))
                .total())
        .isZero();

    store.insertBulkJob(
        UUID.randomUUID(), List.of(UUID.randomUUID()), null, UUID.randomUUID(), now);
  }

  @Test
  void jsonFailures() throws Exception {
    com.fasterxml.jackson.databind.ObjectMapper bad =
        mock(com.fasterxml.jackson.databind.ObjectMapper.class);
    when(bad.writeValueAsString(any()))
        .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("boom") {});
    JdbcMedicineMappingStore broken = new JdbcMedicineMappingStore(jdbc, bad);
    assertThatThrownBy(
            () ->
                broken.insertBulkJob(
                    UUID.randomUUID(), List.of(), Map.of("a", 1), UUID.randomUUID(), now))
        .isInstanceOf(IllegalStateException.class);

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(UUID.randomUUID());
              when(rs.getString("action")).thenReturn("BULK_MAP");
              when(rs.getString("status")).thenReturn("QUEUED");
              when(rs.getArray("pharmacy_ids")).thenReturn(null);
              when(rs.getString("payload")).thenReturn("{bad");
              when(rs.getObject("initiated_by")).thenReturn(UUID.randomUUID());
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThatThrownBy(() -> store.findBulkJob(UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class);

    when(jdbc.query(anyString(), any(RowMapper.class), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(UUID.randomUUID());
              when(rs.getString("action")).thenReturn("BULK_MAP");
              when(rs.getString("status")).thenReturn("QUEUED");
              when(rs.getArray("pharmacy_ids")).thenReturn(null);
              when(rs.getString("payload")).thenReturn("   ");
              when(rs.getObject("initiated_by")).thenReturn(UUID.randomUUID());
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findBulkJob(UUID.randomUUID())).isPresent();
  }

  @Test
  void listFilters_nullSortAndAdminNullFilters_andAboveCeilingBranches() throws Exception {
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(UUID.randomUUID());
              when(rs.getObject("master_medicine_id")).thenReturn(UUID.randomUUID());
              when(rs.getString("name")).thenReturn("A");
              when(rs.getString("salt_composition")).thenReturn("s");
              when(rs.getString("manufacturer")).thenReturn("m");
              when(rs.getString("category_name")).thenReturn("c");
              when(rs.getString("form")).thenReturn("TABLET");
              when(rs.getBigDecimal("pack_size")).thenReturn(new BigDecimal("10"));
              when(rs.getString("schedule")).thenReturn("H");
              when(rs.getBoolean("is_rx_only")).thenReturn(true);
              when(rs.getLong("mrp_paise")).thenReturn(100L);
              when(rs.getObject("mrp_ceiling_paise")).thenReturn(null);
              when(rs.getLong("pharmacy_price_paise")).thenReturn(90L);
              when(rs.getInt("stock_quantity")).thenReturn(1);
              when(rs.getBoolean("is_visible")).thenReturn(true);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
              return List.of(mapper.mapRow(rs, 0));
            });
    store.listForPharmacy(
        new PharmacyListFilter(UUID.randomUUID(), null, null, null, "  ", null, null, 1, 10));
    store.listForPharmacy(
        new PharmacyListFilter(UUID.randomUUID(), null, null, null, "aug", "NAME", "ASC", 1, 10));

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs1 = mock(ResultSet.class);
              when(rs1.getObject("id")).thenReturn(UUID.randomUUID());
              when(rs1.getObject("pharmacy_id")).thenReturn(UUID.randomUUID());
              when(rs1.getString("pharmacy_name")).thenReturn("P");
              when(rs1.getString("zone_name")).thenReturn(null);
              when(rs1.getLong("pharmacy_price_paise")).thenReturn(100L);
              when(rs1.getInt("stock_quantity")).thenReturn(1);
              when(rs1.getBoolean("is_visible")).thenReturn(true);
              when(rs1.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              when(rs1.getObject("mrp_ceiling_paise")).thenReturn(null);
              ResultSet rs2 = mock(ResultSet.class);
              when(rs2.getObject("id")).thenReturn(UUID.randomUUID());
              when(rs2.getObject("pharmacy_id")).thenReturn(UUID.randomUUID());
              when(rs2.getString("pharmacy_name")).thenReturn("P2");
              when(rs2.getString("zone_name")).thenReturn("Z");
              when(rs2.getLong("pharmacy_price_paise")).thenReturn(100L);
              when(rs2.getInt("stock_quantity")).thenReturn(1);
              when(rs2.getBoolean("is_visible")).thenReturn(true);
              when(rs2.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              when(rs2.getObject("mrp_ceiling_paise")).thenReturn(200L);
              ResultSet rs3 = mock(ResultSet.class);
              when(rs3.getObject("id")).thenReturn(UUID.randomUUID());
              when(rs3.getObject("pharmacy_id")).thenReturn(UUID.randomUUID());
              when(rs3.getString("pharmacy_name")).thenReturn("P3");
              when(rs3.getString("zone_name")).thenReturn("Z");
              when(rs3.getLong("pharmacy_price_paise")).thenReturn(100L);
              when(rs3.getInt("stock_quantity")).thenReturn(1);
              when(rs3.getBoolean("is_visible")).thenReturn(true);
              when(rs3.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              when(rs3.getObject("mrp_ceiling_paise")).thenReturn(50L);
              return List.of(mapper.mapRow(rs1, 0), mapper.mapRow(rs2, 1), mapper.mapRow(rs3, 2));
            });
    var admin =
        store.listForAdmin(new AdminListFilter(UUID.randomUUID(), null, null, false, 1, 20));
    assertThat(admin.rows()).hasSize(3);
    assertThat(admin.rows().get(0).aboveCeiling()).isFalse();
    assertThat(admin.rows().get(1).aboveCeiling()).isFalse();
    assertThat(admin.rows().get(2).aboveCeiling()).isTrue();

    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(0);
    assertThat(store.exists(UUID.randomUUID(), UUID.randomUUID())).isFalse();
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(2);
    assertThat(store.exists(UUID.randomUUID(), UUID.randomUUID())).isTrue();
  }

  private MappingRow mapRow(RowMapper<?> mapper, UUID id, UUID pharmacy, UUID medicine)
      throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getObject("pharmacy_id")).thenReturn(pharmacy);
    when(rs.getObject("master_medicine_id")).thenReturn(medicine);
    when(rs.getLong("pharmacy_price_paise")).thenReturn(21500L);
    when(rs.getInt("stock_quantity")).thenReturn(48);
    when(rs.getBoolean("is_visible")).thenReturn(true);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
    return (MappingRow) mapper.mapRow(rs, 0);
  }
}
