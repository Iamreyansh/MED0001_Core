package com.nammamedmate.analytics.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.analytics.application.port.out.AnalyticsExportPort;
import com.nammamedmate.analytics.application.port.out.AnalyticsPlanPort;
import com.nammamedmate.analytics.application.port.out.PharmacyAnalyticsStore;
import com.nammamedmate.analytics.application.port.out.PharmacyAnalyticsStore.AccountsData;
import com.nammamedmate.analytics.application.port.out.PharmacyAnalyticsStore.ChannelTotals;
import com.nammamedmate.analytics.application.port.out.PharmacyAnalyticsStore.FinancialTotals;
import com.nammamedmate.analytics.application.port.out.PharmacyAnalyticsStore.GstSlab;
import com.nammamedmate.analytics.application.port.out.PharmacyAnalyticsStore.PaymentMixRow;
import com.nammamedmate.analytics.application.port.out.PharmacyAnalyticsStore.SaleRow;
import com.nammamedmate.analytics.application.port.out.PharmacyAnalyticsStore.SaleTotals;
import com.nammamedmate.analytics.application.port.out.PharmacyAnalyticsStore.TopItem;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
class PharmacyAnalyticsCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T16:00:00Z");
  private static final UUID PHARMACY = UUID.randomUUID();

  @Mock PharmacyAnalyticsStore store;
  @Mock AnalyticsPlanPort planPort;
  @Mock AnalyticsExportPort exportPort;

  PharmacyAnalyticsService service;
  MedmatePrincipal owner;
  MedmatePrincipal admin;

  @BeforeEach
  void setUp() {
    service =
        new PharmacyAnalyticsService(store, planPort, exportPort, Clock.fixed(NOW, ZoneOffset.UTC));
    owner =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_OWNER, PHARMACY, TokenScope.FULL, "j");
    admin =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
  }

  @Test
  void overviewHappyPathAndScopeErrors() {
    when(planPort.allowsPharmacyAnalytics(PHARMACY)).thenReturn(true);
    when(store.financials(any(), any(), any()))
        .thenReturn(new FinancialTotals(100, 40, 60, 3, 10, true));
    when(store.topItems(any(), any(), any(), anyInt()))
        .thenReturn(List.of(new TopItem(UUID.randomUUID(), "Met", 1, 50)));
    when(store.channelTotals(any(), any(), any())).thenReturn(new ChannelTotals(60, 40));
    when(store.paymentMix(any(), any(), any()))
        .thenReturn(List.of(new PaymentMixRow("UPI", 60), new PaymentMixRow("CREDIT", 40)));

    Map<String, Object> data = service.overview(owner, null, "7D", null, null);
    assertThat(data).containsKeys("financials", "channel_mix", "payment_mix");

    assertThatThrownBy(() -> service.overview(owner, UUID.randomUUID(), "7D", null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    assertThatThrownBy(() -> service.overview(admin, null, "7D", null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThat(service.overview(admin, PHARMACY, "12M", null, null)).containsKey("period");
  }

  @Test
  void salesRegisterAndReportBranches() {
    when(planPort.allowsPharmacyAnalytics(PHARMACY)).thenReturn(true);
    when(store.saleTotals(any(), any(), any(), any(), any())).thenReturn(new SaleTotals(1, 100, 5));
    when(store.sales(any(), any(), any(), any(), any(), anyInt(), anyInt()))
        .thenReturn(
            List.of(
                new SaleRow(
                    UUID.randomUUID(),
                    "INV-1",
                    NOW,
                    "ONLINE",
                    "Ravi",
                    2,
                    90,
                    5,
                    95,
                    "UPI",
                    "DELIVERED")));

    assertThat(service.salesRegister(owner, null, "30D", null, null, "ONLINE", "UPI", 1, 20).data())
        .containsKey("sales");

    when(store.favoriteReportIds(PHARMACY)).thenReturn(Set.of());
    assertThat(service.reportsCatalogue(owner, null).get("reports")).asList().hasSize(9);

    when(store.reportRows(any(), eq("SALES-REGISTER"), any(), any()))
        .thenReturn(List.of(List.of("INV-1", NOW.toString(), "ONLINE", 95L, "UPI")));
    Map<String, Object> inline =
        service.runReport(owner, null, "SALES-REGISTER", "30D", null, null, null);
    assertThat(inline.get("export_url")).isNull();
    assertThat((List<?>) inline.get("rows")).hasSize(1);

    assertThatThrownBy(() -> service.runReport(owner, null, "NOPE", "30D", null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("REPORT_NOT_FOUND");

    service.setFavorite(owner, null, "DAYBOOK", true);
    verify(store).setFavorite(PHARMACY, "DAYBOOK", true);
  }

  @Test
  void accountsDataWarningAndInvalidInputs() {
    when(planPort.allowsPharmacyAnalytics(PHARMACY)).thenReturn(true);
    when(store.accounts(any(), any(), any()))
        .thenReturn(
            new AccountsData(
                10,
                1,
                9,
                0,
                100,
                0,
                10,
                0,
                0,
                0,
                true,
                List.of(
                    new GstSlab(5, 0, 50, 0, 50),
                    new GstSlab(12, 0, 0, 0, 0),
                    new GstSlab(18, 0, 0, 0, 0)),
                List.of()));
    Map<String, Object> data =
        service.accountsGst(owner, null, "CUSTOM", "2026-07-01", "2026-07-10");
    assertThat(data.get("data_warning")).isEqualTo(true);

    assertThatThrownBy(
            () -> service.products(owner, null, "30D", null, null, "bad", null, null, 0, 20))
        .isInstanceOf(AppException.class);
  }

  @Test
  void refreshServiceDelegates() {
    PharmacyAnalyticsRefreshService refresh =
        new PharmacyAnalyticsRefreshService(store, Clock.fixed(NOW, ZoneOffset.UTC));
    refresh.refreshYesterdayAndDeadStock();
    verify(store).refreshDailySnapshots(any(), any());
    verify(store).refreshDeadStockFlags(any());
    refresh.refreshRange(java.time.LocalDate.of(2026, 7, 1), java.time.LocalDate.of(2026, 7, 2));
    verify(store)
        .refreshDailySnapshots(
            java.time.LocalDate.of(2026, 7, 1), java.time.LocalDate.of(2026, 7, 2));
  }
}
