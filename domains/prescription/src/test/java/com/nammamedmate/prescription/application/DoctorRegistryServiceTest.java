package com.nammamedmate.prescription.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.prescription.application.DoctorRegistryService.UpsertResult;
import com.nammamedmate.prescription.application.port.out.DoctorAutoFlagPort;
import com.nammamedmate.prescription.application.port.out.DoctorStore;
import com.nammamedmate.prescription.application.port.out.DoctorStore.Link;
import com.nammamedmate.prescription.application.port.out.DoctorStore.ListFilter;
import com.nammamedmate.prescription.application.port.out.DoctorStore.Page;
import com.nammamedmate.prescription.application.port.out.DoctorStore.ScheduleCounts;
import com.nammamedmate.prescription.application.port.out.NotificationDispatchPort;
import com.nammamedmate.prescription.application.port.out.PharmacyRxQueueStore;
import com.nammamedmate.prescription.application.port.out.RxAuditStore;
import com.nammamedmate.prescription.domain.DoctorRecord;
import com.nammamedmate.prescription.domain.PharmacyRxQueueEntry;
import com.nammamedmate.prescription.domain.RxAuditEntry;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class DoctorRegistryServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-24T09:00:00Z");
  private static final UUID ADMIN_ID = UUID.fromString("a1000001-0000-4000-8000-0000000000a1");
  private static final MedmatePrincipal COMPLIANCE =
      new MedmatePrincipal(ADMIN_ID, AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");
  private static final MedmatePrincipal OPS =
      new MedmatePrincipal(ADMIN_ID, AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");

  private FakeDoctorStore store;
  private FakeAuditStore audits;
  private PharmacyRxQueueStore queues;
  private NotificationDispatchPort notifications;
  private OutboxPublisher outbox;
  private DoctorAutoFlagPort autoFlags;
  private DoctorRegistryService service;

  @BeforeEach
  void setUp() {
    store = new FakeDoctorStore();
    audits = new FakeAuditStore();
    queues = mock(PharmacyRxQueueStore.class);
    notifications = mock(NotificationDispatchPort.class);
    outbox = mock(OutboxPublisher.class);
    autoFlags = mock(DoctorAutoFlagPort.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<DoctorAutoFlagPort> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(autoFlags);
    service =
        new DoctorRegistryService(
            store,
            audits,
            queues,
            notifications,
            outbox,
            provider,
            new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC)),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void ac_ocrCreatesUnverifiedAndIncrementsCount() {
    UUID rx1 = Ids.newId();
    UpsertResult r1 = service.upsertFromOcr(rx1, "Dr. Priya", "MH12345", "MBBS MD", "Endo");
    assertThat(r1.doctor().status()).isEqualTo("UNVERIFIED");
    assertThat(r1.doctor().prescriptionCount()).isEqualTo(1);
    assertThat(r1.doctor().source()).isEqualTo("OCR");

    UUID rx2 = Ids.newId();
    UpsertResult r2 = service.upsertFromOcr(rx2, "Dr. Priya Sharma", "MH12345", "MBBS MD", null);
    assertThat(r2.doctor().id()).isEqualTo(r1.doctor().id());
    assertThat(r2.doctor().prescriptionCount()).isEqualTo(2);
    assertThat(store.byReg.values()).hasSize(1);
  }

  @Test
  void ac_sameRegistrationNo_singleDoctorCountTwo() {
    service.upsertFromOcr(Ids.newId(), "Dr A", "KA1", "MBBS", null);
    service.upsertFromOcr(Ids.newId(), "Dr A", "KA1", "MBBS", null);
    assertThat(store.byId).hasSize(1);
    assertThat(store.byId.values().iterator().next().prescriptionCount()).isEqualTo(2);
  }

  @Test
  void ac_blacklistedDoctor_newRxAutoFlaggedBeforeReview() {
    UpsertResult created = service.upsertFromOcr(Ids.newId(), "Dr Bad", "BAD1", "MBBS", null);
    service.blacklist(COMPLIANCE, created.doctor().id(), "Fraudulent registration");

    UUID rxNew = Ids.newId();
    UUID pharmacy = Ids.newId();
    UpsertResult again = service.upsertFromOcr(rxNew, "Dr Bad", "BAD1", "MBBS", null);
    assertThat(again.doctor().blacklisted()).isTrue();
    assertThat(store.findLink(rxNew)).isPresent();
    assertThat(store.findLink(rxNew).orElseThrow().pendingBlacklistFlag()).isTrue();

    // enqueue path uses DoctorAutoFlagPort
    when(queues.findLatestByRxId(rxNew))
        .thenReturn(
            Optional.of(
                new PharmacyRxQueueEntry(
                    Ids.newId(),
                    rxNew,
                    pharmacy,
                    null,
                    NOW,
                    "PENDING_REVIEW",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    false,
                    null,
                    NOW,
                    NOW,
                    null)));
    service.processRetroactiveFlags(created.doctor().id(), NOW);
    verify(autoFlags).applyPendingFlags(rxNew, pharmacy);
  }

  @Test
  void ac_blacklist_returnsRetroactiveQueuedAndProcesses() {
    UpsertResult d = service.upsertFromOcr(Ids.newId(), "Dr X", "X1", "MBBS", null);
    UUID rx = store.links.keySet().iterator().next();
    RxAuditEntry awaiting =
        new RxAuditEntry(
            Ids.newId(),
            rx,
            null,
            Ids.newId(),
            "H1",
            "AWAITING_AUDIT",
            NOW.plusSeconds(3600),
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            NOW);
    audits.insert(awaiting);

    Map<String, Object> data =
        service.blacklist(COMPLIANCE, d.doctor().id(), "Reported fraudulent by council");
    assertThat(data.get("status")).isEqualTo("BLACKLISTED");
    assertThat(data.get("retroactive_flags_queued")).isEqualTo(1);
    assertThat(audits.findByRxId(rx).orElseThrow().auditStatus()).isEqualTo("FLAGGED");
    assertThat(audits.findByRxId(rx).orElseThrow().flagReason()).isEqualTo("BLACKLISTED_DOCTOR");
    assertThat(audits.findByRxId(rx).orElseThrow().flagSeverity()).isEqualTo("HIGH");
    verify(outbox).publish(any());
    verify(notifications).notifyComplianceDoctorBlacklisted(any(), any());
  }

  @Test
  void ac_blacklistAlreadyBlacklisted_409() {
    UpsertResult d = service.upsertFromOcr(Ids.newId(), "Dr X", "X2", "MBBS", null);
    service.blacklist(COMPLIANCE, d.doctor().id(), "First reason");
    assertThatThrownBy(() -> service.blacklist(COMPLIANCE, d.doctor().id(), "Again"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("DOCTOR_ALREADY_BLACKLISTED");
  }

  @Test
  void ac_unverifiedSortedByPrescriptionCountDesc() {
    UpsertResult low = service.upsertFromOcr(Ids.newId(), "Low", "L1", "MBBS", null);
    UpsertResult high = service.upsertFromOcr(Ids.newId(), "High", "H1", "MBBS", null);
    service.upsertFromOcr(Ids.newId(), "High", "H1", "MBBS", null);
    service.upsertFromOcr(Ids.newId(), "High", "H1", "MBBS", null);

    var result = service.listUnverified(COMPLIANCE, 1, 20, null);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> doctors = (List<Map<String, Object>>) result.data().get("doctors");
    assertThat(doctors).isNotEmpty();
    assertThat((Integer) doctors.get(0).get("prescription_count"))
        .isGreaterThanOrEqualTo(
            (Integer) doctors.get(doctors.size() - 1).get("prescription_count"));
    assertThat(doctors.get(0).get("id")).isEqualTo(high.doctor().id());
    assertThat(doctors.stream().map(m -> m.get("id"))).contains(low.doctor().id());
  }

  @Test
  void ac_unrecognisedQualification_nullAndFlagNote() {
    UUID rx = Ids.newId();
    UpsertResult r = service.upsertFromOcr(rx, "Dr Q", "Q1", "BPT", "Physio");
    assertThat(r.doctor().qualification()).isNull();
    assertThat(r.unrecognizedQualification()).isTrue();
    assertThat(store.findLink(rx).orElseThrow().unrecognizedQualification()).isTrue();
  }

  @Test
  void ac_opsCannotVerify_403() {
    UpsertResult d = service.upsertFromOcr(Ids.newId(), "Dr V", "V1", "MBBS", null);
    assertThatThrownBy(() -> service.verify(OPS, d.doctor().id(), true, "STATE_BOARD", "notes"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).httpStatus())
        .isEqualTo(403);
  }

  @Test
  void verifyAndListAndGet_happyPaths() {
    UpsertResult d = service.upsertFromOcr(Ids.newId(), "Dr P", "P1", "MBBS", "Cardio");
    Map<String, Object> verified =
        service.verify(COMPLIANCE, d.doctor().id(), true, "MANUAL", "ok");
    assertThat(verified.get("status")).isEqualTo("VERIFIED");

    var list = service.list(COMPLIANCE, "P1", null, "VERIFIED", 1, 20, "name", "asc");
    assertThat(list.data()).hasSize(1);
    Map<String, Object> detail = service.get(COMPLIANCE, d.doctor().id());
    assertThat(detail.get("registration_no")).isEqualTo("P1");
    assertThat(detail).containsKey("prescription_stats");
  }

  @Test
  void unknownRegistrationAndTeleconsultAndScheduleAlert() {
    UpsertResult unknown = service.upsertFromOcr(Ids.newId(), "Dr U", null, "MBBS", null);
    assertThat(unknown.doctor().registrationNo()).startsWith("UNKNOWN-");

    UUID rx = Ids.newId();
    DoctorRecord tele = service.upsertFromTeleconsult(rx, "Dr Tele", "NMC-1", "MBBS MD", "GP");
    assertThat(tele.status()).isEqualTo("VERIFIED");

    for (int i = 0; i < 51; i++) {
      store.insertScheduleEvent(Ids.newId(), tele.id(), rx, NOW.minusSeconds(i));
    }
    service.recordScheduledDrug(rx);
    verify(outbox).publish(any());
    verify(notifications).notifyComplianceDoctorScheduleAlert(any(), any(Long.class));
  }

  @Test
  void normalizeQualification_helpers() {
    assertThat(DoctorRegistryService.normalizeQualification("mbbs md")).isEqualTo("MBBS MD");
    assertThat(DoctorRegistryService.normalizeQualification("MD")).isEqualTo("MD");
    assertThat(DoctorRegistryService.normalizeQualification("BPT")).isNull();
    assertThat(DoctorRegistryService.normalizeRegistration("  x  ", Ids.newId())).isEqualTo("x");
  }

  @Test
  void notFoundAndValidation() {
    assertThatThrownBy(() -> service.get(COMPLIANCE, Ids.newId()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("DOCTOR_NOT_FOUND");
    UpsertResult d = service.upsertFromOcr(Ids.newId(), "Dr", "Z1", "MBBS", null);
    assertThatThrownBy(() -> service.verify(COMPLIANCE, d.doctor().id(), null, "MANUAL", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.blacklist(COMPLIANCE, d.doctor().id(), " "))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.list(COMPLIANCE, null, null, "NOPE", 1, 10, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  public static final class FakeDoctorStore implements DoctorStore {
    final Map<UUID, DoctorRecord> byId = new ConcurrentHashMap<>();
    final Map<String, DoctorRecord> byReg = new ConcurrentHashMap<>();
    final Map<UUID, Link> links = new ConcurrentHashMap<>();
    final List<Instant> scheduleEvents = new ArrayList<>();

    @Override
    public void insert(DoctorRecord doctor) {
      byId.put(doctor.id(), doctor);
      byReg.put(doctor.registrationNo(), doctor);
    }

    @Override
    public void update(DoctorRecord doctor) {
      byId.put(doctor.id(), doctor);
      byReg.put(doctor.registrationNo(), doctor);
    }

    @Override
    public Optional<DoctorRecord> findById(UUID id) {
      return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<DoctorRecord> findByRegistrationNo(String registrationNo) {
      return Optional.ofNullable(byReg.get(registrationNo));
    }

    @Override
    public Page list(ListFilter filter) {
      List<DoctorRecord> all =
          byId.values().stream()
              .filter(d -> d.deletedAt() == null)
              .filter(
                  d ->
                      filter.status() == null
                          || "ALL".equalsIgnoreCase(filter.status())
                          || filter.status().equalsIgnoreCase(d.status()))
              .sorted(Comparator.comparingInt(DoctorRecord::prescriptionCount).reversed())
              .collect(Collectors.toList());
      return new Page(all, all.size());
    }

    @Override
    public Page listUnverified(int page, int limit) {
      return list(
          new ListFilter(null, null, "UNVERIFIED", page, limit, "prescription_count", "desc"));
    }

    @Override
    public void linkPrescription(
        UUID rxId, UUID doctorId, boolean unrecognizedQualification, Instant createdAt) {
      Link prev = links.get(rxId);
      boolean unrec =
          unrecognizedQualification || (prev != null && prev.unrecognizedQualification());
      links.put(rxId, new Link(rxId, doctorId, unrec, prev != null && prev.pendingBlacklistFlag()));
    }

    @Override
    public Optional<Link> findLink(UUID rxId) {
      return Optional.ofNullable(links.get(rxId));
    }

    @Override
    public void markPendingBlacklist(UUID doctorId) {
      links.replaceAll(
          (rx, link) ->
              link.doctorId().equals(doctorId)
                  ? new Link(link.rxId(), link.doctorId(), link.unrecognizedQualification(), true)
                  : link);
    }

    @Override
    public List<UUID> listRxIdsForDoctor(UUID doctorId) {
      return links.values().stream()
          .filter(l -> l.doctorId().equals(doctorId))
          .map(Link::rxId)
          .toList();
    }

    @Override
    public int countRxForDoctor(UUID doctorId) {
      return listRxIdsForDoctor(doctorId).size();
    }

    @Override
    public void incrementPrescriptionCount(UUID doctorId, Instant updatedAt) {
      DoctorRecord d = byId.get(doctorId);
      if (d == null) {
        return;
      }
      DoctorRecord u =
          new DoctorRecord(
              d.id(),
              d.registrationNo(),
              d.name(),
              d.qualification(),
              d.specialty(),
              d.status(),
              d.source(),
              d.prescriptionCount() + 1,
              d.scheduledDrugCount(),
              d.verificationMethod(),
              d.verifiedBy(),
              d.verifiedAt(),
              d.verificationNotes(),
              d.blacklistReason(),
              d.blacklistedBy(),
              d.blacklistedAt(),
              d.createdAt(),
              updatedAt,
              d.deletedAt());
      update(u);
    }

    @Override
    public void incrementScheduledDrugCount(UUID doctorId, Instant updatedAt) {
      DoctorRecord d = byId.get(doctorId);
      if (d == null) {
        return;
      }
      update(
          new DoctorRecord(
              d.id(),
              d.registrationNo(),
              d.name(),
              d.qualification(),
              d.specialty(),
              d.status(),
              d.source(),
              d.prescriptionCount(),
              d.scheduledDrugCount() + 1,
              d.verificationMethod(),
              d.verifiedBy(),
              d.verifiedAt(),
              d.verificationNotes(),
              d.blacklistReason(),
              d.blacklistedBy(),
              d.blacklistedAt(),
              d.createdAt(),
              updatedAt,
              d.deletedAt()));
    }

    @Override
    public void insertScheduleEvent(UUID eventId, UUID doctorId, UUID rxId, Instant createdAt) {
      scheduleEvents.add(createdAt);
    }

    @Override
    public long countScheduleEventsSince(UUID doctorId, Instant since) {
      return scheduleEvents.stream().filter(t -> !t.isBefore(since)).count();
    }

    @Override
    public Map<String, Integer> prescriptionCategoryCounts(UUID doctorId) {
      return Map.of("Other", 1);
    }

    @Override
    public ScheduleCounts scheduleCounts(UUID doctorId) {
      return new ScheduleCounts(1, 2, 0);
    }

    @Override
    public long associatedOrdersCount(UUID doctorId) {
      return 0L;
    }
  }

  public static final class FakeAuditStore implements RxAuditStore {
    final Map<UUID, RxAuditEntry> byRx = new LinkedHashMap<>();

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
        Instant createdAt) {}

    @Override
    public List<Map<String, Object>> listActivity(UUID rxId) {
      return List.of();
    }

    @Override
    public ListPage list(ListFilter filter, Instant now) {
      return new ListPage(List.of(), 0, new Kpis(0, 0, 0, 0, 0));
    }

    @Override
    public List<ListRow> listAllForExport(ListFilter filter) {
      return List.of();
    }

    @Override
    public Optional<DuplicateMatch> findDuplicate(
        String patientName, String drugName, int quantity, Instant since, UUID excludeRxId) {
      return Optional.empty();
    }

    @Override
    public List<RxAuditEntry> findAwaitingPastDeadline(Instant now, int limit) {
      return List.of();
    }

    @Override
    public int markOverdue(UUID id, Instant now) {
      return 0;
    }

    @Override
    public Stats statistics(java.time.LocalDate from, java.time.LocalDate to) {
      return new Stats(Map.of(), 0, List.of(), List.of(), 0, 0, 0, 0);
    }

    @Override
    public Optional<OrderContext> orderContext(UUID orderId) {
      return Optional.empty();
    }

    @Override
    public Optional<String> pharmacyName(UUID pharmacyId) {
      return Optional.empty();
    }

    @Override
    public Optional<DispenseContext> dispenseContext(UUID rxId, UUID pharmacyId) {
      return Optional.empty();
    }
  }
}
