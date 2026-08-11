package com.nammamedmate.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.analytics.application.port.out.AnalyticsExportPort;
import com.nammamedmate.analytics.application.port.out.AnalyticsPlanPort;
import com.nammamedmate.analytics.application.port.out.PharmacyAnalyticsStore;
import com.nammamedmate.analytics.application.port.out.PharmacyAnalyticsStore.AccountsData;
import com.nammamedmate.analytics.application.port.out.PharmacyAnalyticsStore.ChannelTotals;
import com.nammamedmate.analytics.application.port.out.PharmacyAnalyticsStore.DayBookRow;
import com.nammamedmate.analytics.application.port.out.PharmacyAnalyticsStore.FinancialTotals;
import com.nammamedmate.analytics.application.port.out.PharmacyAnalyticsStore.GstSlab;
import com.nammamedmate.analytics.application.port.out.PharmacyAnalyticsStore.ProductRow;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PharmacyAnalyticsServiceAcTest {

  private static final Instant NOW = Instant.parse("2026-07-24T16:00:00Z");
  private static final UUID PHARMACY = UUID.randomUUID();

  @Mock PharmacyAnalyticsStore store;
  @Mock AnalyticsPlanPort planPort;
  @Mock AnalyticsExportPort exportPort;

  PharmacyAnalyticsService service;
  MedmatePrincipal owner;
  MedmatePrincipal staff;

  @BeforeEach
  void setUp() {
    service =
        new PharmacyAnalyticsService(store, planPort, exportPort, Clock.fixed(NOW, ZoneOffset.UTC));
    owner =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_OWNER, PHARMACY, TokenScope.FULL, "j");
    staff =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_STAFF, PHARMACY, TokenScope.FULL, "j");
  }

  @Test
  void ac001_freePlanOverviewReturnsPlanUpgradeRequired() {
    when(planPort.allowsPharmacyAnalytics(PHARMACY)).thenReturn(false);
    assertThatThrownBy(() -> service.overview(owner, null, "30D", null, null))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PLAN_UPGRADE_REQUIRED");
  }

  @Test
  @SuppressWarnings("unchecked")
  void ac002_accountsGstSlabBreakdownSumsToOutput() {
    when(planPort.allowsPharmacyAnalytics(PHARMACY)).thenReturn(true);
    List<GstSlab> slabs =
        List.of(
            new GstSlab(5, 840000, 42000, 18000, 24000),
            new GstSlab(12, 620000, 74400, 24000, 50400),
            new GstSlab(18, 520000, 93600, 24000, 69600));
    long output = 42000 + 74400 + 93600;
    when(store.accounts(eq(PHARMACY), any(), any()))
        .thenReturn(
            new AccountsData(
                2840000, 2158000, 682000, 124000, output, 66000, 520000, 2320000, 2080000, 66000,
                false, slabs, List.of()));

    Map<String, Object> data = service.accountsGst(owner, null, "30D", null, null);
    Map<String, Object> gst = (Map<String, Object>) data.get("gst_liability");
    List<Map<String, Object>> breakdown = (List<Map<String, Object>>) gst.get("slab_breakdown");
    assertThat(breakdown).hasSize(3);
    long sum =
        breakdown.stream().mapToLong(r -> ((Number) r.get("output_gst_paise")).longValue()).sum();
    assertThat(sum).isEqualTo(((Number) gst.get("output_gst_paise")).longValue());
  }

  @Test
  @SuppressWarnings("unchecked")
  void ac003_productsDeadStockOnly() {
    when(planPort.allowsPharmacyAnalytics(PHARMACY)).thenReturn(true);
    when(store.countProducts(eq(PHARMACY), any(), any(), eq(true))).thenReturn(1L);
    when(store.products(eq(PHARMACY), any(), any(), any(), any(), eq(true), anyInt(), anyInt()))
        .thenReturn(
            List.of(
                new ProductRow(
                    UUID.randomUUID(), "Vit C", "OTC", 0, 0, 0L, 0L, null, 240, true, false)));

    var page = service.products(owner, null, "30D", null, null, null, null, true, 1, 20);
    List<Map<String, Object>> products = (List<Map<String, Object>>) page.data().get("products");
    assertThat(products).isNotEmpty();
    assertThat(products).allMatch(p -> Boolean.TRUE.equals(p.get("dead_stock_flag")));
  }

  @Test
  void ac004_productsDefaultSortRevenueThenMargin() {
    when(planPort.allowsPharmacyAnalytics(PHARMACY)).thenReturn(true);
    when(store.countProducts(any(), any(), any(), anyBoolean())).thenReturn(0L);
    when(store.products(
            any(), any(), any(), eq("revenue"), eq("desc"), eq(false), anyInt(), anyInt()))
        .thenReturn(List.of());
    service.products(owner, null, "30D", null, null, null, null, null, null, null);
    verify(store)
        .products(eq(PHARMACY), any(), any(), eq("revenue"), eq("desc"), eq(false), eq(0), eq(20));

    when(store.products(
            any(), any(), any(), eq("margin_pct"), eq("desc"), eq(false), anyInt(), anyInt()))
        .thenReturn(List.of());
    service.products(owner, null, "30D", null, null, "margin_pct", "desc", false, 1, 20);
    verify(store)
        .products(
            eq(PHARMACY), any(), any(), eq("margin_pct"), eq("desc"), eq(false), eq(0), eq(20));
  }

  @Test
  void ac005_exportExcelOver500RowsReturnsExportUrl() {
    when(planPort.allowsPharmacyAnalytics(PHARMACY)).thenReturn(true);
    List<List<Object>> rows = new ArrayList<>();
    for (int i = 0; i < 501; i++) {
      rows.add(List.of("INV-" + i, "", 100, 5, 5, 0, 110));
    }
    when(store.reportRows(eq(PHARMACY), eq("GSTR-1-DRAFT"), any(), any())).thenReturn(rows);
    when(exportPort.signedGet(any(), any()))
        .thenReturn(
            new AnalyticsExportPort.SignedUrl("file://export.xlsx", Instant.now().plusSeconds(60)));

    Map<String, Object> data =
        service.runReport(owner, null, "GSTR-1-DRAFT", "30D", null, null, "excel");
    assertThat(data.get("export_url")).isEqualTo("file://export.xlsx");
    assertThat((List<?>) data.get("rows")).isEmpty();
    verify(exportPort).put(any(), any(), any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void ac006_catalogueMarksFavorites() {
    when(planPort.allowsPharmacyAnalytics(PHARMACY)).thenReturn(true);
    when(store.favoriteReportIds(PHARMACY)).thenReturn(Set.of("GSTR-1-DRAFT", "PL-STATEMENT"));

    Map<String, Object> data = service.reportsCatalogue(owner, null);
    List<Map<String, Object>> reports = (List<Map<String, Object>>) data.get("reports");
    assertThat(reports).hasSize(9);
    Map<String, Object> gstr1 =
        reports.stream()
            .filter(r -> "GSTR-1-DRAFT".equals(r.get("report_id")))
            .findFirst()
            .orElseThrow();
    assertThat(gstr1.get("is_favorite")).isEqualTo(true);
  }

  @Test
  @SuppressWarnings("unchecked")
  void ac007_dayBookChronologicalRunningBalance() {
    when(planPort.allowsPharmacyAnalytics(PHARMACY)).thenReturn(true);
    when(store.accounts(eq(PHARMACY), any(), any()))
        .thenReturn(
            new AccountsData(
                100,
                0,
                100,
                0,
                0,
                0,
                100,
                0,
                0,
                0,
                false,
                List.of(
                    new GstSlab(5, 0, 0, 0, 0),
                    new GstSlab(12, 0, 0, 0, 0),
                    new GstSlab(18, 0, 0, 0, 0)),
                List.of(
                    new DayBookRow(LocalDate.of(2026, 7, 24), "SALE", "INV-1", "sale", 0, 50400),
                    new DayBookRow(
                        LocalDate.of(2026, 7, 24), "PURCHASE", "PUR-1", "buy", 84000, 0))));

    Map<String, Object> data = service.accountsGst(owner, null, "30D", null, null);
    List<Map<String, Object>> dayBook = (List<Map<String, Object>>) data.get("day_book");
    assertThat(dayBook).hasSize(2);
    assertThat(((Number) dayBook.get(0).get("balance_paise")).longValue()).isEqualTo(50400L);
    assertThat(((Number) dayBook.get(1).get("balance_paise")).longValue()).isEqualTo(-33600L);
  }

  @Test
  void ac008_staffCanReadButCannotToggleFavorites() {
    when(planPort.allowsPharmacyAnalytics(PHARMACY)).thenReturn(true);
    when(store.financials(any(), any(), any()))
        .thenReturn(new FinancialTotals(0, 0, 0, 0, 0, false));
    when(store.topItems(any(), any(), any(), anyInt())).thenReturn(List.of());
    when(store.channelTotals(any(), any(), any())).thenReturn(new ChannelTotals(0, 0));
    when(store.paymentMix(any(), any(), any())).thenReturn(List.of());

    assertThat(service.overview(staff, null, "30D", null, null)).containsKey("financials");

    assertThatThrownBy(() -> service.setFavorite(staff, null, "GSTR-1-DRAFT", true))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void ac009_fyPeriodStartsApril1OfCurrentFy() {
    when(planPort.allowsPharmacyAnalytics(PHARMACY)).thenReturn(true);
    when(store.financials(any(), any(), any()))
        .thenReturn(new FinancialTotals(0, 0, 0, 0, 0, false));
    when(store.topItems(any(), any(), any(), anyInt())).thenReturn(List.of());
    when(store.channelTotals(any(), any(), any())).thenReturn(new ChannelTotals(0, 0));
    when(store.paymentMix(any(), any(), any())).thenReturn(List.of());

    Map<String, Object> data = service.overview(owner, null, "FY", null, null);
    assertThat(data.get("period")).isEqualTo("FY");
    assertThat(data.get("date_from")).isEqualTo("2026-04-01");
    assertThat(data.get("date_to")).isEqualTo("2026-07-24");
  }
}
