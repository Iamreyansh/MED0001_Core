package com.nammamedmate.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.payment.adapter.out.persistence.LocalTaxFilingObjectStore;
import com.nammamedmate.payment.application.port.out.TaxPharmacyProfilePort;
import com.nammamedmate.payment.application.port.out.TaxPharmacyProfilePort.PharmacyTaxProfile;
import com.nammamedmate.payment.application.port.out.TaxStorePort;
import com.nammamedmate.payment.application.port.out.TaxStorePort.PharmacyCommissionRow;
import com.nammamedmate.payment.application.port.out.TaxStorePort.TaxFilingRecord;
import com.nammamedmate.payment.application.port.out.TaxStorePort.TcsMonthTotals;
import com.nammamedmate.payment.application.port.out.TaxStorePort.TcsPage;
import com.nammamedmate.payment.application.port.out.TaxStorePort.TcsRegisterRecord;
import com.nammamedmate.payment.domain.MoneyFormats;
import com.nammamedmate.payment.domain.TaxFilingStatuses;
import com.nammamedmate.payment.domain.TaxFilingTypes;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TaxFacadeServiceAcTest {

  private static final Instant NOW = Instant.parse("2026-08-09T10:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Mock private TaxStorePort store;
  @Mock private TaxPharmacyProfilePort pharmacies;

  @TempDir java.nio.file.Path temp;

  private TaxFacadeService service;
  private final UUID pharmacyId = UUID.randomUUID();
  private final UUID filingId = UUID.randomUUID();
  private final MedmatePrincipal finance =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
  private final MedmatePrincipal compliance =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");
  private final MedmatePrincipal support =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    service =
        new TaxFacadeService(
            store,
            pharmacies,
            new LocalTaxFilingObjectStore(temp, "file://" + temp),
            new ObjectMapper(),
            CLOCK);
  }

  @Test
  void ac001_tcsAmountIsOnePercentOfGmv() {
    when(store.tcsTotals("2026-07")).thenReturn(new TcsMonthTotals(285_000_000L, 2_850_000L, 28));
    when(store.totalCommissionPaise(any(), any())).thenReturn(22_800_000L);
    when(store.gatewayFeesPaise(any(), any())).thenReturn(1_520_000L);
    when(store.commissionByPharmacy(any(), any())).thenReturn(List.of());
    when(store.findFilingByTypeAndPeriod(eq(TaxFilingTypes.GSTR_8), eq("2026-07")))
        .thenReturn(Optional.empty());

    Map<String, Object> data = service.taxPanel(finance, "2026-07");
    @SuppressWarnings("unchecked")
    Map<String, Object> tcs = (Map<String, Object>) data.get("tcs_collected");
    BigDecimal gmv = (BigDecimal) tcs.get("total_gmv");
    BigDecimal tcsAmt = (BigDecimal) tcs.get("tcs_amount");
    assertThat(tcsAmt).isEqualByComparingTo(gmv.multiply(new BigDecimal("0.01")));
    assertThat(tcsAmt).isEqualByComparingTo(MoneyFormats.paiseToRupees(2_850_000L));
  }

  @Test
  void ac002_tcsRegisterUpdatedOnSettlementRelease() {
    when(pharmacies.find(pharmacyId))
        .thenReturn(
            Optional.of(
                new PharmacyTaxProfile(
                    pharmacyId, "Apollo Pharmacy, Koramangala", "29AAAAA0000A1Z5", "AAAAA0000A")));
    UUID settlementId = UUID.randomUUID();
    service.recordReleasedSettlement(settlementId, pharmacyId, "2026-07", 5_200_000L, 52_000L, NOW);
    verify(store)
        .upsertTcsOnRelease(
            eq(pharmacyId),
            eq("2026-07"),
            eq("Apollo Pharmacy, Koramangala"),
            eq("29AAAAA0000A1Z5"),
            eq("AAAAA0000A"),
            eq(settlementId),
            eq(5_200_000L),
            eq(52_000L),
            eq(NOW));
  }

  @Test
  void ac003_generateReturnsDownloadableGstr8Json() throws Exception {
    TaxFilingRecord filing = pendingGstr8(filingId, "2026-07", LocalDate.of(2026, 8, 10));
    when(store.findFiling(filingId)).thenReturn(Optional.of(filing));
    when(store.listTcsAll("2026-07"))
        .thenReturn(
            List.of(
                new TcsRegisterRecord(
                    UUID.randomUUID(),
                    pharmacyId,
                    "2026-07",
                    "Apollo",
                    "29AAAAA0000A1Z5",
                    "AAAAA0000A",
                    5_200_000L,
                    52_000L,
                    26_000L,
                    26_000L,
                    List.of(UUID.randomUUID()),
                    null)));

    Map<String, Object> data = service.generate(finance, filingId, "JSON");
    assertThat(data.get("format")).isEqualTo("JSON");
    assertThat(data.get("download_url").toString()).contains("file://");
    assertThat(data.get("record_count")).isEqualTo(1);
    assertThat((BigDecimal) data.get("total_tcs_in_file"))
        .isEqualByComparingTo(MoneyFormats.paiseToRupees(52_000L));
    verify(store).linkTcsToFiling(eq("2026-07"), eq(filingId), any());
    verify(store).appendGeneratedFile(eq(filingId), anyString(), any());
    assertThat(Files.list(temp).findAny()).isPresent();
  }

  @Test
  void ac004_markFiledRequiresReference() {
    assertThatThrownBy(() -> service.markFiled(finance, filingId, NOW, " ", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("REFERENCE_REQUIRED");
    assertThatThrownBy(() -> service.markFiled(finance, filingId, NOW, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("REFERENCE_REQUIRED");
  }

  @Test
  void ac005_pendingPastDueShowsOverdue() {
    TaxFilingRecord overdue = pendingGstr8(filingId, "2026-06", LocalDate.of(2026, 7, 10));
    when(store.listFilings(eq(2026), eq(null))).thenReturn(List.of(overdue));
    when(store.findFilingByTypeAndPeriod(anyString(), anyString()))
        .thenReturn(Optional.of(overdue));
    when(store.tcsTotals(anyString())).thenReturn(new TcsMonthTotals(0, 0, 0));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> filings =
        (List<Map<String, Object>>) service.listFilings(finance, 2026, null).get("filings");
    assertThat(filings.getFirst().get("status")).isEqualTo(TaxFilingStatuses.OVERDUE);
  }

  @Test
  void ac006_tcsRegisterReturnsPerPharmacyBreakdown() {
    when(store.listTcs(eq("2026-07"), eq(null), eq(50), eq(0)))
        .thenReturn(
            new TcsPage(
                List.of(
                    new TcsRegisterRecord(
                        UUID.randomUUID(),
                        pharmacyId,
                        "2026-07",
                        "Apollo Pharmacy, Koramangala",
                        "29AAAAA0000A1Z5",
                        "AAAAA0000A",
                        5_200_000L,
                        52_000L,
                        26_000L,
                        26_000L,
                        List.of(UUID.randomUUID()),
                        null)),
                1));
    when(store.tcsTotals("2026-07")).thenReturn(new TcsMonthTotals(5_200_000L, 52_000L, 1));

    var result = service.tcsRegister(finance, "2026-07", null, 1, 50);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> entries = (List<Map<String, Object>>) result.data().get("entries");
    assertThat(entries).hasSize(1);
    assertThat(entries.getFirst().get("gstin")).isEqualTo("29AAAAA0000A1Z5");
    assertThat(entries.getFirst().get("gmv")).isEqualTo(MoneyFormats.paiseToRupees(5_200_000L));
    assertThat(entries.getFirst().get("cgst_tcs")).isEqualTo(MoneyFormats.paiseToRupees(26_000L));
  }

  @Test
  void ac007_regenerateFiledReturnsConflict() {
    TaxFilingRecord filed =
        new TaxFilingRecord(
            filingId,
            TaxFilingTypes.GSTR_8,
            "2026-07",
            LocalDate.of(2026, 8, 10),
            TaxFilingStatuses.FILED,
            NOW,
            "ARN-1",
            null,
            finance.subject(),
            null,
            NOW,
            NOW);
    when(store.findFiling(filingId)).thenReturn(Optional.of(filed));
    assertThatThrownBy(() -> service.generate(finance, filingId, "JSON"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FILING_ALREADY_FILED");
  }

  @Test
  void ac008_otherRolesForbidden_complianceAllowed() {
    assertThatThrownBy(() -> service.taxPanel(support, "2026-07"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    when(store.tcsTotals(anyString())).thenReturn(new TcsMonthTotals(0, 0, 0));
    when(store.totalCommissionPaise(any(), any())).thenReturn(0L);
    when(store.gatewayFeesPaise(any(), any())).thenReturn(0L);
    when(store.commissionByPharmacy(any(), any())).thenReturn(List.of());
    when(store.findFilingByTypeAndPeriod(anyString(), anyString())).thenReturn(Optional.empty());
    assertThat(service.taxPanel(compliance, "2026-07").get("month")).isEqualTo("2026-07");
  }

  @Test
  void markFiledHappyPath() {
    TaxFilingRecord pending = pendingGstr8(filingId, "2026-07", LocalDate.of(2026, 8, 10));
    when(store.findFiling(filingId)).thenReturn(Optional.of(pending));
    Map<String, Object> data =
        service.markFiled(finance, filingId, NOW, "ARN-2026-08-08-XXXXXXXX", "ok");
    assertThat(data.get("status")).isEqualTo(TaxFilingStatuses.FILED);
    verify(store)
        .markFiled(
            eq(filingId),
            eq(NOW),
            eq("ARN-2026-08-08-XXXXXXXX"),
            eq("ok"),
            eq(finance.subject()),
            any());
  }

  @Test
  void generateCsvAndInvalidFormatAndCurrentMonthBlocked() {
    assertThatThrownBy(() -> service.generate(finance, filingId, "XML"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_FORMAT");

    TaxFilingRecord current = pendingGstr8(filingId, "2026-08", LocalDate.of(2026, 9, 10));
    when(store.findFiling(filingId)).thenReturn(Optional.of(current));
    assertThatThrownBy(() -> service.generate(finance, filingId, "CSV"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("DATA_NOT_AVAILABLE");

    TaxFilingRecord jul = pendingGstr8(filingId, "2026-07", LocalDate.of(2026, 8, 10));
    when(store.findFiling(filingId)).thenReturn(Optional.of(jul));
    when(store.listTcsAll("2026-07")).thenReturn(List.of());
    assertThat(service.generate(compliance, filingId, "CSV").get("format")).isEqualTo("CSV");
  }

  @Test
  void tdsEligiblePharmacyUsesPanRate() {
    when(store.tcsTotals("2026-07")).thenReturn(new TcsMonthTotals(0, 0, 0));
    when(store.totalCommissionPaise(any(), any())).thenReturn(0L);
    when(store.gatewayFeesPaise(any(), any())).thenReturn(0L);
    when(store.findFilingByTypeAndPeriod(anyString(), anyString())).thenReturn(Optional.empty());
    when(store.commissionByPharmacy(any(), any()))
        .thenReturn(
            List.of(
                new PharmacyCommissionRow(pharmacyId, "AAAAA0000A", 60_000_000L),
                new PharmacyCommissionRow(UUID.randomUUID(), "", 60_000_000L),
                new PharmacyCommissionRow(UUID.randomUUID(), "X", 1_000L)));

    @SuppressWarnings("unchecked")
    Map<String, Object> tds =
        (Map<String, Object>) service.taxPanel(finance, "2026-07").get("tds_194o");
    assertThat(tds.get("eligible_pharmacies_count")).isEqualTo(2);
    // 60L * 0.75% + 60L * 1% = 450000 + 600000 = 1050000 paise
    assertThat((BigDecimal) tds.get("tds_amount"))
        .isEqualByComparingTo(MoneyFormats.paiseToRupees(1_050_000L));
  }

  @Test
  void scheduledMaintenanceCreatesAndMarksOverdue() {
    when(store.findFilingByTypeAndPeriod(anyString(), anyString())).thenReturn(Optional.empty());
    service.runScheduledMaintenance();
    ArgumentCaptor<TaxFilingRecord> cap = ArgumentCaptor.forClass(TaxFilingRecord.class);
    verify(store, org.mockito.Mockito.atLeastOnce()).insertFiling(cap.capture());
    assertThat(cap.getAllValues())
        .anyMatch(r -> TaxFilingTypes.GSTR_8.equals(r.filingType()))
        .anyMatch(r -> TaxFilingTypes.TDS_194O.equals(r.filingType()));
    verify(store).markOverduePending(any(), any());
  }

  private static TaxFilingRecord pendingGstr8(UUID id, String period, LocalDate due) {
    return new TaxFilingRecord(
        id,
        TaxFilingTypes.GSTR_8,
        period,
        due,
        TaxFilingStatuses.PENDING,
        null,
        null,
        "desc",
        null,
        null,
        NOW,
        NOW);
  }
}
