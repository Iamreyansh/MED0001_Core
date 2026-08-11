package com.nammamedmate.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.nammamedmate.analytics.application.port.out.AnalyticsExportPort;
import com.nammamedmate.analytics.application.port.out.AnalyticsPlanPort;
import com.nammamedmate.analytics.application.port.out.PharmacyAnalyticsStore;
import com.nammamedmate.analytics.application.port.out.PharmacyAnalyticsStore.AccountsData;
import com.nammamedmate.analytics.application.port.out.PharmacyAnalyticsStore.ChannelTotals;
import com.nammamedmate.analytics.application.port.out.PharmacyAnalyticsStore.FinancialTotals;
import com.nammamedmate.analytics.application.port.out.PharmacyAnalyticsStore.GstSlab;
import com.nammamedmate.analytics.application.port.out.PharmacyAnalyticsStore.PaymentMixRow;
import com.nammamedmate.analytics.application.port.out.PharmacyAnalyticsStore.ProductRow;
import com.nammamedmate.analytics.application.port.out.PharmacyAnalyticsStore.SaleTotals;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PharmacyAnalyticsBranchCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T16:00:00Z");
  private static final UUID PHARMACY = UUID.randomUUID();

  @Mock PharmacyAnalyticsStore store;
  @Mock AnalyticsPlanPort planPort;
  @Mock AnalyticsExportPort exportPort;

  PharmacyAnalyticsService service;
  MedmatePrincipal owner;
  MedmatePrincipal staff;
  MedmatePrincipal customer;
  MedmatePrincipal ownerNoPharmacy;
  MedmatePrincipal adminSuper;

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
    customer =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    ownerNoPharmacy =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_OWNER, null, TokenScope.FULL, "j");
    adminSuper =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
  }

  @Test
  void pageLimitValidationAndProductCogsMissingFlag() {
    when(planPort.allowsPharmacyAnalytics(PHARMACY)).thenReturn(true);

    assertThatThrownBy(
            () -> service.salesRegister(owner, null, "30D", null, null, null, null, 0, 20))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    when(store.countProducts(any(), any(), any(), anyBoolean())).thenReturn(1L);
    when(store.products(any(), any(), any(), any(), any(), anyBoolean(), anyInt(), anyInt()))
        .thenReturn(
            List.of(
                new ProductRow(
                    UUID.randomUUID(), "X", "OTC", 1, 100, 0L, 0L, null, 1, false, true)));
    var page = service.products(owner, null, "30D", null, null, "profit", "asc", false, 1, 200);
    assertThat(((List<?>) page.data().get("products")).getFirst())
        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
        .containsEntry("cogs_missing", true);

    assertThatThrownBy(
            () ->
                service.products(
                    owner, null, "30D", null, null, "revenue", "sideways", false, 1, 20))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () -> service.products(owner, null, "30D", null, null, null, null, null, 0, 20))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void favoriteAndAuthBranches() {
    when(planPort.allowsPharmacyAnalytics(PHARMACY)).thenReturn(true);

    assertThatThrownBy(() -> service.setFavorite(owner, null, "NOPE", true))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("REPORT_NOT_FOUND");
    assertThatThrownBy(() -> service.setFavorite(owner, null, "DAYBOOK", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.setFavorite(customer, PHARMACY, "DAYBOOK", true))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    MedmatePrincipal finance =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.setFavorite(finance, PHARMACY, "DAYBOOK", true))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    // admin can set favorite when pharmacy id provided
    service.setFavorite(adminSuper, PHARMACY, "DAYBOOK", false);

    assertThatThrownBy(() -> service.accountsGst(staff, null, "30D", null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    assertThatThrownBy(() -> service.overview(null, null, "30D", null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");

    assertThatThrownBy(() -> service.overview(ownerNoPharmacy, null, "30D", null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");

    assertThatThrownBy(() -> service.overview(customer, PHARMACY, "30D", null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void exportPdfInlineAndInvalidExportAndBlankReportId() {
    when(planPort.allowsPharmacyAnalytics(PHARMACY)).thenReturn(true);
    when(store.reportRows(any(), eq("DAYBOOK"), any(), any()))
        .thenReturn(List.of(List.of("2026-07-01", "SALE", "INV", 0, 10, 10)));

    Map<String, Object> pdf = service.runReport(owner, null, "DAYBOOK", "30D", null, null, "pdf");
    assertThat(pdf.get("export_url")).isNull();
    assertThat((List<?>) pdf.get("rows")).hasSize(1);

    assertThatThrownBy(() -> service.runReport(owner, null, "DAYBOOK", "30D", null, null, "csv"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(() -> service.runReport(owner, null, "  ", "30D", null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("REPORT_NOT_FOUND");

    // blank export string treated as inline
    assertThat(service.runReport(owner, null, "DAYBOOK", "30D", null, null, "  ").get("rows"))
        .asList()
        .isNotEmpty();
  }

  @Test
  void paymentMixNullMethodAndGstr1TotalsAndAccountsNoWarning() {
    when(planPort.allowsPharmacyAnalytics(PHARMACY)).thenReturn(true);
    when(store.financials(any(), any(), any()))
        .thenReturn(new FinancialTotals(10, 2, 8, 1, 1, false));
    when(store.topItems(any(), any(), any(), anyInt())).thenReturn(List.of());
    when(store.channelTotals(any(), any(), any())).thenReturn(new ChannelTotals(0, 0));
    when(store.paymentMix(any(), any(), any()))
        .thenReturn(List.of(new PaymentMixRow(null, 10), new PaymentMixRow("COD", 5)));

    Map<String, Object> overview = service.overview(owner, null, "30D", null, null);
    assertThat(overview.get("payment_mix")).asList().isNotEmpty();

    when(store.reportRows(any(), eq("GSTR-1-DRAFT"), any(), any()))
        .thenReturn(List.of(List.of("INV", "", 100, 5, 5, 0, 110), List.of("x")));
    Map<String, Object> gstr =
        service.runReport(owner, null, "GSTR-1-DRAFT", "30D", null, null, null);
    assertThat(((Map<?, ?>) gstr.get("totals")).get("taxable_value")).isEqualTo(100L);

    long output = 10;
    when(store.accounts(any(), any(), any()))
        .thenReturn(
            new AccountsData(
                10,
                0,
                10,
                0,
                output,
                0,
                10,
                0,
                0,
                0,
                false,
                List.of(
                    new GstSlab(5, 0, 10, 0, 10),
                    new GstSlab(12, 0, 0, 0, 0),
                    new GstSlab(18, 0, 0, 0, 0)),
                List.of()));
    assertThat(service.accountsGst(owner, null, "30D", null, null).containsKey("data_warning"))
        .isFalse();
  }

  @Test
  void exportPdfAsyncAndNullCellsAndNonNumberTotals() {
    when(planPort.allowsPharmacyAnalytics(PHARMACY)).thenReturn(true);
    List<List<Object>> rows = new ArrayList<>();
    for (int i = 0; i < 501; i++) {
      rows.add(java.util.Arrays.asList("INV", null, "x", 1, 1, 0, 2));
    }
    when(store.reportRows(eq(PHARMACY), eq("GSTR-1-DRAFT"), any(), any())).thenReturn(rows);
    when(exportPort.signedGet(any(), any()))
        .thenReturn(
            new AnalyticsExportPort.SignedUrl("file://x.pdf", Instant.now().plusSeconds(30)));

    Map<String, Object> data =
        service.runReport(owner, null, "GSTR-1-DRAFT", "30D", null, null, "pdf");
    assertThat(data.get("export_url")).isEqualTo("file://x.pdf");

    when(store.reportRows(eq(PHARMACY), eq("GSTR-1-DRAFT"), any(), any()))
        .thenReturn(List.of(List.of("INV", "", "not-a-number", 1, 1, 0, 2)));
    Map<String, Object> totals =
        (Map<String, Object>)
            service.runReport(owner, null, "GSTR-1-DRAFT", "30D", null, null, null).get("totals");
    assertThat(totals.get("taxable_value")).isEqualTo(0L);
  }

  @Test
  void remainingCompoundBranches() {
    when(planPort.allowsPharmacyAnalytics(PHARMACY)).thenReturn(true);

    assertThatThrownBy(
            () -> service.salesRegister(owner, null, "30D", null, null, null, null, 1, 0))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> service.products(owner, null, "30D", null, null, null, null, null, 1, 0))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    // data_warning: cogs incomplete alone
    when(store.accounts(any(), any(), any()))
        .thenReturn(
            new AccountsData(
                10,
                0,
                10,
                0,
                10,
                0,
                10,
                0,
                0,
                0,
                true,
                List.of(
                    new GstSlab(5, 0, 10, 0, 10),
                    new GstSlab(12, 0, 0, 0, 0),
                    new GstSlab(18, 0, 0, 0, 0)),
                List.of()));
    assertThat(service.accountsGst(owner, null, "30D", null, null).get("data_warning"))
        .isEqualTo(true);

    // mismatch slabs vs output without cogs incomplete
    when(store.accounts(any(), any(), any()))
        .thenReturn(
            new AccountsData(
                10,
                0,
                10,
                0,
                99,
                0,
                10,
                0,
                0,
                0,
                false,
                List.of(
                    new GstSlab(5, 0, 10, 0, 10),
                    new GstSlab(12, 0, 0, 0, 0),
                    new GstSlab(18, 0, 0, 0, 0)),
                List.of()));
    assertThat(service.accountsGst(owner, null, "30D", null, null).get("data_warning"))
        .isEqualTo(true);

    MedmatePrincipal ops =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    service.setFavorite(ops, PHARMACY, "GSTR-3B-DRAFT", true);

    when(store.financials(any(), any(), any()))
        .thenReturn(new FinancialTotals(10, 0, 10, 1, 1, false));
    when(store.topItems(any(), any(), any(), anyInt())).thenReturn(List.of());
    when(store.channelTotals(any(), any(), any())).thenReturn(new ChannelTotals(5, 5));
    when(store.paymentMix(any(), any(), any()))
        .thenReturn(
            List.of(
                new PaymentMixRow("CREDIT", 5),
                new PaymentMixRow("COD", 5),
                new PaymentMixRow("CARD", 5)));
    assertThat(service.overview(owner, null, "30D", null, null)).containsKey("payment_mix");
    assertThat(service.overview(owner, PHARMACY, "30D", null, null)).containsKey("period");

    assertThatThrownBy(() -> service.runReport(owner, null, null, "30D", null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("REPORT_NOT_FOUND");

    when(store.countProducts(any(), any(), any(), anyBoolean())).thenReturn(0L);
    when(store.products(any(), any(), any(), any(), any(), anyBoolean(), anyInt(), anyInt()))
        .thenReturn(List.of());
    service.products(owner, null, "30D", null, null, null, null, false, null, null);

    when(store.reportRows(any(), any(), any(), any())).thenReturn(List.of());
    for (var e : PharmacyAnalyticsService.CATALOGUE) {
      assertThat(service.runReport(owner, null, e.reportId(), "30D", null, null, null))
          .containsEntry("report_id", e.reportId());
    }
    when(store.saleTotals(any(), any(), any(), any(), any())).thenReturn(new SaleTotals(0, 0, 0));
    when(store.sales(any(), any(), any(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());
    service.salesRegister(owner, null, "30D", null, null, "  ", null, null, null);
    service.products(owner, null, "30D", null, null, "  ", "  ", false, 1, 20);
  }
}
