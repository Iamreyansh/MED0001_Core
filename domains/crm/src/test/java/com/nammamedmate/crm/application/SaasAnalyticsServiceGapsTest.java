package com.nammamedmate.crm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.nammamedmate.crm.application.port.out.SaasAnalyticsStore;
import com.nammamedmate.crm.application.port.out.SaasAnalyticsStore.CohortRetentionRow;
import com.nammamedmate.crm.application.port.out.SaasInvoicePdfPort;
import com.nammamedmate.crm.domain.SaasMetricsSnapshot;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SaasAnalyticsServiceGapsTest {

  private static final Instant NOW = Instant.parse("2026-07-15T12:00:00Z");
  private static final LocalDate JULY = LocalDate.of(2026, 7, 1);

  @Mock SaasAnalyticsStore store;
  @Mock SaasInvoicePdfPort reports;
  @Mock RateLimiter rateLimiter;

  SaasAnalyticsService service;
  MedmatePrincipal finance;

  @BeforeEach
  void setUp() {
    service =
        new SaasAnalyticsService(store, reports, rateLimiter, Clock.fixed(NOW, ZoneOffset.UTC));
    finance = new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "f");
    lenient().when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(true);
  }

  @Test
  void emptyTrendUsesLiveMrrAndCachedCohort() {
    SaasMetricsSnapshot snap =
        new SaasMetricsSnapshot(
            JULY,
            1000L,
            12000L,
            100L,
            new BigDecimal("101.00"),
            new BigDecimal("99.00"),
            new BigDecimal("2.00"),
            null,
            1000L,
            200L,
            new BigDecimal("2.00"),
            900L,
            50L,
            40L,
            10L,
            20L,
            60L,
            1,
            1,
            1,
            1,
            NOW);
    when(store.findMetrics(JULY)).thenReturn(Optional.of(snap));
    when(store.sumActiveMrrPaise(isNull())).thenReturn(1000L);
    when(store.findMetrics(LocalDate.of(2026, 6, 1))).thenReturn(Optional.empty());
    when(store.listMetrics(any(), any())).thenReturn(List.of());
    when(store.mrrByPlan(isNull())).thenReturn(List.of());
    when(store.listCohortRetention(any(), any()))
        .thenReturn(
            List.of(
                new CohortRetentionRow(
                    LocalDate.of(2026, 2, 1), 0, 10, 10, new BigDecimal("100.00")),
                new CohortRetentionRow(
                    LocalDate.of(2026, 2, 1), 1, 10, 9, new BigDecimal("90.00"))));

    Map<String, Object> revenue = service.revenue(finance, "MONTH", null, null, null);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> trend = (List<Map<String, Object>>) revenue.get("mrr_trend");
    assertThat(trend).hasSize(1);
    assertThat(service.cohort(finance, null, null).get("cohort_retention")).isNotNull();
    assertThat(service.mrrBridge(finance, null)).containsEntry("month", "2026-07");
    when(reports.signedGet(anyString(), any(Duration.class)))
        .thenReturn(new SaasInvoicePdfPort.SignedUrl("u", NOW.plusSeconds(3600)));
    assertThat(service.report(finance, null, null, null)).containsEntry("format", "PDF");
  }

  @Test
  void computeResidualContractionAndZeroSpendMagic() {
    when(store.findMetrics(JULY)).thenReturn(Optional.empty());
    when(store.findMetrics(LocalDate.of(2026, 4, 1))).thenReturn(Optional.empty());
    when(store.sumActiveMrrPaise(null)).thenReturn(1000L);
    when(store.countPayingAccounts(null)).thenReturn(10L);
    when(store.sumNewLogoMrrPaise(any(), any())).thenReturn(500L);
    when(store.countNewLogos(any(), any())).thenReturn(0);
    when(store.sumChurnMrrPaise(any(), any())).thenReturn(0L);
    when(store.countChurnedLogos(any(), any())).thenReturn(0);
    when(store.sumExpansionMrrPaise(any(), any())).thenReturn(0L);
    when(store.countExpansionAccounts(any(), any())).thenReturn(0);
    when(store.sumContractionMrrPaise(any(), any())).thenReturn(0L);
    when(store.countContractionAccounts(any(), any())).thenReturn(0);
    when(store.smSpendPaise(JULY)).thenReturn(0L);
    when(store.sumSmSpendPaise(any(), any())).thenReturn(0L);
    when(store.findMetrics(LocalDate.of(2026, 6, 1)))
        .thenReturn(
            Optional.of(
                new SaasMetricsSnapshot(
                    LocalDate.of(2026, 6, 1),
                    2000L,
                    24000L,
                    200L,
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    null,
                    0,
                    0,
                    BigDecimal.ONE,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    NOW)));

    SaasMetricsSnapshot snap = service.computeAndCache(JULY);
    assertThat(snap.startMrrPaise()).isEqualTo(2000L);
    assertThat(snap.contractionMrrPaise()).isGreaterThan(0L);
    assertThat(snap.magicNumber()).isNull();
    assertThat(snap.startMrrPaise() + snap.netNewMrrPaise()).isEqualTo(snap.mrrPaise());
  }

  @Test
  void computePositiveResidualGoesToExpansion() {
    when(store.findMetrics(JULY)).thenReturn(Optional.empty());
    when(store.findMetrics(LocalDate.of(2026, 6, 1)))
        .thenReturn(
            Optional.of(
                new SaasMetricsSnapshot(
                    LocalDate.of(2026, 6, 1),
                    1000L,
                    12000L,
                    100L,
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    null,
                    0,
                    0,
                    BigDecimal.ONE,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    NOW)));
    when(store.findMetrics(LocalDate.of(2026, 4, 1))).thenReturn(Optional.empty());
    when(store.sumActiveMrrPaise(null)).thenReturn(2500L);
    when(store.countPayingAccounts(null)).thenReturn(5L);
    when(store.sumNewLogoMrrPaise(any(), any())).thenReturn(0L);
    when(store.countNewLogos(any(), any())).thenReturn(0);
    when(store.sumChurnMrrPaise(any(), any())).thenReturn(0L);
    when(store.countChurnedLogos(any(), any())).thenReturn(0);
    when(store.sumExpansionMrrPaise(any(), any())).thenReturn(0L);
    when(store.countExpansionAccounts(any(), any())).thenReturn(0);
    when(store.sumContractionMrrPaise(any(), any())).thenReturn(0L);
    when(store.countContractionAccounts(any(), any())).thenReturn(0);
    when(store.smSpendPaise(JULY)).thenReturn(0L);
    when(store.sumSmSpendPaise(any(), any())).thenReturn(0L);

    SaasMetricsSnapshot snap = service.computeAndCache(JULY);
    assertThat(snap.expansionMrrPaise()).isEqualTo(1500L);
    assertThat(snap.startMrrPaise() + snap.netNewMrrPaise()).isEqualTo(snap.mrrPaise());
  }
}
