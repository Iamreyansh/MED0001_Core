package com.nammamedmate.inventory.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.inventory.application.port.out.PharmacyProductStore.DetailsPatch;
import com.nammamedmate.inventory.application.port.out.PharmacyProductStore.ListFilter;
import com.nammamedmate.inventory.application.port.out.PharmacyProductStore.ListResult;
import com.nammamedmate.inventory.application.port.out.PharmacyProductStore.SettingsPatch;
import com.nammamedmate.inventory.application.port.out.PharmacyProductStore.SummaryRow;
import com.nammamedmate.inventory.domain.PharmacyProduct;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Connection;
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
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JdbcPharmacyProductStoreTest {

  @Mock private JdbcTemplate jdbc;
  private JdbcPharmacyProductStore store;
  private final Instant now = Instant.parse("2026-08-09T00:00:00Z");
  private final UUID pharmacy = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
  private final UUID product = UUID.fromString("11111111-2222-4333-8444-555555555555");

  @BeforeEach
  void setUp() {
    store = new JdbcPharmacyProductStore(jdbc);
  }

  @Test
  void list_summary_find_update_andMapRow() throws Exception {
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
        .thenReturn(null)
        .thenReturn(2L);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockProductRs(), 0));
            });
    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<?> ex = inv.getArgument(1);
              ResultSet rs = mock(ResultSet.class);
              when(rs.next()).thenReturn(true);
              when(rs.getLong(anyString())).thenReturn(1L);
              return ex.extractData(rs);
            });

    ListResult listed =
        store.list(
            new ListFilter(pharmacy, "LOW_STOCK", "para", "stock", "desc", 1, 20, null), now);
    assertThat(listed.total()).isEqualTo(0);
    assertThat(listed.rows()).hasSize(1);

    store.list(new ListFilter(pharmacy, "EXPIRING", null, "expiry", "asc", 1, 10, null), now);
    store.list(new ListFilter(pharmacy, "ALERTS", null, "value", "desc", 1, 10, null), now);
    store.list(new ListFilter(pharmacy, "RX_ONLY", null, "name", "asc", 1, 10, null), now);
    store.list(new ListFilter(pharmacy, "OUT_OF_STOCK", null, "name", "asc", 1, 10, null), now);
    store.list(new ListFilter(pharmacy, "UNALLOCATED", null, "name", "asc", 1, 10, null), now);
    store.list(new ListFilter(pharmacy, "ALL", "x", "name", "asc", 1, 10, UUID.randomUUID()), now);

    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              return List.of(mapper.mapRow(mockProductRs(), 0));
            });
    assertThat(store.findById(pharmacy, product)).isPresent();
    assertThat(
            store.listAllForExport(
                new ListFilter(pharmacy, "ALL", null, "name", "asc", 1, 20, null), now))
        .hasSize(1);

    SummaryRow summary = store.summary(pharmacy, now);
    assertThat(summary.totalSkus()).isEqualTo(1);

    when(jdbc.execute(any(ConnectionCallback.class)))
        .thenAnswer(
            inv -> {
              ConnectionCallback<?> cb = inv.getArgument(0);
              Connection conn = mock(Connection.class);
              Array arr = mock(Array.class);
              when(conn.createArrayOf(eq("text"), any(Object[].class))).thenReturn(arr);
              return cb.doInConnection(conn);
            });
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

    assertThat(
            store.updateSettings(pharmacy, product, new SettingsPatch(true, true, 10, "A1"), now))
        .isPresent();
    assertThat(
            store.updateSettings(pharmacy, product, new SettingsPatch(null, null, null, ""), now))
        .isPresent();
    // empty rack list path via details
    assertThat(
            store.updateDetails(
                pharmacy,
                product,
                new DetailsPatch(
                    null, null, null, null, null, null, null, null, null, null, List.of(), null),
                now))
        .isPresent();
    assertThat(
            store.updateDetails(
                pharmacy,
                product,
                new DetailsPatch(
                    "n",
                    "s",
                    "m",
                    10,
                    "t",
                    UUID.randomUUID(),
                    "TABLET",
                    "OTC",
                    "30049099",
                    BigDecimal.valueOf(12),
                    List.of("A1"),
                    "http://x"),
                now))
        .isPresent();

    assertThat(store.findByNameAndManufacturer(pharmacy, "Para", "Mfg")).isPresent();
    assertThat(store.findByNameAndManufacturer(pharmacy, "Para", null)).isPresent();
    assertThat(store.searchByName(pharmacy, "Para", 10)).hasSize(1);
    assertThat(store.searchByName(pharmacy, "  ", 10)).isEmpty();
    assertThat(store.searchByName(pharmacy, null, 10)).isEmpty();
    store.updateMrp(pharmacy, product, 999L, now);
    store.insert(
        new PharmacyProduct(
            product,
            pharmacy,
            null,
            "n",
            null,
            "m",
            1,
            "u",
            null,
            null,
            "TABLET",
            "OTC",
            null,
            BigDecimal.TEN,
            100,
            false,
            false,
            false,
            0,
            List.of(),
            0,
            0,
            null,
            0,
            null,
            null,
            now,
            now));
    store.insert(
        new PharmacyProduct(
            UUID.randomUUID(),
            pharmacy,
            null,
            "n2",
            null,
            "m",
            1,
            "u",
            null,
            null,
            "TABLET",
            "OTC",
            null,
            null,
            100,
            false,
            false,
            false,
            0,
            List.of(),
            0,
            0,
            LocalDate.of(2027, 1, 1),
            0,
            now,
            null,
            now,
            now));

    store.list(new ListFilter(pharmacy, "LOW_STOCK", "", "name", "asc", 1, 10, null), now);
    store.list(new ListFilter(pharmacy, null, null, null, null, 1, 10, null), now);

    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
    assertThat(
            store.updateSettings(pharmacy, product, new SettingsPatch(null, null, null, null), now))
        .isEmpty();
    assertThat(
            store.updateDetails(
                pharmacy,
                product,
                new DetailsPatch(
                    null, null, null, null, null, null, null, null, null, null, null, null),
                now))
        .isEmpty();
  }

  @Test
  void mapRow_andReadTextArray_branches() throws Exception {
    ResultSet rs = mockProductRs();
    PharmacyProduct row = JdbcPharmacyProductStore.mapRow(rs);
    assertThat(row.name()).contains("Para");
    assertThat(row.totalStockPacks()).isEqualTo(30);
    assertThat(row.mrpValuePaise()).isEqualTo(2250L * 450);

    ResultSet nullish = mockProductRs();
    when(nullish.getBigDecimal("gst_pct")).thenReturn(null);
    when(nullish.getInt("gst_pct")).thenReturn(12);
    when(nullish.getDate("earliest_expiry")).thenReturn(null);
    when(nullish.getTimestamp("last_movement_at")).thenReturn(null);
    when(nullish.getTimestamp("created_at")).thenReturn(null);
    when(nullish.getTimestamp("updated_at")).thenReturn(null);
    PharmacyProduct sparse = JdbcPharmacyProductStore.mapRow(nullish);
    assertThat(sparse.earliestExpiry()).isNull();
    assertThat(sparse.lastMovementAt()).isNull();

    assertThat(JdbcPharmacyProductStore.readTextArray(null)).isEmpty();
    Array empty = mock(Array.class);
    when(empty.getArray()).thenReturn(new String[] {});
    assertThat(JdbcPharmacyProductStore.readTextArray(empty)).isEmpty();

    Array objs = mock(Array.class);
    when(objs.getArray()).thenReturn(new Object[] {"A1", 2, null});
    assertThat(JdbcPharmacyProductStore.readTextArray(objs)).containsExactly("A1", "2");

    Array bad = mock(Array.class);
    when(bad.getArray()).thenReturn(42);
    assertThat(JdbcPharmacyProductStore.readTextArray(bad)).isEmpty();
  }

  private ResultSet mockProductRs() throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(product);
    when(rs.getObject("pharmacy_id")).thenReturn(pharmacy);
    when(rs.getObject("master_medicine_id")).thenReturn(null);
    when(rs.getString("name")).thenReturn("Paracetamol 500mg Tab");
    when(rs.getString("salt_composition")).thenReturn("Paracetamol 500mg");
    when(rs.getString("manufacturer")).thenReturn("Cipla Ltd");
    when(rs.getInt("pack_size")).thenReturn(15);
    when(rs.getString("pack_unit")).thenReturn("tablets");
    when(rs.getObject("category_id")).thenReturn(null);
    when(rs.getString("category_name")).thenReturn(null);
    when(rs.getString("form")).thenReturn("TABLET");
    when(rs.getString("schedule")).thenReturn("OTC");
    when(rs.getString("hsn_code")).thenReturn("30049099");
    when(rs.getBigDecimal("gst_pct")).thenReturn(BigDecimal.valueOf(12));
    when(rs.getLong("mrp_paise")).thenReturn(2250L);
    when(rs.getBoolean("is_rx_only")).thenReturn(false);
    when(rs.getBoolean("is_loose_selling_enabled")).thenReturn(false);
    when(rs.getBoolean("is_online_visible")).thenReturn(true);
    when(rs.getInt("reorder_level")).thenReturn(60);
    Array racks = mock(Array.class);
    when(racks.getArray()).thenReturn(new String[] {"A1-03"});
    when(rs.getArray("rack_locations")).thenReturn(racks);
    when(rs.getInt("total_stock_units")).thenReturn(450);
    when(rs.getInt("total_batches")).thenReturn(3);
    when(rs.getDate("earliest_expiry")).thenReturn(Date.valueOf(LocalDate.of(2026, 10, 31)));
    when(rs.getLong("cost_value_paise")).thenReturn(675000L);
    when(rs.getTimestamp("last_movement_at")).thenReturn(Timestamp.from(now));
    when(rs.getString("product_photo_url")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
    return rs;
  }
}
