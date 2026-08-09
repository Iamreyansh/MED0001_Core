package com.nammamedmate.inventory.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.inventory.application.port.out.RackLocationStore.Kpi;
import com.nammamedmate.inventory.application.port.out.RackLocationStore.ListFilter;
import com.nammamedmate.inventory.application.port.out.RackLocationStore.ListResult;
import com.nammamedmate.inventory.application.port.out.RackLocationStore.ProductPreview;
import com.nammamedmate.inventory.application.port.out.RackLocationStore.UnlocatedPage;
import com.nammamedmate.inventory.domain.PharmacyProduct;
import com.nammamedmate.inventory.domain.RackLocation;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JdbcRackLocationStoreTest {

  @Mock private JdbcTemplate jdbc;
  private JdbcRackLocationStore store;

  private final Instant now = Instant.parse("2026-08-09T10:00:00Z");
  private final UUID pharmacy = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
  private final UUID product = UUID.fromString("11111111-2222-4333-8444-555555555555");
  private final UUID rackId = UUID.fromString("99999999-8888-4777-8666-555555555555");

  @BeforeEach
  void setUp() {
    store = new JdbcRackLocationStore(jdbc);
  }

  @Test
  @SuppressWarnings("unchecked")
  void crudListKpiAssignAndMap() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              String sql = inv.getArgument(0);
              if (sql.contains("FROM rack_location")) {
                return List.of(mapper.mapRow(mockRackRs(), 0));
              }
              if (sql.contains("SELECT id, name")) {
                return List.of(mapper.mapRow(mockPreviewRs(), 0));
              }
              return List.of(mapper.mapRow(mockProductRs(), 0));
            });
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(1L);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(1L);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any(), any())).thenReturn(1L);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
    when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(1);
    when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);

    assertThat(store.findByCode(pharmacy, "A1-01")).isPresent();

    ListResult listed = store.list(new ListFilter(pharmacy, "Zone A", "A1", 1, 50));
    assertThat(listed.total()).isEqualTo(1L);
    assertThat(listed.rows()).hasSize(1);

    store.list(new ListFilter(pharmacy, null, null, 1, 10));

    Kpi kpi = store.kpi(pharmacy);
    assertThat(kpi.racksCount()).isEqualTo(1L);

    RackLocation inserted =
        store.insert(new RackLocation(rackId, pharmacy, "Z99-99", "Zone Z", "d", now, now, null));
    assertThat(inserted.rackCode()).isEqualTo("Z99-99");

    Optional<RackLocation> deleted = store.softDelete(pharmacy, "A1-01", now);
    assertThat(deleted).isPresent();
    assertThat(deleted.get().deletedAt()).isEqualTo(now);

    List<PharmacyProduct> meds = store.medicinesInRack(pharmacy, "A1-01");
    assertThat(meds).hasSize(1);

    List<ProductPreview> blockers = store.blockingProducts(pharmacy, "A1-01", 10);
    assertThat(blockers).hasSize(1);

    assertThat(store.medicineCount(pharmacy, "A1-01")).isEqualTo(1L);

    assertThat(store.findByCodes(pharmacy, List.of("A1-01"))).hasSize(1);
    assertThat(store.findByCodes(pharmacy, List.of())).isEmpty();

    UnlocatedPage unlocated = store.unlocated(pharmacy, 1, 20);
    assertThat(unlocated.total()).isEqualTo(1L);

    // product lookup for assign: queryForObject then update
    when(jdbc.queryForObject(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return mapper.mapRow(mockProductRs(), 0);
            });
    List<UUID> assigned = store.assignRack(pharmacy, List.of(product), "A1-01", now);
    // already has A1-01 in mock → skip
    assertThat(assigned).isEmpty();

    when(jdbc.queryForObject(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return mapper.mapRow(mockProductRsEmptyRacks(), 0);
            });
    when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);
    assertThat(store.assignRack(pharmacy, List.of(product), "B2-03", now)).containsExactly(product);

    when(jdbc.queryForObject(anyString(), any(RowMapper.class), any(), any()))
        .thenThrow(new EmptyResultDataAccessException(1));
    assertThat(store.assignRack(pharmacy, List.of(product), "B2-03", now)).isEmpty();

    when(jdbc.queryForObject(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return mapper.mapRow(mockProductRs(), 0);
            });
    // already has A1-01 → return unchanged without requiring update
    assertThat(store.addRackToProduct(pharmacy, product, "A1-01", now)).isPresent();

    when(jdbc.queryForObject(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return mapper.mapRow(mockProductRsEmptyRacks(), 0);
            });
    assertThat(store.addRackToProduct(pharmacy, product, "C3-07", now)).isPresent();
    assertThat(store.removeRackFromProduct(pharmacy, product, "C3-07", now)).isPresent();

    // assign update returns 0 → not added
    when(jdbc.queryForObject(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return mapper.mapRow(mockProductRsEmptyRacks(), 0);
            });
    when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(0);
    assertThat(store.assignRack(pharmacy, List.of(product), "D4-04", now)).isEmpty();

    when(jdbc.queryForObject(anyString(), any(RowMapper.class), any(), any()))
        .thenThrow(new EmptyResultDataAccessException(1));
    assertThat(store.addRackToProduct(pharmacy, product, "C3-07", now)).isEmpty();
    assertThat(store.removeRackFromProduct(pharmacy, product, "C3-07", now)).isEmpty();

    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    assertThat(store.findByCode(pharmacy, "NOPE")).isEmpty();
    assertThat(store.findByCodes(pharmacy, null)).isEmpty();

    ResultSet deletedRs = mockRackRs();
    when(deletedRs.getTimestamp("deleted_at")).thenReturn(Timestamp.from(now));
    assertThat(JdbcRackLocationStore.mapRack(deletedRs).deletedAt()).isEqualTo(now);

    when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(0);
    // softDelete when find returns empty
    assertThat(store.softDelete(pharmacy, "NOPE", now)).isEmpty();

    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockRackRs(), 0));
            });
    when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(0);
    assertThat(store.softDelete(pharmacy, "A1-01", now)).isEmpty();

    when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(null);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(null);
    assertThat(store.kpi(pharmacy).racksCount()).isZero();
    assertThat(store.medicineCount(pharmacy, "A1-01")).isZero();
    assertThat(store.unlocated(pharmacy, 1, 5).total()).isZero();
    assertThat(store.list(new ListFilter(pharmacy, " ", " ", 1, 5)).total()).isZero();

    RackLocation mapped = JdbcRackLocationStore.mapRack(mockRackRsNulls());
    assertThat(mapped.createdAt()).isNull();
  }

  private ResultSet mockRackRs() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(rackId);
    when(rs.getObject("pharmacy_id")).thenReturn(pharmacy);
    when(rs.getString("rack_code")).thenReturn("A1-01");
    when(rs.getString("zone_name")).thenReturn("Zone A");
    when(rs.getString("description")).thenReturn("desc");
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("deleted_at")).thenReturn(null);
    return rs;
  }

  private ResultSet mockRackRsNulls() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(rackId);
    when(rs.getObject("pharmacy_id")).thenReturn(pharmacy);
    when(rs.getString("rack_code")).thenReturn("A1-01");
    when(rs.getString("zone_name")).thenReturn("Zone A");
    when(rs.getString("description")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(null);
    when(rs.getTimestamp("updated_at")).thenReturn(null);
    when(rs.getTimestamp("deleted_at")).thenReturn(null);
    return rs;
  }

  private ResultSet mockPreviewRs() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(product);
    when(rs.getString("name")).thenReturn("Amox");
    return rs;
  }

  private ResultSet mockProductRs() throws Exception {
    return mockProductRsWithRacks(new String[] {"A1-01"});
  }

  private ResultSet mockProductRsEmptyRacks() throws Exception {
    return mockProductRsWithRacks(new String[] {});
  }

  private ResultSet mockProductRsWithRacks(String[] racks) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(product);
    when(rs.getObject("pharmacy_id")).thenReturn(pharmacy);
    when(rs.getObject("master_medicine_id")).thenReturn(null);
    when(rs.getString("name")).thenReturn("Amox");
    when(rs.getString("salt_composition")).thenReturn(null);
    when(rs.getString("manufacturer")).thenReturn(null);
    when(rs.getInt("pack_size")).thenReturn(10);
    when(rs.getString("pack_unit")).thenReturn("caps");
    when(rs.getObject("category_id")).thenReturn(null);
    when(rs.getString("category_name")).thenReturn("Antibiotics");
    when(rs.getString("form")).thenReturn("CAPSULE");
    when(rs.getString("schedule")).thenReturn("H");
    when(rs.getString("hsn_code")).thenReturn(null);
    when(rs.getBigDecimal("gst_pct")).thenReturn(BigDecimal.valueOf(12));
    when(rs.getInt("gst_pct")).thenReturn(12);
    when(rs.getLong("mrp_paise")).thenReturn(4500L);
    when(rs.getBoolean("is_rx_only")).thenReturn(true);
    when(rs.getBoolean("is_loose_selling_enabled")).thenReturn(false);
    when(rs.getBoolean("is_online_visible")).thenReturn(false);
    when(rs.getInt("reorder_level")).thenReturn(0);
    Array arr = mock(Array.class);
    when(arr.getArray()).thenReturn(racks);
    when(rs.getArray("rack_locations")).thenReturn(arr);
    when(rs.getInt("total_stock_units")).thenReturn(10);
    when(rs.getInt("total_batches")).thenReturn(1);
    when(rs.getDate("earliest_expiry")).thenReturn(Date.valueOf(LocalDate.of(2027, 1, 1)));
    when(rs.getLong("cost_value_paise")).thenReturn(0L);
    when(rs.getTimestamp("last_movement_at")).thenReturn(null);
    when(rs.getString("product_photo_url")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
    return rs;
  }
}
