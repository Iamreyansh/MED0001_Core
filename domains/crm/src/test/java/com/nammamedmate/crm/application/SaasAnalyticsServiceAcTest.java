package com.nammamedmate.crm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.crm.application.port.out.SaasAnalyticsStore;
import com.nammamedmate.crm.application.port.out.SaasAnalyticsStore.CohortRetentionRow;
import com.nammamedmate.crm.application.port.out.SaasAnalyticsStore.PlanMrrRow;
import com.nammamedmate.crm.application.port.out.SaasInvoicePdfPort;
import com.nammamedmate.crm.domain.AnalyticsMath;
import com.nammamedmate.crm.domain.CrmMoney;
import com.nammamedmate.crm.domain.SaasMetricsSnapshot;
import com.nammamedmate.kernel.error.AppException;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SaasAnalyticsServiceAcTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final LocalDate JULY = LocalDate.of(2026, 7, 1);

  @Mock SaasAnalyticsStore store;
  @Mock SaasInvoicePdfPort reports;
  @Mock RateLimiter rateLimiter;

  SaasAnalyticsService service;
  MedmatePrincipal finance;
  MedmatePrincipal ops;
  MedmatePrincipal superAdmin;
  SaasMetricsSnapshot julySnap;

  @BeforeEach
  void setUp() {
    service =
        new SaasAnalyticsService(store, reports, rateLimiter, Clock.fixed(NOW, ZoneOffset.UTC));
    finance = new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "f");
    ops = new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "o");
    superAdmin =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "s");
    julySnap =
        new SaasMetricsSnapshot(
            JULY,
            61248000L,
            734976000L,
            102400L,
            new BigDecimal("112.40"),
            new BigDecimal("94.80"),
            new BigDecimal("3.20"),
            new BigDecimal("1.80"),
            3276800L,
            585000L,
            new BigDecimal("1.43"),
            57520000L,
            3864000L,
            1120000L,
            380000L,
            876000L,
            3728000L,
            55,
            8,
            22,
            6,
            Instant.parse("2026-07-01T00:00:00Z"));
    lenient().when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(true);
    lenient().when(store.findMetrics(JULY)).thenReturn(Optional.of(julySnap));
    lenient().when(store.sumActiveMrrPaise(isNull())).thenReturn(61248000L);
    lenient()
        .when(store.mrrByPlan(isNull()))
        .thenReturn(List.of(new PlanMrrRow("STARTER", 29358000L, 420)));
    lenient().when(store.listMetrics(any(), any())).thenReturn(List.of(julySnap));
    lenient()
        .when(store.findMetrics(LocalDate.of(2026, 6, 1)))
        .thenReturn(
            Optional.of(
                new SaasMetricsSnapshot(
                    LocalDate.of(2026, 6, 1),
                    57520000L,
                    AnalyticsMath.arrPaise(57520000L),
                    100000L,
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
  }

  @Test
  @DisplayName("AC-001 MRR matches ACTIVE subscription sum")
  void ac001_mrr() {
    Map<String, Object> data = service.revenue(finance, "MONTH", null, null, null);
    @SuppressWarnings("unchecked")
    Map<String, Object> kpi = (Map<String, Object>) data.get("kpi_grid");
    assertThat(kpi.get("mrr_rs")).isEqualTo(CrmMoney.paiseToRupees(61248000L));
    assertThat(store.sumActiveMrrPaise(null)).isEqualTo(61248000L);
  }

  @Test
  @DisplayName("AC-002 ARR = MRR × 12")
  void ac002_arr() {
    Map<String, Object> data = service.revenue(finance, null, null, null, null);
    @SuppressWarnings("unchecked")
    Map<String, Object> kpi = (Map<String, Object>) data.get("kpi_grid");
    assertThat(kpi.get("arr_rs"))
        .isEqualTo(CrmMoney.paiseToRupees(AnalyticsMath.arrPaise(61248000L)));
  }

  @Test
  @DisplayName("AC-003 NRR formula when expansion > churn")
  void ac003_nrr() {
    BigDecimal expected = AnalyticsMath.nrrPct(57520000L, 1120000L, 876000L);
    assertThat(expected).isGreaterThan(BigDecimal.valueOf(100));
    Map<String, Object> data = service.revenue(superAdmin, "MONTH", null, null, null);
    @SuppressWarnings("unchecked")
    Map<String, Object> kpi = (Map<String, Object>) data.get("kpi_grid");
    assertThat(kpi.get("nrr_pct")).isEqualTo(julySnap.nrrPct());
  }

  @Test
  @DisplayName("AC-004 GRR ≤ NRR")
  void ac004_grr() {
    Map<String, Object> data = service.revenue(finance, "MONTH", null, null, null);
    @SuppressWarnings("unchecked")
    Map<String, Object> kpi = (Map<String, Object>) data.get("kpi_grid");
    BigDecimal nrr = (BigDecimal) kpi.get("nrr_pct");
    BigDecimal grr = (BigDecimal) kpi.get("grr_pct");
    assertThat(grr).isLessThanOrEqualTo(nrr);
  }

  @Test
  @DisplayName("AC-005 quick ratio > 1 when growth > churn")
  void ac005_quickRatio() {
    Map<String, Object> data = service.revenue(finance, "MONTH", null, null, null);
    @SuppressWarnings("unchecked")
    Map<String, Object> kpi = (Map<String, Object>) data.get("kpi_grid");
    assertThat((BigDecimal) kpi.get("quick_ratio")).isGreaterThan(BigDecimal.ONE);
  }

  @Test
  @DisplayName("AC-006 MRR bridge arithmetic integrity")
  void ac006_bridge() {
    Map<String, Object> bridge = service.mrrBridge(finance, "2026-07");
    long start = ((BigDecimal) bridge.get("start_mrr_rs")).movePointRight(2).longValueExact();
    long neu = ((BigDecimal) bridge.get("new_mrr_rs")).movePointRight(2).longValueExact();
    long exp = ((BigDecimal) bridge.get("expansion_mrr_rs")).movePointRight(2).longValueExact();
    long con = ((BigDecimal) bridge.get("contraction_mrr_rs")).movePointRight(2).longValueExact();
    long churn = ((BigDecimal) bridge.get("churn_mrr_rs")).movePointRight(2).longValueExact();
    long end = ((BigDecimal) bridge.get("end_mrr_rs")).movePointRight(2).longValueExact();
    assertThat(start + neu + exp - con - churn).isEqualTo(end);
  }

  @Test
  @DisplayName("AC-007 cohort month 0 is 100% and non-increasing")
  void ac007_cohort() {
    when(store.listCohortRetention(any(), any())).thenReturn(List.of());
    when(store.computeLiveCohortRetention(any(), any(), any()))
        .thenReturn(
            List.of(
                new CohortRetentionRow(
                    LocalDate.of(2026, 1, 1), 0, 48, 48, new BigDecimal("100.00")),
                new CohortRetentionRow(
                    LocalDate.of(2026, 1, 1), 1, 48, 46, new BigDecimal("95.83")),
                new CohortRetentionRow(
                    LocalDate.of(2026, 1, 1), 2, 48, 45, new BigDecimal("93.75"))));
    Map<String, Object> data = service.cohort(finance, "2026-01", "2026-01");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("cohort_retention");
    @SuppressWarnings("unchecked")
    List<BigDecimal> pcts = (List<BigDecimal>) rows.getFirst().get("retention_pcts");
    assertThat(pcts.getFirst()).isEqualByComparingTo("100.00");
    for (int i = 1; i < pcts.size(); i++) {
      if (pcts.get(i) != null) {
        assertThat(pcts.get(i)).isLessThanOrEqualTo(pcts.get(i - 1));
      }
    }
  }

  @Test
  @DisplayName("AC-008 LTV/CAC ratio arithmetic")
  void ac008_ltvCac() {
    Map<String, Object> data = service.unitEconomics(finance);
    BigDecimal ratio = (BigDecimal) data.get("ltv_cac_ratio");
    assertThat(ratio)
        .isEqualByComparingTo(AnalyticsMath.ltvCacRatio(julySnap.ltvPaise(), julySnap.cacPaise()));
  }

  @Test
  @DisplayName("AC-009 report signed URL expires in 1 hour")
  void ac009_report() {
    Instant expires = NOW.plus(Duration.ofHours(1));
    when(reports.signedGet(anyString(), eq(Duration.ofHours(1))))
        .thenReturn(new SaasInvoicePdfPort.SignedUrl("https://cdn.example/r.pdf", expires));
    Map<String, Object> data = service.report(finance, "MONTH", "2026-07", "PDF");
    assertThat(data.get("report_url")).isEqualTo("https://cdn.example/r.pdf");
    assertThat(data.get("expires_at")).isEqualTo(expires.toString());
    assertThat(data.get("format")).isEqualTo("PDF");
    verify(reports).put(anyString(), any());
  }

  @Test
  @DisplayName("AC-010 admin_operations gets 403 on analytics endpoints")
  void ac010_opsForbidden() {
    assertThatThrownBy(() -> service.revenue(ops, "MONTH", null, null, null))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).httpStatus())
        .isEqualTo(403);
    assertThatThrownBy(() -> service.mrrBridge(ops, null))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.cohort(ops, null, null)).isInstanceOf(AppException.class);
    assertThatThrownBy(() -> service.unitEconomics(ops)).isInstanceOf(AppException.class);
    assertThatThrownBy(() -> service.report(ops, "MONTH", null, "CSV"))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> service.revenue(null, null, null, null, null))
        .isInstanceOf(AppException.class);
  }

  @Test
  void computeAndCacheAndReportCsv() {
    when(store.findMetrics(JULY)).thenReturn(Optional.empty());
    when(store.countPayingAccounts(null)).thenReturn(600L);
    when(store.sumNewLogoMrrPaise(any(), any())).thenReturn(3864000L);
    when(store.countNewLogos(any(), any())).thenReturn(55);
    when(store.sumChurnMrrPaise(any(), any())).thenReturn(876000L);
    when(store.countChurnedLogos(any(), any())).thenReturn(8);
    when(store.sumExpansionMrrPaise(any(), any())).thenReturn(1120000L);
    when(store.countExpansionAccounts(any(), any())).thenReturn(22);
    when(store.sumContractionMrrPaise(any(), any())).thenReturn(380000L);
    when(store.countContractionAccounts(any(), any())).thenReturn(6);
    when(store.smSpendPaise(JULY)).thenReturn(32175000L);
    when(store.sumSmSpendPaise(any(), any())).thenReturn(150000000L);
    when(store.findMetrics(LocalDate.of(2026, 4, 1))).thenReturn(Optional.empty());

    SaasMetricsSnapshot computed = service.computeAndCache(JULY);
    assertThat(computed.mrrPaise()).isEqualTo(61248000L);
    assertThat(computed.arrPaise()).isEqualTo(AnalyticsMath.arrPaise(61248000L));
    assertThat(computed.grrPct()).isLessThanOrEqualTo(computed.nrrPct());
    ArgumentCaptor<SaasMetricsSnapshot> cap = ArgumentCaptor.forClass(SaasMetricsSnapshot.class);
    verify(store).upsertMetrics(cap.capture());
    assertThat(cap.getValue().startMrrPaise() + cap.getValue().netNewMrrPaise())
        .isEqualTo(cap.getValue().mrrPaise());

    when(store.findMetrics(JULY)).thenReturn(Optional.of(computed));
    when(reports.signedGet(anyString(), any()))
        .thenReturn(new SaasInvoicePdfPort.SignedUrl("file://x.csv", NOW.plusSeconds(3600)));
    assertThat(service.report(finance, "MONTH", "2026-07", "CSV")).containsKey("report_url");
  }

  @Test
  void batchAndValidationBranches() {
    when(store.computeLiveCohortRetention(any(), any(), any())).thenReturn(List.of());
    service.computeMonthlyBatch();
    verify(store).replaceCohortRetention(any());

    assertThatThrownBy(() -> service.revenue(finance, "WEEK", null, null, null))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> service.revenue(finance, "CUSTOM", null, null, null))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> service.revenue(finance, "CUSTOM", "", "2026-07", null))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> service.revenue(finance, "CUSTOM", "2026-01", " ", null))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> service.revenue(finance, "CUSTOM", "2026-08", "2026-07", null))
        .isInstanceOf(AppException.class);
    assertThat(service.revenue(finance, "CUSTOM", "2026-01", "2026-07", "STARTER"))
        .containsKey("kpi_grid");
    assertThat(service.revenue(finance, "QUARTER", null, null, null)).containsKey("period");
    assertThat(service.revenue(finance, "YEAR", null, null, null)).containsKey("period");
    assertThatThrownBy(() -> service.mrrBridge(finance, "2026/07"))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> service.cohort(finance, "2026-07", "2026-01"))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> service.report(finance, "WEEK", null, "PDF"))
        .isInstanceOf(AppException.class);
    assertThatThrownBy(() -> service.report(finance, "MONTH", null, "XLS"))
        .isInstanceOf(AppException.class);

    when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(false);
    when(rateLimiter.secondsUntilAvailable(anyString(), anyInt(), anyInt())).thenReturn(12);
    assertThatThrownBy(() -> service.unitEconomics(finance))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).httpStatus())
        .isEqualTo(429);
  }
}
