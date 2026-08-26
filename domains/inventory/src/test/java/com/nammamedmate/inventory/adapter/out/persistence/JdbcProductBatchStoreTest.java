package com.nammamedmate.inventory.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.inventory.application.port.out.ProductBatchStore.AdjustmentLogRow;
import com.nammamedmate.inventory.application.port.out.ProductBatchStore.ExpiryAlertRow;
import com.nammamedmate.inventory.application.port.out.ProductBatchStore.ExpiryReportRow;
import com.nammamedmate.inventory.application.port.out.ProductBatchStore.ProductStockAgg;
import com.nammamedmate.inventory.domain.ProductBatch;
import java.math.BigDecimal;
import java.sql.Array;
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
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JdbcProductBatchStoreTest {

  @Mock private JdbcTemplate jdbc;
  private JdbcProductBatchStore store;
  private final Instant now = Instant.parse("2026-08-09T00:00:00Z");
  private final UUID pharmacy = UUID.randomUUID();
  private final UUID product = UUID.randomUUID();
  private final UUID batch = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    store = new JdbcProductBatchStore(jdbc);
  }

  @Test
  void crudAndQueries() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockBatchRs(), 0));
            });
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockBatchRs(), 0));
            });
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockBatchRs(), 0));
            });
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

    assertThat(store.listByProduct(pharmacy, product, true)).hasSize(1);
    assertThat(store.listByProduct(pharmacy, product, false)).hasSize(1);
    assertThat(store.findById(pharmacy, product, batch)).isPresent();
    assertThat(store.findByBatchNumber(pharmacy, product, "BN")).isPresent();

    ProductBatch inserted =
        new ProductBatch(
            batch,
            product,
            pharmacy,
            "BN",
            LocalDate.of(2027, 1, 1),
            LocalDate.of(2025, 1, 1),
            10,
            10,
            100L,
            200L,
            true,
            null,
            null,
            null,
            now,
            now);
    store.insert(inserted);
    store.updateQuantities(batch, 12, 12, true, now);
    store.writeOff(batch, "EXPIRED", "n", now);

    store.insertAdjustmentLog(
        new AdjustmentLogRow(
            UUID.randomUUID(), batch, pharmacy, UUID.randomUUID(), -1, "DAMAGE", 10, 9, now));
    store.insertStockMovement(
        UUID.randomUUID(),
        pharmacy,
        product,
        batch,
        "ADJUSTMENT",
        -1,
        "DAMAGE",
        UUID.randomUUID(),
        now);

    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(), any()))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<?> ex = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.next()).thenReturn(true);
              when(rs.getInt("total_stock_units")).thenReturn(10);
              when(rs.getInt("total_batches")).thenReturn(1);
              when(rs.getDate("earliest_expiry")).thenReturn(Date.valueOf("2027-01-01"));
              when(rs.getLong("cost_value_paise")).thenReturn(1000L);
              return ex.extractData(rs);
            });
    ProductStockAgg agg = store.aggregateActive(pharmacy, product);
    assertThat(agg.totalStockUnits()).isEqualTo(10);
    store.refreshProductDenorm(pharmacy, product, now);
    verify(jdbc).update(anyString(), any(), any(), any(), any(), any(), any(), any(), any());

    assertThat(store.listFefoEligible(pharmacy, product, LocalDate.of(2026, 8, 9))).hasSize(1);

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("product_id")).thenReturn(product);
              when(rs.getString("product_name")).thenReturn("P");
              when(rs.getString("batch_number")).thenReturn("BN");
              when(rs.getDate("expiry_date")).thenReturn(Date.valueOf("2026-09-01"));
              when(rs.getInt("quantity_current")).thenReturn(5);
              when(rs.getLong("purchase_price_paise")).thenReturn(100L);
              when(rs.getArray("rack_locations")).thenReturn(null);
              when(rs.getString("rack_location")).thenReturn("A1");
              Object row = mapper.mapRow(rs, 0);
              return List.of(row);
            });
    List<ExpiryAlertRow> alerts =
        store.listExpiringWithinMonths(pharmacy, 4, LocalDate.of(2026, 8, 9));
    assertThat(alerts).hasSize(1);
    List<ExpiryReportRow> report = store.listExpiryReport(pharmacy, 4, LocalDate.of(2026, 8, 9));
    assertThat(report).hasSize(1);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(batch)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(UUID.randomUUID());
              when(rs.getObject("batch_id")).thenReturn(batch);
              when(rs.getObject("pharmacy_id")).thenReturn(pharmacy);
              when(rs.getObject("staff_id")).thenReturn(UUID.randomUUID());
              when(rs.getInt("adjustment")).thenReturn(-1);
              when(rs.getString("reason")).thenReturn("DAMAGE");
              when(rs.getInt("before_qty")).thenReturn(10);
              when(rs.getInt("after_qty")).thenReturn(9);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.listAdjustments(batch)).hasSize(1);

    Array arr = mock(Array.class);
    when(arr.getArray()).thenReturn(new String[] {"A1"});
    assertThat(JdbcProductBatchStore.readTextArray(arr)).containsExactly("A1");
    when(arr.getArray()).thenReturn(new Object[] {"B", null, BigDecimal.ONE});
    assertThat(JdbcProductBatchStore.readTextArray(arr)).hasSize(2);
    when(arr.getArray()).thenReturn(new int[] {1});
    assertThat(JdbcProductBatchStore.readTextArray(arr)).isEmpty();
    assertThat(JdbcProductBatchStore.readTextArray(null)).isEmpty();

    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(), any()))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<?> ex = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.next()).thenReturn(true);
              when(rs.getInt("total_stock_units")).thenReturn(0);
              when(rs.getInt("total_batches")).thenReturn(0);
              when(rs.getDate("earliest_expiry")).thenReturn(null);
              when(rs.getLong("cost_value_paise")).thenReturn(0L);
              return ex.extractData(rs);
            });
    ProductStockAgg emptyAgg = store.aggregateActive(pharmacy, product);
    assertThat(emptyAgg.earliestExpiry()).isNull();
    store.refreshProductDenorm(pharmacy, product, now);

    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(batch);
              when(rs.getObject("product_id")).thenReturn(product);
              when(rs.getObject("pharmacy_id")).thenReturn(pharmacy);
              when(rs.getString("batch_number")).thenReturn("BN");
              when(rs.getDate("expiry_date")).thenReturn(Date.valueOf("2027-01-01"));
              when(rs.getDate("manufactured_date")).thenReturn(null);
              when(rs.getInt("quantity_received")).thenReturn(1);
              when(rs.getInt("quantity_current")).thenReturn(1);
              when(rs.getLong("purchase_price_paise")).thenReturn(1L);
              when(rs.getLong("mrp_paise")).thenReturn(1L);
              when(rs.getBoolean("is_active")).thenReturn(true);
              when(rs.getString("write_off_reason")).thenReturn(null);
              when(rs.getString("write_off_notes")).thenReturn(null);
              when(rs.getObject("grn_item_id")).thenReturn(null);
              when(rs.getTimestamp("created_at")).thenReturn(null);
              when(rs.getTimestamp("updated_at")).thenReturn(null);
              return List.of(mapper.mapRow(rs, 0));
            });
    ProductBatch sparse = store.listByProduct(pharmacy, product, true).get(0);
    assertThat(sparse.manufacturedDate()).isNull();
    assertThat(sparse.createdAt()).isNull();

    ProductBatch noMfg =
        new ProductBatch(
            batch,
            product,
            pharmacy,
            "BN2",
            LocalDate.of(2027, 1, 1),
            null,
            1,
            1,
            1L,
            1L,
            true,
            null,
            null,
            null,
            now,
            now);
    store.insert(noMfg);

    store.topUpFromGrn(batch, 20, 20, 150L, 250L, UUID.randomUUID(), now);
  }

  private ResultSet mockBatchRs() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(batch);
    when(rs.getObject("product_id")).thenReturn(product);
    when(rs.getObject("pharmacy_id")).thenReturn(pharmacy);
    when(rs.getString("batch_number")).thenReturn("BN");
    when(rs.getDate("expiry_date")).thenReturn(Date.valueOf("2027-01-01"));
    when(rs.getDate("manufactured_date")).thenReturn(Date.valueOf("2025-01-01"));
    when(rs.getInt("quantity_received")).thenReturn(10);
    when(rs.getInt("quantity_current")).thenReturn(10);
    when(rs.getLong("purchase_price_paise")).thenReturn(100L);
    when(rs.getLong("mrp_paise")).thenReturn(200L);
    when(rs.getBoolean("is_active")).thenReturn(true);
    when(rs.getString("write_off_reason")).thenReturn(null);
    when(rs.getString("write_off_notes")).thenReturn(null);
    when(rs.getObject("grn_item_id")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
    return rs;
  }

  @Test
  void tryDeductQuantityReturnsEmptyWhenNoRowUpdated() {
    when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(0);
    assertThat(store.tryDeductQuantity(batch, 1, now)).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void tryDeductQuantityReturnsBatchWhenUpdated() throws Exception {
    when(jdbc.update(anyString(), any(), any(), any(), any(), any())).thenReturn(1);
    when(jdbc.query(anyString(), any(RowMapper.class), eq(batch)))
        .thenAnswer(
            inv -> {
              RowMapper<ProductBatch> mapper = inv.getArgument(1);
              ResultSet rs = mockBatchRs();
              when(rs.getInt("quantity_current")).thenReturn(9);
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.tryDeductQuantity(batch, 1, now)).isPresent();
  }
}
