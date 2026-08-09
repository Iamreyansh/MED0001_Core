package com.nammamedmate.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.payment.adapter.out.persistence.LocalTaxFilingObjectStore;
import com.nammamedmate.payment.application.port.out.TaxPharmacyProfilePort;
import com.nammamedmate.payment.application.port.out.TaxStorePort;
import com.nammamedmate.payment.application.port.out.TaxStorePort.TaxFilingRecord;
import com.nammamedmate.payment.application.port.out.TaxStorePort.TcsMonthTotals;
import com.nammamedmate.payment.application.port.out.TaxStorePort.TcsPage;
import com.nammamedmate.payment.domain.TaxFilingStatuses;
import com.nammamedmate.payment.domain.TaxFilingTypes;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TaxFacadeCoverageTest {

  private static final Instant NOW = Instant.parse("2026-08-09T10:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Mock TaxStorePort store;
  @Mock TaxPharmacyProfilePort pharmacies;
  @TempDir java.nio.file.Path temp;

  TaxFacadeService service;
  MedmatePrincipal finance =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_FINANCE, null, TokenScope.FULL, "j");
  MedmatePrincipal superAdmin =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

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
  void rolesMonthsQuartersAndFilters() {
    assertThatThrownBy(() -> TaxFacadeService.requireTaxRole(null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    assertThat(TaxFacadeService.parseMonth(null, CLOCK)).isEqualTo(YearMonth.of(2026, 8));
    assertThat(TaxFacadeService.parseMonth(" 2026-07 ", CLOCK)).isEqualTo(YearMonth.of(2026, 7));
    assertThatThrownBy(() -> TaxFacadeService.parseMonth("nope", CLOCK))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_MONTH");

    assertThat(TaxFacadeService.gstr8DueDate(YearMonth.of(2026, 7)))
        .isEqualTo(LocalDate.of(2026, 8, 10));
    assertThat(TaxFacadeService.currentFyQuarterPeriod(LocalDate.of(2026, 5, 1)))
        .isEqualTo("Q1-2026");
    assertThat(TaxFacadeService.currentFyQuarterPeriod(LocalDate.of(2026, 8, 1)))
        .isEqualTo("Q2-2026");
    assertThat(TaxFacadeService.currentFyQuarterPeriod(LocalDate.of(2026, 11, 1)))
        .isEqualTo("Q3-2026");
    assertThat(TaxFacadeService.currentFyQuarterPeriod(LocalDate.of(2026, 2, 1)))
        .isEqualTo("Q4-2026");
    assertThat(TaxFacadeService.tdsDueDate("Q1-2026")).isEqualTo(LocalDate.of(2026, 7, 31));
    assertThat(TaxFacadeService.tdsDueDate("Q2-2026")).isEqualTo(LocalDate.of(2026, 10, 31));
    assertThat(TaxFacadeService.tdsDueDate("Q3-2026")).isEqualTo(LocalDate.of(2027, 1, 31));
    assertThat(TaxFacadeService.tdsDueDate("Q4-2026")).isEqualTo(LocalDate.of(2026, 5, 31));
    assertThat(TaxFacadeService.quarterEndMonth("Q1-2026")).isEqualTo(YearMonth.of(2026, 6));
    assertThat(TaxFacadeService.quarterEndMonth("Q2-2026")).isEqualTo(YearMonth.of(2026, 9));
    assertThat(TaxFacadeService.quarterEndMonth("Q3-2026")).isEqualTo(YearMonth.of(2026, 12));
    assertThat(TaxFacadeService.quarterEndMonth("Q4-2026")).isEqualTo(YearMonth.of(2026, 3));
    assertThatThrownBy(() -> TaxFacadeService.tdsDueDate("Q9-2026"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TaxFacadeService.quarterEndMonth("Q9-2026"))
        .isInstanceOf(IllegalArgumentException.class);

    when(store.findFilingByTypeAndPeriod(anyString(), anyString())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.listFilings(finance, 2026, "NOPE"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_STATUS");
  }

  @Test
  void listFilingsStatusFilterAndExistingEnsureSkip() {
    UUID id = UUID.randomUUID();
    TaxFilingRecord pending =
        new TaxFilingRecord(
            id,
            TaxFilingTypes.GSTR_8,
            "2026-07",
            LocalDate.of(2026, 8, 10),
            TaxFilingStatuses.PENDING,
            null,
            null,
            "",
            null,
            null,
            NOW,
            NOW);
    TaxFilingRecord filed =
        new TaxFilingRecord(
            UUID.randomUUID(),
            TaxFilingTypes.GSTR_3B,
            "2026-06",
            LocalDate.of(2026, 7, 20),
            TaxFilingStatuses.FILED,
            NOW,
            "ARN",
            "filed",
            finance.subject(),
            null,
            NOW,
            NOW);
    when(store.findFilingByTypeAndPeriod(anyString(), anyString()))
        .thenReturn(Optional.of(pending));
    when(store.listFilings(eq(2026), eq("FILED"))).thenReturn(List.of(filed, pending));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> filings =
        (List<Map<String, Object>>) service.listFilings(superAdmin, 2026, "FILED").get("filings");
    assertThat(filings).hasSize(1);
    assertThat(filings.getFirst().get("status")).isEqualTo(TaxFilingStatuses.FILED);
  }

  @Test
  void generateNotFoundAlreadyFiledTdsJsonAndUnsupportedType() {
    when(store.findFiling(any())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.generate(finance, UUID.randomUUID(), null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FILING_NOT_FOUND");

    UUID id = UUID.randomUUID();
    when(store.findFiling(id))
        .thenReturn(
            Optional.of(
                new TaxFilingRecord(
                    id,
                    TaxFilingTypes.GSTR_1,
                    "2026-07",
                    LocalDate.of(2026, 8, 11),
                    TaxFilingStatuses.PENDING,
                    null,
                    null,
                    null,
                    null,
                    null,
                    NOW,
                    NOW)));
    assertThatThrownBy(() -> service.generate(finance, id, "JSON"))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("DATA_NOT_AVAILABLE");

    when(store.findFiling(id))
        .thenReturn(
            Optional.of(
                new TaxFilingRecord(
                    id,
                    TaxFilingTypes.TDS_194O,
                    "Q2-2026",
                    LocalDate.of(2026, 10, 31),
                    TaxFilingStatuses.PENDING,
                    null,
                    null,
                    null,
                    null,
                    null,
                    NOW,
                    NOW)));
    when(store.commissionByPharmacy(any(), any())).thenReturn(List.of());
    assertThat(service.generate(finance, id, "JSON").get("filing_type"))
        .isEqualTo(TaxFilingTypes.TDS_194O);
    assertThat(service.generate(finance, id, "CSV").get("format")).isEqualTo("CSV");
  }

  @Test
  void markFiledNotFoundAndAlreadyFiled() {
    when(store.findFiling(any())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.markFiled(finance, UUID.randomUUID(), null, "ARN", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FILING_NOT_FOUND");

    UUID id = UUID.randomUUID();
    when(store.findFiling(id))
        .thenReturn(
            Optional.of(
                new TaxFilingRecord(
                    id,
                    TaxFilingTypes.GSTR_8,
                    "2026-07",
                    LocalDate.of(2026, 8, 10),
                    TaxFilingStatuses.FILED,
                    NOW,
                    "ARN",
                    null,
                    finance.subject(),
                    null,
                    NOW,
                    NOW)));
    assertThatThrownBy(() -> service.markFiled(finance, id, null, "ARN2", null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("ALREADY_FILED");
  }

  @Test
  void recordReleaseWithoutProfileUsesDefaultsAndTcsPaging() {
    UUID pharmacyId = UUID.randomUUID();
    when(pharmacies.find(pharmacyId)).thenReturn(Optional.empty());
    service.recordReleasedSettlement(UUID.randomUUID(), pharmacyId, "2026-07", 100, 1, null);
    verify(store)
        .upsertTcsOnRelease(
            eq(pharmacyId), eq("2026-07"), eq(""), eq(""), eq(""), any(), eq(100L), eq(1L), any());

    when(store.listTcs(eq("2026-07"), eq(pharmacyId), eq(50), eq(0)))
        .thenReturn(new TcsPage(List.of(), 0));
    when(store.tcsTotals("2026-07")).thenReturn(new TcsMonthTotals(0, 0, 0));
    assertThat(service.tcsRegister(finance, "2026-07", pharmacyId, 0, 0).meta().total())
        .isEqualTo(0);
  }

  @Test
  void coverageExtrasForBranchesAndJsonFailures() throws Exception {
    assertThat(new TaxFacadeService.PagedResult(null, PaginationMeta.of(1, 1, 0)).data()).isEmpty();
    assertThat(TaxFacadeService.parseMonth("  ", CLOCK)).isEqualTo(YearMonth.of(2026, 8));

    when(store.findFilingByTypeAndPeriod(anyString(), anyString())).thenReturn(Optional.empty());
    service.listFilings(finance, 2026, "   ");

    UUID id = UUID.randomUUID();
    TaxFilingRecord blankNotes =
        new TaxFilingRecord(
            id,
            TaxFilingTypes.TDS_194O,
            "Q2-2026",
            LocalDate.of(2026, 10, 31),
            TaxFilingStatuses.PENDING,
            null,
            null,
            " ",
            null,
            null,
            NOW,
            NOW);
    when(store.findFilingByTypeAndPeriod(anyString(), anyString()))
        .thenReturn(Optional.of(blankNotes));
    when(store.listFilings(isNull(), isNull())).thenReturn(List.of(blankNotes));
    when(store.commissionByPharmacy(any(), any())).thenReturn(List.of());
    assertThat(service.listFilings(finance, null, null).get("filings")).isNotNull();

    when(store.findFiling(id)).thenReturn(Optional.of(blankNotes));
    Map<String, Object> marked = service.markFiled(finance, id, null, "ARN-X", null);
    assertThat(marked.get("filed_at")).isEqualTo(NOW.toString());

    ObjectMapper failing =
        new ObjectMapper() {
          @Override
          public String writeValueAsString(Object value) throws JsonProcessingException {
            throw new JsonProcessingException("boom") {};
          }

          @Override
          public byte[] writeValueAsBytes(Object value) throws JsonProcessingException {
            throw new JsonProcessingException("boom") {};
          }
        };
    TaxFacadeService badJson =
        new TaxFacadeService(
            store,
            pharmacies,
            new LocalTaxFilingObjectStore(temp, "file://" + temp),
            failing,
            CLOCK);
    TaxFilingRecord gstr8 =
        new TaxFilingRecord(
            id,
            TaxFilingTypes.GSTR_8,
            "2026-07",
            LocalDate.of(2026, 8, 10),
            TaxFilingStatuses.PENDING,
            null,
            null,
            null,
            null,
            null,
            NOW,
            NOW);
    when(store.findFiling(id)).thenReturn(Optional.of(gstr8));
    when(store.listTcsAll("2026-07"))
        .thenReturn(
            List.of(
                new TaxStorePort.TcsRegisterRecord(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "2026-07",
                    "P",
                    null,
                    null,
                    1,
                    1,
                    0,
                    1,
                    null,
                    null)));
    assertThatThrownBy(() -> badJson.generate(finance, id, "JSON"))
        .isInstanceOf(IllegalStateException.class);

    when(store.findFiling(id))
        .thenReturn(
            Optional.of(
                new TaxFilingRecord(
                    id,
                    TaxFilingTypes.TDS_194O,
                    "Q2-2026",
                    LocalDate.of(2026, 10, 31),
                    TaxFilingStatuses.PENDING,
                    null,
                    null,
                    null,
                    null,
                    null,
                    NOW,
                    NOW)));
    when(store.commissionByPharmacy(any(), any())).thenReturn(List.of());
    assertThatThrownBy(() -> badJson.generate(finance, id, "JSON"))
        .isInstanceOf(IllegalStateException.class);

    ObjectMapper failOnMeta =
        new ObjectMapper() {
          int calls;

          @Override
          public String writeValueAsString(Object value) throws JsonProcessingException {
            calls++;
            if (calls > 1) {
              throw new JsonProcessingException("meta") {};
            }
            return new ObjectMapper().writeValueAsString(value);
          }
        };
    TaxFacadeService metaFail =
        new TaxFacadeService(
            store,
            pharmacies,
            new LocalTaxFilingObjectStore(temp, "file://" + temp),
            failOnMeta,
            CLOCK);
    when(store.findFiling(id)).thenReturn(Optional.of(gstr8));
    when(store.listTcsAll("2026-07")).thenReturn(List.of());
    assertThatThrownBy(() -> metaFail.generate(finance, id, "JSON"))
        .isInstanceOf(IllegalStateException.class);

    when(store.listTcs(eq("2026-07"), isNull(), eq(100), eq(100)))
        .thenReturn(new TcsPage(List.of(), 0));
    when(store.listTcs(eq("2026-07"), isNull(), eq(50), eq(0)))
        .thenReturn(new TcsPage(List.of(), 0));
    when(store.listTcs(eq("2026-07"), isNull(), eq(10), eq(0)))
        .thenReturn(new TcsPage(List.of(), 0));
    when(store.tcsTotals("2026-07")).thenReturn(new TcsMonthTotals(0, 0, 0));
    assertThat(service.tcsRegister(finance, "2026-07", null, 2, 500).meta().page()).isEqualTo(2);
    assertThat(service.tcsRegister(finance, "2026-07", null, null, null).meta().limit())
        .isEqualTo(50);
    assertThat(service.tcsRegister(finance, "2026-07", null, 1, 10).meta().limit()).isEqualTo(10);

    TaxFilingRecord nullNotes =
        new TaxFilingRecord(
            id,
            TaxFilingTypes.GSTR_3B,
            "2026-06",
            LocalDate.of(2026, 7, 20),
            TaxFilingStatuses.PENDING,
            NOW,
            null,
            null,
            null,
            null,
            NOW,
            NOW);
    when(store.findFilingByTypeAndPeriod(anyString(), anyString()))
        .thenReturn(Optional.of(nullNotes));
    when(store.listFilings(eq(2026), isNull())).thenReturn(List.of(nullNotes));
    assertThat(
            ((java.util.List<?>) service.listFilings(finance, 2026, null).get("filings"))
                .getFirst())
        .extracting(m -> ((Map<?, ?>) m).get("description"))
        .isEqualTo("GSTR-3B for 2026-06");

    when(store.commissionByPharmacy(any(), any()))
        .thenReturn(
            List.of(new TaxStorePort.PharmacyCommissionRow(UUID.randomUUID(), null, 60_000_000L)));
    when(store.tcsTotals(anyString())).thenReturn(new TcsMonthTotals(0, 0, 0));
    when(store.totalCommissionPaise(any(), any())).thenReturn(0L);
    when(store.gatewayFeesPaise(any(), any())).thenReturn(0L);
    when(store.findFilingByTypeAndPeriod(anyString(), anyString())).thenReturn(Optional.empty());
    service.taxPanel(finance, "2026-07");

    // overdue TDS creation (after Q3 due 2027-01-31)
    Clock afterTdsDue = Clock.fixed(Instant.parse("2027-02-05T10:00:00Z"), ZoneOffset.UTC);
    TaxFacadeService afterDue =
        new TaxFacadeService(
            store,
            pharmacies,
            new LocalTaxFilingObjectStore(temp, "file://" + temp),
            new ObjectMapper(),
            afterTdsDue);
    when(store.findFilingByTypeAndPeriod(anyString(), anyString())).thenReturn(Optional.empty());
    afterDue.runScheduledMaintenance();

    // overdue ensure path for prior month relative to far-future clock
    Clock late = Clock.fixed(Instant.parse("2026-12-15T10:00:00Z"), ZoneOffset.UTC);
    TaxFacadeService lateSvc =
        new TaxFacadeService(
            store,
            pharmacies,
            new LocalTaxFilingObjectStore(temp, "file://" + temp),
            new ObjectMapper(),
            late);
    when(store.findFilingByTypeAndPeriod(anyString(), anyString())).thenReturn(Optional.empty());
    lateSvc.runScheduledMaintenance();
  }

  @Test
  void taxPanelWithExistingFilingAndCsvEscaping() {
    UUID filingId = UUID.randomUUID();
    when(store.tcsTotals("2026-07")).thenReturn(new TcsMonthTotals(100, 1, 1));
    when(store.totalCommissionPaise(any(), any())).thenReturn(100L);
    when(store.gatewayFeesPaise(any(), any())).thenReturn(0L);
    when(store.commissionByPharmacy(any(), any())).thenReturn(List.of());
    when(store.findFilingByTypeAndPeriod(TaxFilingTypes.GSTR_8, "2026-07"))
        .thenReturn(
            Optional.of(
                new TaxFilingRecord(
                    filingId,
                    TaxFilingTypes.GSTR_8,
                    "2026-07",
                    LocalDate.of(2026, 8, 10),
                    TaxFilingStatuses.PENDING,
                    null,
                    null,
                    "note",
                    null,
                    null,
                    NOW,
                    NOW)));
    assertThat(service.taxPanel(finance, "2026-07").get("gstr8_status"))
        .isEqualTo(TaxFilingStatuses.PENDING);

    when(store.findFiling(filingId))
        .thenReturn(
            Optional.of(
                new TaxFilingRecord(
                    filingId,
                    TaxFilingTypes.GSTR_8,
                    "2026-07",
                    LocalDate.of(2026, 8, 10),
                    TaxFilingStatuses.PENDING,
                    null,
                    null,
                    null,
                    null,
                    null,
                    NOW,
                    NOW)));
    when(store.listTcsAll("2026-07"))
        .thenReturn(
            List.of(
                new TaxStorePort.TcsRegisterRecord(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "2026-07",
                    "P",
                    "A,B",
                    "C\"D",
                    100,
                    1,
                    0,
                    1,
                    List.of(),
                    null),
                new TaxStorePort.TcsRegisterRecord(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "2026-07",
                    "P2",
                    null,
                    "SIMPLE",
                    100,
                    1,
                    0,
                    1,
                    List.of(),
                    null)));
    assertThat(service.generate(finance, filingId, "CSV").get("record_count")).isEqualTo(2);
  }
}
