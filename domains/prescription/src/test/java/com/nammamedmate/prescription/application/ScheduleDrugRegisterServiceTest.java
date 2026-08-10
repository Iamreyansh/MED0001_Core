package com.nammamedmate.prescription.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.prescription.application.port.out.CatalogueSchedulePort;
import com.nammamedmate.prescription.application.port.out.ComplianceExportStore;
import com.nammamedmate.prescription.application.port.out.DoctorCardPort;
import com.nammamedmate.prescription.application.port.out.InventoryBatchPort;
import com.nammamedmate.prescription.application.port.out.PrescriptionStore;
import com.nammamedmate.prescription.application.port.out.ScheduleDrugRegisterStore;
import com.nammamedmate.prescription.application.port.out.ScheduleDrugRegisterStore.ExportJob;
import com.nammamedmate.prescription.application.port.out.ScheduleDrugRegisterStore.ListFilter;
import com.nammamedmate.prescription.application.port.out.ScheduleDrugRegisterStore.ListPage;
import com.nammamedmate.prescription.application.port.out.ScheduleDrugRegisterStore.PharmacySnapshot;
import com.nammamedmate.prescription.domain.PharmacyRxQueueEntry.ApprovedMedicine;
import com.nammamedmate.prescription.domain.PrescriptionRecord;
import com.nammamedmate.prescription.domain.ScheduleDrugRegisterEntry;
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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ScheduleDrugRegisterServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-24T08:30:00Z");
  private static final UUID PHARM = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID RX = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID STAFF = UUID.fromString("33333333-3333-4333-8333-333333333333");
  private static final UUID CUST = UUID.fromString("44444444-4444-4444-8444-444444444444");

  private ScheduleDrugRegisterStore store;
  private PrescriptionStore rxStore;
  private CatalogueSchedulePort catalogue;
  private DoctorCardPort doctors;
  private InventoryBatchPort batch;
  private ComplianceExportStore exportStore;
  private DoctorRegistryService doctorRegistry;
  private ScheduleDrugRegisterService service;

  private final MedmatePrincipal compliance =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");
  private final MedmatePrincipal owner =
      new MedmatePrincipal(STAFF, AuthRole.PHARMACY_OWNER, PHARM, TokenScope.FULL, "j");
  private final MedmatePrincipal staff =
      new MedmatePrincipal(STAFF, AuthRole.PHARMACY_STAFF, PHARM, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    store = mock(ScheduleDrugRegisterStore.class);
    rxStore = mock(PrescriptionStore.class);
    catalogue = mock(CatalogueSchedulePort.class);
    doctors = mock(DoctorCardPort.class);
    batch = mock(InventoryBatchPort.class);
    exportStore = mock(ComplianceExportStore.class);
    when(exportStore.createDownloadUrl(any(), any()))
        .thenReturn("https://s3/export.csv?X-Amz-Expires=900");
    when(store.pharmacy(PHARM))
        .thenReturn(Optional.of(new PharmacySnapshot("Sai Medicals", "KA-PHR-1")));
    when(store.staffName(STAFF)).thenReturn(Optional.of("Ramesh Pharmacist"));
    when(store.orderIdForRx(RX, PHARM)).thenReturn(Optional.of(UUID.randomUUID()));
    when(store.nextSno(eq(PHARM), anyString())).thenReturn(1);
    when(store.nextRxSeq(eq(PHARM), anyInt())).thenReturn(451);
    when(batch.findOpeningStock(eq(PHARM), anyString()))
        .thenReturn(Optional.of(new InventoryBatchPort.OpeningStock("BX2024011", 500)));
    when(doctors.findForPrescription(any(), any(), any(), any()))
        .thenReturn(
            Optional.of(
                new DoctorCardPort.DoctorCard("Dr. Priya Sharma", "MBBS", "MH12345", true)));
    doctorRegistry = mock(DoctorRegistryService.class);
    service =
        new ScheduleDrugRegisterService(
            store,
            rxStore,
            catalogue,
            doctors,
            batch,
            exportStore,
            doctorRegistry,
            new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC)),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void ac001_dispenseCreatesEntryWithRunningBalance() {
    when(rxStore.findById(RX)).thenReturn(Optional.of(rx()));
    when(store.latestRunningBalance(PHARM, "H1", "Alprazolam 0.5mg")).thenReturn(Optional.empty());

    service.recordDispense(
        PHARM,
        RX,
        STAFF,
        List.of(new ApprovedMedicine("Alprazolam 0.5mg", 30, BigDecimal.ONE, "H1")));

    ArgumentCaptor<ScheduleDrugRegisterEntry> cap =
        ArgumentCaptor.forClass(ScheduleDrugRegisterEntry.class);
    verify(store).insert(cap.capture());
    ScheduleDrugRegisterEntry e = cap.getValue();
    assertThat(e.runningBalance()).isEqualTo(470);
    assertThat(e.quantityIssued()).isEqualTo(30);
    assertThat(e.schedule()).isEqualTo("H1");
    assertThat(e.rxReferenceNo()).isEqualTo("RX-2026-00451");
    assertThat(e.batchNo()).isEqualTo("BX2024011");
    assertThat(e.retentionExpiresAt())
        .isEqualTo(NOW.atZone(ZoneOffset.UTC).plusYears(3).toInstant());
    verify(doctorRegistry).recordScheduledDrug(RX);
  }

  @Test
  void ac003_exportCsvColumnsInRegulatoryOrder() {
    when(store.pharmacyExists(PHARM)).thenReturn(true);
    ScheduleDrugRegisterEntry entry = sampleEntry(false);
    when(store.listAll(any())).thenReturn(List.of(entry));
    when(store.findExportJob(any()))
        .thenAnswer(
            inv ->
                Optional.of(
                    new ExportJob(
                        inv.getArgument(0),
                        PHARM,
                        "H1",
                        LocalDate.of(2026, 4, 1),
                        LocalDate.of(2026, 6, 30),
                        "GENERATING",
                        null,
                        null,
                        compliance.subject(),
                        null,
                        null,
                        null,
                        NOW)));

    Map<String, Object> created =
        service.createExportJob(compliance, PHARM, "H1", "2026-04-01", "2026-06-30");
    assertThat(created.get("status")).isEqualTo("GENERATING");

    ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
    verify(exportStore).put(anyString(), bytes.capture(), eq("text/csv"));
    String csv = new String(bytes.getValue());
    assertThat(csv.lines().findFirst().orElseThrow())
        .isEqualTo(ScheduleDrugRegisterService.CSV_HEADER);
    assertThat(csv)
        .contains("RX-2026-00451")
        .contains("Alprazolam 0.5mg")
        .contains("Ramesh Pharmacist");
  }

  @Test
  void ac004_pharmacyListScopedToOwnPharmacy() {
    when(store.list(any())).thenReturn(new ListPage(List.of(sampleEntry(false)), 1, 30));
    var result = service.listPharmacy(owner, "H1", null, null, null, 1, 50, false);
    ArgumentCaptor<ListFilter> filter = ArgumentCaptor.forClass(ListFilter.class);
    verify(store).list(filter.capture());
    assertThat(filter.getValue().pharmacyId()).isEqualTo(PHARM);
    assertThat(result.data().get("schedule")).isEqualTo("H1");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> entries = (List<Map<String, Object>>) result.data().get("entries");
    assertThat(entries).hasSize(1);
  }

  @Test
  void ac005_adminListFiltersScheduleAndPharmacy() {
    when(store.list(any())).thenReturn(new ListPage(List.of(sampleEntryX(true)), 1, 10));
    var result = service.listAdmin(compliance, "X", PHARM, null, null, null, 1, 50, false);
    ArgumentCaptor<ListFilter> filter = ArgumentCaptor.forClass(ListFilter.class);
    verify(store).list(filter.capture());
    assertThat(filter.getValue().schedule()).isEqualTo("X");
    assertThat(filter.getValue().pharmacyId()).isEqualTo(PHARM);
    assertThat(result.data().get("schedule")).isEqualTo("X");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> entries = (List<Map<String, Object>>) result.data().get("entries");
    assertThat(entries.get(0).get("is_archived")).isEqualTo(true);
  }

  @Test
  void ac006_archivalMarksIsArchived() {
    when(store.markArchivedPastRetention(NOW)).thenReturn(1);
    assertThat(service.archiveExpired()).isEqualTo(1);
    verify(store).markArchivedPastRetention(NOW);
  }

  @Test
  void ac007_archivedXEntryStillQueryableWithRetention() {
    ScheduleDrugRegisterEntry archived = sampleEntryX(true);
    when(store.list(any())).thenReturn(new ListPage(List.of(archived), 1, 10));
    var result = service.listAdmin(compliance, "X", PHARM, null, null, null, 1, 50, false);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> entries = (List<Map<String, Object>>) result.data().get("entries");
    assertThat(entries.get(0).get("is_archived")).isEqualTo(true);
    assertThat(entries.get(0).get("retention_expires_at")).isEqualTo(archived.retentionExpiresAt());
  }

  @Test
  void ac008_pharmacyStaffForbiddenOnAdminExport() {
    assertThatThrownBy(
            () -> service.createExportJob(staff, PHARM, "H1", "2026-04-01", "2026-06-30"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).httpStatus())
        .isEqualTo(403);
  }

  @Test
  void skipsNonHxMedicinesAndEmptyInput() {
    service.recordDispense(PHARM, RX, STAFF, List.of());
    service.recordDispense(null, RX, STAFF, List.of(new ApprovedMedicine("x", 1, BigDecimal.ONE)));
    when(rxStore.findById(RX)).thenReturn(Optional.of(rx()));
    when(catalogue.resolveSchedule("Paracetamol")).thenReturn(Optional.empty());
    service.recordDispense(
        PHARM, RX, STAFF, List.of(new ApprovedMedicine("Paracetamol", 10, BigDecimal.ONE, "H")));
    verify(store, never()).insert(any());
  }

  @Test
  void usesPreviousBalanceWhenPresent() {
    when(rxStore.findById(RX)).thenReturn(Optional.of(rx()));
    when(store.latestRunningBalance(PHARM, "H1", "Alprazolam 0.5mg")).thenReturn(Optional.of(100));
    service.recordDispense(
        PHARM,
        RX,
        STAFF,
        List.of(new ApprovedMedicine("Alprazolam 0.5mg", 20, BigDecimal.ONE, "H1")));
    ArgumentCaptor<ScheduleDrugRegisterEntry> cap =
        ArgumentCaptor.forClass(ScheduleDrugRegisterEntry.class);
    verify(store).insert(cap.capture());
    assertThat(cap.getValue().runningBalance()).isEqualTo(80);
  }

  @Test
  void invalidScheduleAndDateRangeAndPharmacyNotFound() {
    assertThatThrownBy(
            () -> service.listAdmin(compliance, "H", null, null, null, null, 1, 10, false))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_SCHEDULE");
    when(store.pharmacyExists(PHARM)).thenReturn(false);
    assertThatThrownBy(
            () -> service.createExportJob(compliance, PHARM, "H1", "2026-01-01", "2026-12-31"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PHARMACY_NOT_FOUND");
    when(store.pharmacyExists(PHARM)).thenReturn(true);
    assertThatThrownBy(
            () -> service.createExportJob(compliance, PHARM, "H1", "2025-01-01", "2026-01-02"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("DATE_RANGE_TOO_LARGE");
  }

  @Test
  void retentionRulesAndPollExport() {
    Map<String, Object> rules = service.retentionRules(compliance);
    assertThat(rules.get("rules")).asList().hasSize(2);
    assertThat(service.retentionRules(owner)).isNotNull();
    assertThatThrownBy(() -> service.retentionRules(staff))
        .extracting(ex -> ((AppException) ex).httpStatus())
        .isEqualTo(403);

    UUID jobId = UUID.randomUUID();
    when(store.findExportJob(jobId))
        .thenReturn(
            Optional.of(
                new ExportJob(
                    jobId,
                    PHARM,
                    "H1",
                    LocalDate.of(2026, 4, 1),
                    LocalDate.of(2026, 6, 30),
                    "READY",
                    "exports/x.csv",
                    47,
                    compliance.subject(),
                    NOW,
                    NOW.plus(Duration.ofMinutes(15)),
                    null,
                    NOW)));
    Map<String, Object> poll = service.pollExportJob(compliance, jobId);
    assertThat(poll.get("status")).isEqualTo("READY");
    assertThat(poll.get("download_url")).isNotNull();
    assertThat(poll.get("row_count")).isEqualTo(47);
  }

  @Test
  void adminExportQueryParamAndCsvEscaping() {
    when(store.listAll(any()))
        .thenReturn(
            List.of(
                new ScheduleDrugRegisterEntry(
                    UUID.randomUUID(),
                    1,
                    PHARM,
                    "H1",
                    RX,
                    "RX-2026-00001",
                    null,
                    "Name, Jr",
                    52,
                    "Dr \"X\"",
                    "R1",
                    "Drug",
                    null,
                    1,
                    "TABLETS",
                    9,
                    "LIC",
                    "By",
                    STAFF,
                    NOW,
                    NOW.plusSeconds(1),
                    false,
                    NOW)));
    var result = service.listAdmin(compliance, "H1", PHARM, null, null, null, 1, 50, true);
    assertThat(result.data().get("download_url")).isNotNull();
    ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
    verify(exportStore).put(anyString(), bytes.capture(), eq("text/csv"));
    assertThat(new String(bytes.getValue())).contains("\"Name, Jr\"").contains("\"Dr \"\"X\"\"\"");
  }

  @Test
  void scheduleXRetentionFiveYears() {
    when(rxStore.findById(RX)).thenReturn(Optional.of(rx()));
    when(catalogue.resolveSchedule("Morphine Syrup ML")).thenReturn(Optional.of("X"));
    when(store.latestRunningBalance(PHARM, "X", "Morphine Syrup ML")).thenReturn(Optional.of(10));
    when(batch.findOpeningStock(PHARM, "Morphine Syrup ML")).thenReturn(Optional.empty());
    service.recordDispense(
        PHARM, RX, STAFF, List.of(new ApprovedMedicine("Morphine Syrup ML", 2, BigDecimal.ONE)));
    ArgumentCaptor<ScheduleDrugRegisterEntry> cap =
        ArgumentCaptor.forClass(ScheduleDrugRegisterEntry.class);
    verify(store).insert(cap.capture());
    assertThat(cap.getValue().schedule()).isEqualTo("X");
    assertThat(cap.getValue().unit()).isEqualTo("ML");
    assertThat(cap.getValue().retentionExpiresAt())
        .isEqualTo(NOW.atZone(ZoneOffset.UTC).plusYears(5).toInstant());
  }

  private PrescriptionRecord rx() {
    return new PrescriptionRecord(
        RX,
        CUST,
        "UPLOADED",
        "VERIFIED",
        "k",
        1,
        "image/jpeg",
        "Ravi Kumar",
        null,
        "Dr. Priya Sharma",
        LocalDate.of(2026, 7, 1),
        "CUSTOMER_UPLOAD",
        null,
        null,
        null,
        NOW.plusSeconds(86400),
        null,
        NOW,
        NOW,
        null);
  }

  private ScheduleDrugRegisterEntry sampleEntry(boolean archived) {
    return new ScheduleDrugRegisterEntry(
        UUID.randomUUID(),
        1,
        PHARM,
        "H1",
        RX,
        "RX-2026-00451",
        UUID.randomUUID(),
        "Ravi Kumar",
        52,
        "Dr. Priya Sharma",
        "MH12345",
        "Alprazolam 0.5mg",
        "BX2024011",
        30,
        "TABLETS",
        470,
        "KA-PHR-1",
        "Ramesh Pharmacist",
        STAFF,
        NOW,
        NOW.atZone(ZoneOffset.UTC).plusYears(3).toInstant(),
        archived,
        NOW);
  }

  private ScheduleDrugRegisterEntry sampleEntryX(boolean archived) {
    Instant dispensed = NOW.atZone(ZoneOffset.UTC).minusYears(5).toInstant();
    return new ScheduleDrugRegisterEntry(
        UUID.randomUUID(),
        1,
        PHARM,
        "X",
        RX,
        "RX-2021-00001",
        UUID.randomUUID(),
        "Ravi Kumar",
        null,
        "Dr. Priya Sharma",
        "MH12345",
        "Morphine",
        "BX1",
        10,
        "TABLETS",
        0,
        "KA-PHR-1",
        "Ramesh Pharmacist",
        STAFF,
        dispensed,
        dispensed.atZone(ZoneOffset.UTC).plusYears(5).toInstant(),
        archived,
        dispensed);
  }
}
