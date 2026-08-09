package com.nammamedmate.inventory.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.inventory.application.port.out.PurchaseGrnStore.ListFilter;
import com.nammamedmate.inventory.domain.GrnStatus;
import com.nammamedmate.inventory.domain.PurchaseGrn;
import com.nammamedmate.inventory.domain.PurchaseGrnItem;
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
class JdbcPurchaseGrnStoreTest {

  @Mock private JdbcTemplate jdbc;
  private JdbcPurchaseGrnStore store;
  private final Instant now = Instant.parse("2026-08-09T00:00:00Z");
  private final UUID pharmacy = UUID.randomUUID();
  private final UUID grnId = UUID.randomUUID();
  private final UUID dist = UUID.randomUUID();
  private final UUID itemId = UUID.randomUUID();
  private final UUID product = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    store = new JdbcPurchaseGrnStore(jdbc);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(2);
    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<?> ex = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.next()).thenReturn(true);
              when(rs.getLong(anyString())).thenReturn(1L);
              return ex.extractData(rs);
            });
  }

  @Test
  void invoiceCountZeroAndDeleteFalse() throws Exception {
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
    assertThat(store.invoiceExists(pharmacy, dist, "Z")).isFalse();
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
    assertThat(store.deleteItem(pharmacy, grnId, itemId)).isFalse();
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(inv -> List.of(mapBySql(inv.getArgument(0), inv.getArgument(1)).get(0)));
    assertThat(store.updateStatus(grnId, GrnStatus.DRAFT, null, null, now)).isNotNull();
  }

  @Test
  @SuppressWarnings("unchecked")
  void crudListKpi() throws Exception {
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(inv -> mapBySql(inv.getArgument(0), inv.getArgument(1)));

    PurchaseGrn grn =
        new PurchaseGrn(
            grnId,
            pharmacy,
            dist,
            "INV",
            LocalDate.of(2026, 7, 1),
            GrnStatus.DRAFT,
            null,
            null,
            UUID.randomUUID(),
            null,
            now,
            now,
            null);
    assertThat(store.insert(grn)).isSameAs(grn);
    PurchaseGrn stockedInsert =
        new PurchaseGrn(
            UUID.randomUUID(),
            pharmacy,
            dist,
            "INV2",
            LocalDate.of(2026, 7, 1),
            GrnStatus.STOCKED,
            now,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "[]",
            now,
            now,
            null);
    assertThat(store.insert(stockedInsert)).isSameAs(stockedInsert);
    assertThat(store.findById(pharmacy, grnId)).isPresent();
    assertThat(store.invoiceExists(pharmacy, dist, "INV")).isTrue();
    assertThat(
            store
                .list(
                    new ListFilter(
                        pharmacy,
                        GrnStatus.DRAFT,
                        dist,
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 12, 31),
                        "INV",
                        1,
                        20))
                .total())
        .isEqualTo(1);
    assertThat(store.kpi(pharmacy, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1)).totalGrns())
        .isEqualTo(1);
    assertThat(store.updateStatus(grnId, GrnStatus.STOCKED, now, UUID.randomUUID(), now))
        .isNotNull();
    assertThat(store.updateImportUnmatched(grnId, "[]", now)).isNotNull();

    PurchaseGrnItem item =
        new PurchaseGrnItem(
            itemId,
            grnId,
            pharmacy,
            product,
            "BN",
            LocalDate.of(2027, 1, 1),
            null,
            10,
            0,
            100,
            200,
            12,
            1000,
            120,
            1120,
            false,
            now,
            now);
    assertThat(store.insertItem(item)).isSameAs(item);
    PurchaseGrnItem withMfg =
        new PurchaseGrnItem(
            UUID.randomUUID(),
            grnId,
            pharmacy,
            product,
            "BN2",
            LocalDate.of(2027, 1, 1),
            LocalDate.of(2025, 1, 1),
            1,
            0,
            100,
            200,
            12,
            100,
            12,
            112,
            false,
            now,
            now);
    assertThat(store.insertItem(withMfg)).isSameAs(withMfg);
    assertThat(store.findItem(pharmacy, grnId, itemId)).isPresent();
    assertThat(store.updateItem(item)).isSameAs(item);
    assertThat(store.deleteItem(pharmacy, grnId, itemId)).isTrue();
    assertThat(store.listItems(pharmacy, grnId)).hasSize(1);
    assertThat(store.countItems(pharmacy, grnId)).isEqualTo(2);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(pharmacy), eq(dist)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getString(1)).thenReturn("Firm");
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.distributorFirmName(pharmacy, dist)).isEqualTo("Firm");

    when(jdbc.query(anyString(), any(RowMapper.class), eq(pharmacy), eq(dist)))
        .thenReturn(List.of());
    assertThat(store.distributorFirmName(pharmacy, dist)).isNull();

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    assertThat(store.invoiceExists(pharmacy, dist, "X")).isFalse();
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(null);
    assertThat(store.countItems(pharmacy, grnId)).isEqualTo(0);

    // list with no optional filters
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    assertThat(store.list(new ListFilter(pharmacy, null, null, null, null, null, 1, 10)).total())
        .isEqualTo(0);
    assertThat(store.list(new ListFilter(pharmacy, null, null, null, null, "  ", 1, 10)).total())
        .isEqualTo(0);
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
    assertThat(store.list(new ListFilter(pharmacy, null, null, null, null, "INV", 1, 10)).total())
        .isEqualTo(0);

    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(grnId);
              when(rs.getObject("pharmacy_id")).thenReturn(pharmacy);
              when(rs.getObject("distributor_id")).thenReturn(dist);
              when(rs.getString("invoice_number")).thenReturn("INV");
              when(rs.getDate("invoice_date")).thenReturn(Date.valueOf("2026-07-01"));
              when(rs.getString("status")).thenReturn("STOCKED");
              when(rs.getTimestamp("stocked_at")).thenReturn(Timestamp.from(now));
              when(rs.getObject("stocked_by")).thenReturn(UUID.randomUUID());
              when(rs.getObject("created_by")).thenReturn(UUID.randomUUID());
              when(rs.getString("import_unmatched")).thenReturn("[]");
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
              when(rs.getTimestamp("deleted_at")).thenReturn(Timestamp.from(now));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findById(pharmacy, grnId).orElseThrow().stockedAt()).isNotNull();

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              ResultSet rs = mockItemRs();
              when(rs.getDate("manufactured_date")).thenReturn(Date.valueOf("2025-01-01"));
              return List.of(mapper.mapRow(rs, 0));
            });
    assertThat(store.findItem(pharmacy, grnId, itemId).orElseThrow().manufacturedDate())
        .isNotNull();
  }

  private List<?> mapBySql(String sql, RowMapper<?> mapper) throws Exception {
    if (sql.contains("line_count")) {
      return List.of(mapper.mapRow(mockListRs(), 0));
    }
    if (sql.contains("product_name")) {
      return List.of(mapper.mapRow(mockItemWithNameRs(), 0));
    }
    if (sql.contains("purchase_grn_item") && !sql.contains("LEFT JOIN purchase_grn_item")) {
      return List.of(mapper.mapRow(mockItemRs(), 0));
    }
    return List.of(mapper.mapRow(mockGrnRs(), 0));
  }

  private ResultSet mockGrnRs() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(grnId);
    when(rs.getObject("pharmacy_id")).thenReturn(pharmacy);
    when(rs.getObject("distributor_id")).thenReturn(dist);
    when(rs.getString("invoice_number")).thenReturn("INV");
    when(rs.getDate("invoice_date")).thenReturn(Date.valueOf("2026-07-01"));
    when(rs.getString("status")).thenReturn("DRAFT");
    when(rs.getTimestamp("stocked_at")).thenReturn(null);
    when(rs.getObject("stocked_by")).thenReturn(null);
    when(rs.getObject("created_by")).thenReturn(UUID.randomUUID());
    when(rs.getString("import_unmatched")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("deleted_at")).thenReturn(null);
    return rs;
  }

  private ResultSet mockListRs() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(grnId);
    when(rs.getString("firm_name")).thenReturn("Firm");
    when(rs.getString("invoice_number")).thenReturn("INV");
    when(rs.getDate("invoice_date")).thenReturn(Date.valueOf("2026-07-01"));
    when(rs.getInt("line_count")).thenReturn(1);
    when(rs.getLong("taxable_amount_paise")).thenReturn(100L);
    when(rs.getLong("gst_amount_paise")).thenReturn(12L);
    when(rs.getLong("total_paise")).thenReturn(112L);
    when(rs.getString("status")).thenReturn("DRAFT");
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    return rs;
  }

  private ResultSet mockItemRs() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(itemId);
    when(rs.getObject("grn_id")).thenReturn(grnId);
    when(rs.getObject("pharmacy_id")).thenReturn(pharmacy);
    when(rs.getObject("product_id")).thenReturn(product);
    when(rs.getString("batch_number")).thenReturn("BN");
    when(rs.getDate("expiry_date")).thenReturn(Date.valueOf("2027-01-01"));
    when(rs.getDate("manufactured_date")).thenReturn(null);
    when(rs.getInt("quantity")).thenReturn(10);
    when(rs.getInt("free_quantity")).thenReturn(0);
    when(rs.getLong("purchase_price_paise")).thenReturn(100L);
    when(rs.getLong("mrp_paise")).thenReturn(200L);
    when(rs.getInt("gst_pct")).thenReturn(12);
    when(rs.getLong("taxable_amount_paise")).thenReturn(1000L);
    when(rs.getLong("gst_amount_paise")).thenReturn(120L);
    when(rs.getLong("line_total_paise")).thenReturn(1120L);
    when(rs.getBoolean("is_new_product")).thenReturn(false);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
    return rs;
  }

  private ResultSet mockItemWithNameRs() throws Exception {
    ResultSet rs = mockItemRs();
    when(rs.getString("product_name")).thenReturn("Para");
    return rs;
  }
}
