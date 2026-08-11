package com.nammamedmate.analytics.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
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
class JdbcPharmacyAnalyticsStoreTest {

  @Mock JdbcTemplate jdbc;
  @Mock ResultSet rs;

  private final UUID pharmacyId = UUID.randomUUID();
  private final Instant from = Instant.parse("2026-07-01T00:00:00Z");
  private final Instant to = Instant.parse("2026-07-25T00:00:00Z");

  @Test
  @SuppressWarnings("unchecked")
  void coversCoreQueriesFavoritesReportsAndRefresh() throws Exception {
    UUID productId = UUID.randomUUID();
    UUID saleId = UUID.randomUUID();
    when(rs.next()).thenReturn(true, false);
    when(rs.getLong(anyString())).thenReturn(100L);
    when(rs.getInt(anyString())).thenReturn(2);
    when(rs.getBoolean(anyString())).thenReturn(true);
    when(rs.getString(anyString())).thenReturn("UPI");
    when(rs.getString("name")).thenReturn("Metformin");
    when(rs.getString("channel")).thenReturn("ONLINE");
    when(rs.getString("invoice_number")).thenReturn("INV-1");
    when(rs.getString("customer_name")).thenReturn("Ravi");
    when(rs.getString("payment_method")).thenReturn("UPI");
    when(rs.getString("payment_status")).thenReturn("PENDING");
    when(rs.getString("category")).thenReturn("OTC");
    when(rs.getString("report_id")).thenReturn("GSTR-1-DRAFT");
    when(rs.getObject("product_id")).thenReturn(productId);
    when(rs.getObject("id")).thenReturn(saleId);
    when(rs.getObject("pharmacy_id")).thenReturn(pharmacyId);
    when(rs.getObject("cogs_paise")).thenReturn(40L);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(from));
    when(rs.getDate("d")).thenReturn(Date.valueOf(LocalDate.of(2026, 7, 24)));
    when(rs.getDate("invoice_date")).thenReturn(Date.valueOf(LocalDate.of(2026, 7, 24)));

    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<?> ex = inv.getArgument(1);
              when(rs.next()).thenReturn(true, false);
              return ex.extractData(rs);
            });
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

    JdbcPharmacyAnalyticsStore store = new JdbcPharmacyAnalyticsStore(jdbc);

    assertThat(store.financials(pharmacyId, from, to).netRevenuePaise()).isEqualTo(100L);

    // empty financials path
    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<?> ex = inv.getArgument(1);
              when(rs.next()).thenReturn(false);
              return ex.extractData(rs);
            });
    assertThat(store.financials(pharmacyId, from, to).netRevenuePaise()).isEqualTo(0L);

    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<?> ex = inv.getArgument(1);
              when(rs.next()).thenReturn(true, false);
              return ex.extractData(rs);
            });
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(10L);

    assertThat(store.topItems(pharmacyId, from, to, 5)).hasSize(1);
    assertThat(store.channelTotals(pharmacyId, from, to).onlineRevenuePaise()).isEqualTo(100L);
    assertThat(store.paymentMix(pharmacyId, from, to)).hasSize(1);
    assertThat(store.saleTotals(pharmacyId, from, to, "ONLINE", "UPI").totalSales())
        .isEqualTo(100L);
    assertThat(store.sales(pharmacyId, from, to, null, null, 0, 20).getFirst().status())
        .isEqualTo("PENDING");
    assertThat(store.countProducts(pharmacyId, from, to, false)).isEqualTo(10L);

    when(rs.getObject("cogs_paise")).thenReturn(50L);
    when(rs.getLong("missing_cogs")).thenReturn(0L);
    when(rs.getLong("revenue_paise")).thenReturn(100L);
    assertThat(store.products(pharmacyId, from, to, "units_sold", "desc", false, 0, 20)).hasSize(1);
    assertThat(store.products(pharmacyId, from, to, "profit", "asc", false, 0, 20)).hasSize(1);
    assertThat(store.products(pharmacyId, from, to, "revenue", "desc", false, 0, 20)).hasSize(1);

    when(rs.getObject("cogs_paise")).thenReturn(null);
    when(rs.getLong("missing_cogs")).thenReturn(1L);
    assertThat(store.products(pharmacyId, from, to, "margin_pct", "asc", true, 0, 20)).hasSize(1);

    assertThat(store.accounts(pharmacyId, from, to).slabs()).hasSize(3);
    assertThat(store.favoriteReportIds(pharmacyId)).contains("GSTR-1-DRAFT");
    store.setFavorite(pharmacyId, "DAYBOOK", true);
    store.setFavorite(pharmacyId, "DAYBOOK", false);
    assertThat(store.reportRows(pharmacyId, "GSTR-1-DRAFT", from, to)).hasSize(1);
    assertThat(store.reportRows(pharmacyId, "SALES-REGISTER", from, to)).hasSize(1);
    assertThat(store.reportRows(pharmacyId, "DEAD-STOCK", from, to)).hasSize(1);
    assertThat(store.reportRows(pharmacyId, "PURCHASE-REG", from, to)).hasSize(1);
    assertThat(store.reportRows(pharmacyId, "STOCK-SUMMARY", from, to)).hasSize(1);
    assertThat(store.reportRows(pharmacyId, "PL-STATEMENT", from, to)).isNotEmpty();
    assertThat(store.reportRows(pharmacyId, "GSTR-3B-DRAFT", from, to)).hasSize(3);
    assertThat(store.reportRows(pharmacyId, "DAYBOOK", from, to)).isNotEmpty();
    assertThat(store.reportRows(pharmacyId, "PARTY-LEDGER", from, to)).isEmpty();

    store.refreshDailySnapshots(LocalDate.of(2026, 7, 24), LocalDate.of(2026, 7, 24));
    store.refreshDeadStockFlags(LocalDate.of(2026, 7, 24));
    assertThat(store.reportRows(pharmacyId, "UNKNOWN", from, to)).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void coversNullCountPaidNullStatusAndEmptySlabs() throws Exception {
    when(rs.next()).thenReturn(true, false);
    when(rs.getLong(anyString())).thenReturn(0L);
    when(rs.getLong("missing_cogs")).thenReturn(3L);
    when(rs.getInt(anyString())).thenReturn(1);
    when(rs.getBoolean(anyString())).thenReturn(false);
    when(rs.getString(anyString())).thenReturn(null);
    when(rs.getString("channel")).thenReturn("COUNTER");
    when(rs.getString("category")).thenReturn("H");
    when(rs.getString("payment_status")).thenReturn(null);
    when(rs.getString("invoice_number")).thenReturn("INV");
    when(rs.getObject("id")).thenReturn(UUID.randomUUID());
    when(rs.getObject("product_id")).thenReturn(UUID.randomUUID());
    when(rs.getObject("pharmacy_id")).thenReturn(pharmacyId);
    when(rs.getObject("cogs_paise")).thenReturn(10L);
    when(rs.getLong("revenue_paise")).thenReturn(100L);
    when(rs.getLong("missing_cogs")).thenReturn(0L);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(from));
    when(rs.getDate("d"))
        .thenReturn(
            Date.valueOf(LocalDate.of(2026, 7, 23)), Date.valueOf(LocalDate.of(2026, 7, 24)));
    when(rs.getDate("invoice_date")).thenReturn(Date.valueOf(LocalDate.of(2026, 7, 24)));

    when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              ResultSetExtractor<?> ex = inv.getArgument(1);
              when(rs.next()).thenReturn(true, false);
              when(rs.getLong("missing_cogs")).thenReturn(3L);
              return ex.extractData(rs);
            })
        .thenAnswer(
            inv -> {
              // second financials / channel etc with complete COGS
              ResultSetExtractor<?> ex = inv.getArgument(1);
              when(rs.next()).thenReturn(true, false);
              when(rs.getLong("missing_cogs")).thenReturn(0L);
              when(rs.getLong(anyString())).thenReturn(50L);
              when(rs.getLong("missing_cogs")).thenReturn(0L);
              return ex.extractData(rs);
            });
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
        .thenReturn(null) // countProducts
        .thenReturn(25L); // gst input itc etc
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(
            inv -> {
              when(rs.getInt("slab")).thenReturn(5);
              when(rs.getLong("taxable")).thenReturn(100L);
              when(rs.getLong("output_gst")).thenReturn(10L);
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            })
        .thenAnswer(
            inv -> {
              RowMapper<?> mapper = inv.getArgument(1);
              when(rs.getString("channel")).thenReturn("ONLINE");
              when(rs.getString("customer_name")).thenReturn(null);
              when(rs.getDate("d")).thenReturn(Date.valueOf(LocalDate.of(2026, 7, 23)));
              Object a = mapper.mapRow(rs, 0);
              when(rs.getString("channel")).thenReturn("COUNTER");
              when(rs.getString("customer_name")).thenReturn("Ravi");
              when(rs.getDate("d")).thenReturn(Date.valueOf(LocalDate.of(2026, 7, 24)));
              Object b = mapper.mapRow(rs, 1);
              return List.of(a, b);
            });

    JdbcPharmacyAnalyticsStore store = new JdbcPharmacyAnalyticsStore(jdbc);
    assertThat(store.financials(pharmacyId, from, to).cogsIncomplete()).isTrue();
    assertThat(store.financials(pharmacyId, from, to).cogsIncomplete()).isFalse();
    assertThat(store.countProducts(pharmacyId, from, to, true)).isEqualTo(0L);
    // further queryForObject calls return 25 for ITC
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(25L);
    assertThat(store.accounts(pharmacyId, from, to).inputItcPaise()).isGreaterThan(0L);

    when(rs.getString("payment_status")).thenReturn("PAID");
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    assertThat(store.sales(pharmacyId, from, to, "ONLINE", "UPI", 0, 1).getFirst().status())
        .isEqualTo("DELIVERED");
    when(rs.getString("payment_status")).thenReturn(null);
    assertThat(store.sales(pharmacyId, from, to, null, null, 0, 1).getFirst().status())
        .isEqualTo("ACTIVE");
    when(rs.getString("payment_status")).thenReturn("PENDING");
    assertThat(store.sales(pharmacyId, from, to, null, null, 0, 1).getFirst().status())
        .isEqualTo("PENDING");

    when(rs.getObject("cogs_paise")).thenReturn(null);
    when(rs.getLong("missing_cogs")).thenReturn(0L);
    when(rs.getLong("revenue_paise")).thenReturn(0L);
    when(rs.getString("category")).thenReturn(null);
    assertThat(
            store
                .products(pharmacyId, from, to, "revenue", "desc", false, 0, 1)
                .getFirst()
                .category())
        .isEqualTo("OTC");
    when(rs.getString("category")).thenReturn("OTC");
    assertThat(
            store
                .products(pharmacyId, from, to, "revenue", "desc", false, 0, 1)
                .getFirst()
                .category())
        .isEqualTo("OTC");
    when(rs.getString("category")).thenReturn("H");
    assertThat(
            store
                .products(pharmacyId, from, to, "revenue", "desc", false, 0, 1)
                .getFirst()
                .category())
        .isEqualTo("PRESCRIPTION");
    when(rs.getObject("cogs_paise")).thenReturn(10L);
    when(rs.getLong("missing_cogs")).thenReturn(2L);
    when(rs.getLong("revenue_paise")).thenReturn(100L);
    assertThat(store.products(pharmacyId, from, to, "revenue", "desc", false, 0, 1)).hasSize(1);

    // force nullableLong(null) via cash/digital helpers in accounts with null queryForObject
    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
    assertThat(store.accounts(pharmacyId, from, to).cashCollectedPaise()).isEqualTo(0L);
  }
}
