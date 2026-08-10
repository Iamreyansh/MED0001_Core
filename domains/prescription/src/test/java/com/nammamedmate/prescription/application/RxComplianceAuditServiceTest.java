package com.nammamedmate.prescription.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.kernel.storage.PresignedUrlService;
import com.nammamedmate.kernel.storage.PresignedUrlService.PresignedUrl;
import com.nammamedmate.prescription.application.port.out.CatalogueSchedulePort;
import com.nammamedmate.prescription.application.port.out.ComplianceExportStore;
import com.nammamedmate.prescription.application.port.out.DoctorCardPort;
import com.nammamedmate.prescription.application.port.out.NotificationDispatchPort;
import com.nammamedmate.prescription.application.port.out.PrescriptionStore;
import com.nammamedmate.prescription.application.port.out.RxAuditStore;
import com.nammamedmate.prescription.application.port.out.RxAuditStore.DispenseContext;
import com.nammamedmate.prescription.application.port.out.RxAuditStore.DuplicateMatch;
import com.nammamedmate.prescription.application.port.out.RxAuditStore.Kpis;
import com.nammamedmate.prescription.application.port.out.RxAuditStore.ListPage;
import com.nammamedmate.prescription.application.port.out.RxAuditStore.ListRow;
import com.nammamedmate.prescription.application.port.out.RxAuditStore.Stats;
import com.nammamedmate.prescription.domain.PharmacyRxQueueEntry.ApprovedMedicine;
import com.nammamedmate.prescription.domain.PrescriptionRecord;
import com.nammamedmate.prescription.domain.PrescriptionRecord.MedicineExtracted;
import com.nammamedmate.prescription.domain.RxAuditEntry;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RxComplianceAuditServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
  private static final UUID ADMIN = UUID.fromString("a1000001-0000-4000-8000-0000000000a1");
  private static final UUID OPS = UUID.fromString("a1000001-0000-4000-8000-0000000000a2");
  private static final MedmatePrincipal COMPLIANCE =
      new MedmatePrincipal(ADMIN, AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");
  private static final MedmatePrincipal OPERATIONS =
      new MedmatePrincipal(OPS, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
  private static final MedmatePrincipal SUPER =
      new MedmatePrincipal(ADMIN, AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

  private InMemoryAuditStore store;
  private PrescriptionStore rxStore;
  private NotificationDispatchPort notifications;
  private ComplianceExportStore exportStore;
  private RxComplianceAuditService service;

  @BeforeEach
  void setUp() {
    store = new InMemoryAuditStore();
    rxStore = mock(PrescriptionStore.class);
    notifications = mock(NotificationDispatchPort.class);
    exportStore = mock(ComplianceExportStore.class);
    when(exportStore.createDownloadUrl(any(), any())).thenReturn("https://local/export.csv");
    DoctorCardPort doctors =
        (rxId, type, name, tele) ->
            Optional.of(new DoctorCardPort.DoctorCard(name, "MBBS", "MH1", true));
    CatalogueSchedulePort catalogue =
        name ->
            name != null && name.toUpperCase().contains("ALPRAZOLAM")
                ? Optional.of("H1")
                : Optional.empty();
    PresignedUrlService presigner =
        new PresignedUrlService() {
          @Override
          public PresignedUrl createPutUrl(String key, String contentType, Duration ttl) {
            return new PresignedUrl("https://x/" + key, key, ttl);
          }

          @Override
          public PresignedUrl createGetUrl(String key, Duration ttl) {
            return new PresignedUrl("https://x/" + key + "?g=1", key, ttl);
          }
        };
    service =
        new RxComplianceAuditService(
            store,
            rxStore,
            catalogue,
            exportStore,
            notifications,
            doctors,
            presigner,
            new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC)),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void ac001_overdueAuditTransitionsAndAlerts() {
    UUID rxId = seedAwaiting(NOW.minus(Duration.ofHours(25)), "H1");
    int n = service.markOverdueAudits();
    assertThat(n).isEqualTo(1);
    assertThat(store.findByRxId(rxId).orElseThrow().auditStatus()).isEqualTo("OVERDUE_AUDIT");
    verify(notifications).notifyComplianceOverdueAudit(eq(rxId), any());
  }

  @Test
  void ac002_possibleDuplicateOnDetail() {
    UUID first = Ids.newId();
    UUID second = Ids.newId();
    seedRx(first, "Ravi", List.of(new MedicineExtracted("Alprazolam", "90", null, "H1")));
    seedRx(second, "Ravi", List.of(new MedicineExtracted("Alprazolam", "90", null, "H1")));
    store.insert(awaiting(first, "H1", NOW.minus(Duration.ofDays(2))));
    store.dispense.put(
        second,
        new DispenseContext(
            NOW,
            List.of(Map.of("name", "Alprazolam", "quantity", 90, "schedule", "H1")),
            "Ravi",
            "Dr"));
    store.insert(awaiting(second, "H1", NOW));
    store.duplicates.put("Ravi|Alprazolam|90", new DuplicateMatch(first, Ids.newId()));

    Map<String, Object> detail = service.get(COMPLIANCE, second);
    assertThat(detail.get("possible_duplicate")).isEqualTo(true);
    assertThat(detail.get("possible_duplicate_rx_id")).isEqualTo(first);
  }

  @Test
  void ac003_highSeverityFlagEscalatesEmail() {
    UUID rxId = seedAwaiting(NOW, "H1");
    Map<String, Object> data = service.flag(COMPLIANCE, rxId, "Suspected duplicate", "HIGH");
    assertThat(data.get("audit_status")).isEqualTo("FLAGGED");
    assertThat(data.get("escalation_sent")).isEqualTo(true);
    verify(notifications).notifyHeadOfComplianceFlag(rxId, "HIGH", "Suspected duplicate");
    assertThat(store.activities).isNotEmpty();
  }

  @Test
  void ac004_verifyAndFlagAreAppendOnly_noPatchDeleteInApi() {
    UUID rxId = seedAwaiting(NOW, "H1");
    service.verify(COMPLIANCE, rxId, true, null, "ok");
    assertThat(store.activities.stream().anyMatch(a -> "RX_VERIFIED".equals(a.action()))).isTrue();
    // PATCH/DELETE not mapped — immutability is API + DB trigger; service only appends.
    assertThat(store.activities).hasSize(1);
  }

  @Test
  void ac005_operationsDetailOmitsFileUrl() {
    UUID rxId = seedAwaiting(NOW, "H1");
    seedRx(rxId, "Ravi", List.of(new MedicineExtracted("Alprazolam", "10", null, "H1")));
    Map<String, Object> ops = service.get(OPERATIONS, rxId);
    assertThat(ops).doesNotContainKey("file_url");
    Map<String, Object> compliance = service.get(COMPLIANCE, rxId);
    assertThat(compliance.get("file_url").toString()).contains("https://x/");
  }

  @Test
  void ac006_exportReturnsCsvUrl() {
    for (int i = 0; i < 3; i++) {
      seedAwaiting(NOW, "H1");
    }
    var result = service.list(COMPLIANCE, "ALL", "ALL", null, null, null, null, null, 1, 20, true);
    assertThat(result.data().get("download_url")).isEqualTo("https://local/export.csv");
    assertThat(result.data().get("record_count")).isEqualTo(3);
    verify(exportStore).put(any(), any(), eq("text/csv"));
  }

  @Test
  void ac007_statisticsBySchedule() {
    store.stats =
        new Stats(
            Map.of("H", 98.2, "H1", 95.4, "X", 93.1), 3.2, List.of(), List.of(), 312, 295, 10, 2);
    Map<String, Object> data = service.statistics(SUPER, null, null);
    @SuppressWarnings("unchecked")
    Map<String, Double> rates = (Map<String, Double>) data.get("compliance_rate_by_schedule");
    assertThat(rates).containsKeys("H", "H1", "X");
  }

  @Test
  void ac008_ocrChecklistLabeled() {
    UUID rxId = seedAwaiting(NOW, "H1");
    seedRx(rxId, "Ravi", List.of(new MedicineExtracted("Alprazolam 0.5mg", "90", "1-0-1", "H1")));
    Map<String, Object> detail = service.get(COMPLIANCE, rxId);
    @SuppressWarnings("unchecked")
    Map<String, Object> checklist = (Map<String, Object>) detail.get("verification_checklist");
    @SuppressWarnings("unchecked")
    Map<String, Object> schedule = (Map<String, Object>) checklist.get("schedule_check");
    assertThat(schedule.get("source_label")).isEqualTo("OCR extracted");
    assertThat(schedule.get("medicines_extracted")).isNotNull();
  }

  @Test
  void createFromDispense_h1AndSkipNone() {
    UUID rxId = Ids.newId();
    PrescriptionRecord rx =
        seedRx(rxId, "P", List.of(new MedicineExtracted("Vit C", "10", null, null)));
    assertThat(
            service.createFromDispense(
                rxId,
                null,
                UUID.randomUUID(),
                List.of(new ApprovedMedicine("Vit C", 10, BigDecimal.ONE)),
                rx,
                NOW))
        .isEmpty();
    assertThat(
            service.createFromDispense(
                rxId,
                null,
                UUID.randomUUID(),
                List.of(new ApprovedMedicine("Alprazolam", 10, BigDecimal.ONE, "H1")),
                rx,
                NOW))
        .isPresent();
    assertThat(
            service.createFromDispense(
                rxId,
                null,
                UUID.randomUUID(),
                List.of(new ApprovedMedicine("Alprazolam", 10, BigDecimal.ONE, "H1")),
                rx,
                NOW))
        .isEmpty();
  }

  @Test
  void listAndVerifyAndLowFlag() {
    UUID rxId = seedAwaiting(NOW, "H");
    seedRx(rxId, "P", List.of());
    var listed =
        service.list(COMPLIANCE, "H", "AWAITING_AUDIT", null, null, null, null, null, 1, 20, false);
    assertThat(listed.data().get("prescriptions")).isInstanceOf(List.class);
    service.verify(COMPLIANCE, rxId, false, "bad qty", "note");
    UUID rx2 = seedAwaiting(NOW, "X");
    Map<String, Object> low = service.flag(COMPLIANCE, rx2, "watch", "LOW");
    assertThat(low.get("escalation_sent")).isEqualTo(false);
  }

  @Test
  void forbiddenAndValidation() {
    assertThatThrownBy(
            () ->
                service.list(
                    new MedmatePrincipal(ADMIN, AuthRole.CUSTOMER, null, TokenScope.FULL, "j"),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    1,
                    20,
                    false))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    UUID rxId = seedAwaiting(NOW, "H1");
    assertThatThrownBy(() -> service.verify(OPERATIONS, rxId, true, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.verify(COMPLIANCE, rxId, null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.flag(COMPLIANCE, rxId, "x", "CRITICAL"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void csvAndJsonHelpers() {
    assertThat(RxComplianceAuditService.toJson(null)).isEqualTo("{}");
    assertThat(RxComplianceAuditService.toJson(Map.of("a", true, "b", 1, "c", "x")))
        .contains("\"a\":true");
    assertThat(
            RxComplianceAuditService.buildCsv(
                List.of(
                    new ListRow(
                        awaiting(Ids.newId(), "H1", NOW),
                        "P",
                        "D",
                        true,
                        "Pharm",
                        NOW,
                        "UPLOADED",
                        "Drug \"A\", B"))))
        .contains("rx_id,patient_name");
    assertThat(RxComplianceAuditService.startOfUtcDay(LocalDate.of(2026, 7, 24))).isNotNull();
  }

  private UUID seedAwaiting(Instant created, String schedule) {
    UUID rxId = Ids.newId();
    seedRx(rxId, "Patient", List.of());
    store.insert(awaiting(rxId, schedule, created));
    return rxId;
  }

  private RxAuditEntry awaiting(UUID rxId, String schedule, Instant created) {
    return new RxAuditEntry(
        Ids.newId(),
        rxId,
        null,
        UUID.randomUUID(),
        schedule,
        "AWAITING_AUDIT",
        created.plus(RxAuditEntry.deadlineFor(schedule)),
        false,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        created);
  }

  private PrescriptionRecord seedRx(UUID id, String patient, List<MedicineExtracted> meds) {
    PrescriptionRecord r =
        new PrescriptionRecord(
            id,
            UUID.randomUUID(),
            "UPLOADED",
            "DISPENSED",
            "prescriptions/" + id + ".jpg",
            100,
            "image/jpeg",
            patient,
            null,
            "Dr Priya",
            LocalDate.of(2026, 7, 1),
            "UPLOAD",
            meds,
            null,
            null,
            NOW.plus(Duration.ofDays(30)),
            null,
            NOW,
            NOW,
            null);
    when(rxStore.findById(id)).thenReturn(Optional.of(r));
    return r;
  }

  private static final class InMemoryAuditStore implements RxAuditStore {
    private final ConcurrentHashMap<UUID, RxAuditEntry> byRx = new ConcurrentHashMap<>();
    private final List<Activity> activities = new CopyOnWriteArrayList<>();
    private final Map<String, DuplicateMatch> duplicates = new ConcurrentHashMap<>();
    private final Map<UUID, DispenseContext> dispense = new ConcurrentHashMap<>();
    private Stats stats =
        new Stats(Map.of("H", 0d, "H1", 0d, "X", 0d), 0d, List.of(), List.of(), 0, 0, 0, 0);

    record Activity(String action, UUID rxId) {}

    @Override
    public void insert(RxAuditEntry entry) {
      byRx.put(entry.rxId(), entry);
    }

    @Override
    public Optional<RxAuditEntry> findByRxId(UUID rxId) {
      return Optional.ofNullable(byRx.get(rxId));
    }

    @Override
    public void update(RxAuditEntry entry) {
      byRx.put(entry.rxId(), entry);
    }

    @Override
    public void appendActivity(
        UUID id,
        UUID rxId,
        String action,
        UUID actorId,
        String actorRole,
        String payloadJson,
        Instant createdAt) {
      activities.add(new Activity(action, rxId));
    }

    @Override
    public List<Map<String, Object>> listActivity(UUID rxId) {
      return List.of();
    }

    @Override
    public ListPage list(ListFilter filter, Instant now) {
      List<ListRow> rows = new ArrayList<>();
      for (RxAuditEntry e : byRx.values()) {
        if (!"ALL".equals(filter.schedule()) && !filter.schedule().equals(e.schedule())) {
          continue;
        }
        if (!"ALL".equals(filter.status()) && !filter.status().equals(e.auditStatus())) {
          continue;
        }
        rows.add(new ListRow(e, "P", "D", true, "Pharm", e.createdAt(), "UPLOADED", "Drug"));
      }
      return new ListPage(rows, rows.size(), new Kpis(rows.size(), 0, 0, 0, 100d));
    }

    @Override
    public List<ListRow> listAllForExport(ListFilter filter) {
      return list(filter, NOW).items();
    }

    @Override
    public Optional<DuplicateMatch> findDuplicate(
        String patientName, String drugName, int quantity, Instant since, UUID excludeRxId) {
      return Optional.ofNullable(duplicates.get(patientName + "|" + drugName + "|" + quantity));
    }

    @Override
    public List<RxAuditEntry> findAwaitingPastDeadline(Instant now, int limit) {
      List<RxAuditEntry> out = new ArrayList<>();
      for (RxAuditEntry e : byRx.values()) {
        if ("AWAITING_AUDIT".equals(e.auditStatus()) && e.auditDeadline().isBefore(now)) {
          out.add(e);
        }
      }
      return out;
    }

    @Override
    public int markOverdue(UUID id, Instant now) {
      for (RxAuditEntry e : byRx.values()) {
        if (e.id().equals(id) && "AWAITING_AUDIT".equals(e.auditStatus())) {
          byRx.put(
              e.rxId(),
              new RxAuditEntry(
                  e.id(),
                  e.rxId(),
                  e.orderId(),
                  e.pharmacyId(),
                  e.schedule(),
                  "OVERDUE_AUDIT",
                  e.auditDeadline(),
                  e.possibleDuplicate(),
                  e.possibleDuplicateRxId(),
                  e.verifiedBy(),
                  e.verifiedAt(),
                  e.flagReason(),
                  e.flagSeverity(),
                  e.flaggedBy(),
                  e.flaggedAt(),
                  e.notes(),
                  e.createdAt()));
          return 1;
        }
      }
      return 0;
    }

    @Override
    public Stats statistics(LocalDate from, LocalDate to) {
      return stats;
    }

    @Override
    public Optional<OrderContext> orderContext(UUID orderId) {
      return Optional.of(new OrderContext("ORD-1", "Pharm"));
    }

    @Override
    public Optional<String> pharmacyName(UUID pharmacyId) {
      return Optional.of("Pharm");
    }

    @Override
    public Optional<DispenseContext> dispenseContext(UUID rxId, UUID pharmacyId) {
      return Optional.ofNullable(dispense.get(rxId));
    }
  }
}
