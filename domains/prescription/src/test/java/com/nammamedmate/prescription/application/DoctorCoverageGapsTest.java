package com.nammamedmate.prescription.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.kernel.storage.PresignedUrlService;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.prescription.adapter.out.persistence.JdbcDoctorCardAdapter;
import com.nammamedmate.prescription.adapter.out.persistence.JdbcDoctorStore;
import com.nammamedmate.prescription.application.DoctorRegistryServiceTest.FakeAuditStore;
import com.nammamedmate.prescription.application.DoctorRegistryServiceTest.FakeDoctorStore;
import com.nammamedmate.prescription.application.port.out.CatalogueSchedulePort;
import com.nammamedmate.prescription.application.port.out.ComplianceExportStore;
import com.nammamedmate.prescription.application.port.out.DoctorAutoFlagPort;
import com.nammamedmate.prescription.application.port.out.DoctorCardPort;
import com.nammamedmate.prescription.application.port.out.DoctorStore;
import com.nammamedmate.prescription.application.port.out.DoctorStore.Link;
import com.nammamedmate.prescription.application.port.out.DoctorStore.ListFilter;
import com.nammamedmate.prescription.application.port.out.NotificationDispatchPort;
import com.nammamedmate.prescription.application.port.out.PharmacyRxQueueStore;
import com.nammamedmate.prescription.application.port.out.PrescriptionStore;
import com.nammamedmate.prescription.application.port.out.RxAuditStore;
import com.nammamedmate.prescription.domain.DoctorRecord;
import com.nammamedmate.prescription.domain.PharmacyRxQueueEntry;
import com.nammamedmate.prescription.domain.PrescriptionRecord;
import com.nammamedmate.prescription.domain.RxAuditEntry;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class DoctorCoverageGapsTest {

  private static final Instant NOW = Instant.parse("2026-07-24T11:00:00Z");
  private static final MedmatePrincipal COMPLIANCE =
      new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");

  @Test
  void normalizeQualificationBranchesAndMutatorNullRole() {
    assertThat(DoctorRegistryService.normalizeQualification("mbbs md")).isEqualTo("MBBS MD");
    assertThat(DoctorRegistryService.normalizeQualification("MBBS MS")).isEqualTo("MBBS MS");
    assertThat(DoctorRegistryService.normalizeQualification("bams")).isEqualTo("BAMS");
    assertThat(DoctorRegistryService.normalizeQualification("MD")).isEqualTo("MD");
    assertThat(DoctorRegistryService.normalizeRegistration(null, Ids.newId()))
        .startsWith("UNKNOWN-");
    assertThat(DoctorRegistryService.normalizeRegistration("  ", Ids.newId()))
        .startsWith("UNKNOWN-");

    FakeDoctorStore store = new FakeDoctorStore();
    DoctorRegistryService service =
        new DoctorRegistryService(
            store,
            new FakeAuditStore(),
            mock(PharmacyRxQueueStore.class),
            mock(NotificationDispatchPort.class),
            mock(OutboxPublisher.class),
            mock(ObjectProvider.class),
            new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC)),
            Clock.fixed(NOW, ZoneOffset.UTC));
    assertThatThrownBy(() -> service.listUnverified(null, 1, 20, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.verify(null, Ids.newId(), true, "MANUAL", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    service.upsertFromOcr(Ids.newId(), "Dr", "Q-NULL", null, null);
    service.upsertFromOcr(Ids.newId(), "Dr", "Q-BLANK", "   ", null);
    service.upsertFromOcr(Ids.newId(), "Dr", "Q-BAD", "BPT", null);
    var d = service.upsertFromOcr(Ids.newId(), "Dr", "G1", "MBBS", null);
    service.verify(COMPLIANCE, d.doctor().id(), true, "state_board", null);
    assertThatThrownBy(() -> service.verify(COMPLIANCE, d.doctor().id(), true, "  ", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    service.list(COMPLIANCE, null, null, "ALL", 0, 0, "", "");
    service.listUnverified(COMPLIANCE, null, null, "name");
    service.listUnverified(COMPLIANCE, 0, 0, "name");

    MedmatePrincipal superAdmin =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    var d2 = service.upsertFromOcr(Ids.newId(), "Dr2", "G2", "BPT", null);
    assertThat(d2.unrecognizedQualification()).isTrue();
    service.verify(superAdmin, d2.doctor().id(), true, "MANUAL", "ok");
    assertThatThrownBy(() -> service.blacklist(COMPLIANCE, d2.doctor().id(), null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void teleconsultBlankNameAndListFilters() {
    FakeDoctorStore store = new FakeDoctorStore();
    @SuppressWarnings("unchecked")
    ObjectProvider<DoctorAutoFlagPort> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(null);
    DoctorRegistryService service =
        new DoctorRegistryService(
            store,
            new FakeAuditStore(),
            mock(PharmacyRxQueueStore.class),
            mock(NotificationDispatchPort.class),
            mock(OutboxPublisher.class),
            provider,
            new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC)),
            Clock.fixed(NOW, ZoneOffset.UTC));
    service.upsertFromOcr(Ids.newId(), null, "OCR-NULL", "MBBS", null);
    service.upsertFromTeleconsult(Ids.newId(), "   ", "TEL-BLANK", "MBBS", "GP");
    DoctorRecord tele =
        service.upsertFromTeleconsult(Ids.newId(), "Dr Tele", "TEL-9", "MBBS", "GP");
    service.upsertFromTeleconsult(Ids.newId(), null, "TEL-9", null, null);
    assertThat(store.findById(tele.id()).orElseThrow().verificationMethod()).isEqualTo("MANUAL");
    service.list(COMPLIANCE, "OCR", "GP", "  ", 1, 5, "scheduled_drug_count", "asc");

    // FLAGGED but not BLACKLISTED_DOCTOR → still re-flag; autoFlags null
    UUID rx =
        store.links.keySet().stream()
            .filter(id -> store.links.get(id).doctorId().equals(tele.id()))
            .findFirst()
            .orElseThrow();
    FakeAuditStore audits = new FakeAuditStore();
    audits.insert(
        new RxAuditEntry(
            Ids.newId(),
            rx,
            null,
            Ids.newId(),
            "H",
            "FLAGGED",
            NOW,
            false,
            null,
            null,
            null,
            "OTHER",
            "LOW",
            null,
            NOW,
            null,
            NOW));
    DoctorRegistryService withAudits =
        new DoctorRegistryService(
            store,
            audits,
            mock(PharmacyRxQueueStore.class),
            mock(NotificationDispatchPort.class),
            mock(OutboxPublisher.class),
            provider,
            new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC)),
            Clock.fixed(NOW, ZoneOffset.UTC));
    withAudits.blacklist(COMPLIANCE, tele.id(), "ban after teleconsult path coverage");
    assertThat(audits.findByRxId(rx).orElseThrow().flagReason()).isEqualTo("BLACKLISTED_DOCTOR");

    // port == null path: linked rx with queue, no audit yet
    UUID rxNoAudit = Ids.newId();
    store.linkPrescription(rxNoAudit, tele.id(), false, NOW);
    PharmacyRxQueueStore queues = mock(PharmacyRxQueueStore.class);
    when(queues.findLatestByRxId(rxNoAudit))
        .thenReturn(
            Optional.of(
                new PharmacyRxQueueEntry(
                    Ids.newId(),
                    rxNoAudit,
                    Ids.newId(),
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
    DoctorRegistryService nullPortSvc =
        new DoctorRegistryService(
            store,
            new FakeAuditStore(),
            queues,
            mock(NotificationDispatchPort.class),
            mock(OutboxPublisher.class),
            provider,
            new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC)),
            Clock.fixed(NOW, ZoneOffset.UTC));
    nullPortSvc.processRetroactiveFlags(tele.id(), NOW);
  }

  @Test
  @SuppressWarnings("unchecked")
  void jdbcCategorizeAllBucketsAndListFilters() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    JdbcDoctorStore store = new JdbcDoctorStore(jdbc);
    UUID id = Ids.newId();
    java.util.HashMap<String, Object> nullName = new java.util.HashMap<>();
    nullName.put("drug_name", null);
    when(jdbc.queryForList(anyString(), eq(id)))
        .thenReturn(
            List.of(
                Map.of("drug_name", "Insulin Glargine"),
                Map.of("drug_name", "Glimepiride 1mg"),
                Map.of("drug_name", "Azithromycin"),
                Map.of("drug_name", "Ciprofloxacin"),
                Map.of("drug_name", "Cefixime"),
                Map.of("drug_name", "Clonazepam"),
                Map.of("drug_name", "Diazepam"),
                nullName));
    assertThat(store.prescriptionCategoryCounts(id)).isNotEmpty();

    when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getString("registration_no")).thenReturn("MH");
    when(rs.getString("name")).thenReturn("Dr");
    when(rs.getString("qualification")).thenReturn(null);
    when(rs.getString("specialty")).thenReturn(null);
    when(rs.getString("status")).thenReturn("UNVERIFIED");
    when(rs.getString("source")).thenReturn("OCR");
    when(rs.getInt("prescription_count")).thenReturn(0);
    when(rs.getInt("scheduled_drug_count")).thenReturn(0);
    when(rs.getString("verification_method")).thenReturn(null);
    when(rs.getObject("verified_by")).thenReturn(null);
    when(rs.getTimestamp("verified_at")).thenReturn(null);
    when(rs.getString("verification_notes")).thenReturn(null);
    when(rs.getString("blacklist_reason")).thenReturn(null);
    when(rs.getObject("blacklisted_by")).thenReturn(null);
    when(rs.getTimestamp("blacklisted_at")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(NOW));
    when(rs.getTimestamp("deleted_at")).thenReturn(Timestamp.from(NOW));
    when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0)));
    assertThat(store.list(new ListFilter(" ", " ", "ALL", 1, 10, "name", "DESC")).items())
        .hasSize(1);
    assertThat(store.list(new ListFilter(null, null, "  ", 1, 10, "name", "asc")).items())
        .hasSize(1);
    assertThat(store.list(new ListFilter(null, null, "UNVERIFIED", 1, 10, "name", "asc")).items())
        .hasSize(1);

    DoctorStore fake =
        new DoctorStore() {
          @Override
          public void insert(DoctorRecord doctor) {}

          @Override
          public void update(DoctorRecord doctor) {}

          @Override
          public Optional<DoctorRecord> findById(UUID doctorId) {
            return Optional.of(
                new DoctorRecord(
                    doctorId,
                    "R",
                    "Dr",
                    "MBBS",
                    null,
                    "VERIFIED",
                    "OCR",
                    1,
                    0,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    NOW,
                    NOW,
                    null));
          }

          @Override
          public Optional<DoctorRecord> findByRegistrationNo(String registrationNo) {
            return Optional.empty();
          }

          @Override
          public Page list(ListFilter filter) {
            return new Page(List.of(), 0);
          }

          @Override
          public Page listUnverified(int page, int limit) {
            return new Page(List.of(), 0);
          }

          @Override
          public void linkPrescription(
              UUID rxId, UUID doctorId, boolean unrecognizedQualification, Instant createdAt) {}

          @Override
          public Optional<Link> findLink(UUID rxId) {
            return Optional.empty();
          }

          @Override
          public void markPendingBlacklist(UUID doctorId) {}

          @Override
          public List<UUID> listRxIdsForDoctor(UUID doctorId) {
            return List.of();
          }

          @Override
          public int countRxForDoctor(UUID doctorId) {
            return 0;
          }

          @Override
          public void incrementPrescriptionCount(UUID doctorId, Instant updatedAt) {}

          @Override
          public void incrementScheduledDrugCount(UUID doctorId, Instant updatedAt) {}

          @Override
          public void insertScheduleEvent(
              UUID eventId, UUID doctorId, UUID rxId, Instant createdAt) {}

          @Override
          public long countScheduleEventsSince(UUID doctorId, Instant since) {
            return 0;
          }

          @Override
          public Map<String, Integer> prescriptionCategoryCounts(UUID doctorId) {
            return Map.of();
          }

          @Override
          public ScheduleCounts scheduleCounts(UUID doctorId) {
            return new ScheduleCounts(0, 0, 0);
          }

          @Override
          public long associatedOrdersCount(UUID doctorId) {
            return 0;
          }
        };
    JdbcDoctorCardAdapter card = new JdbcDoctorCardAdapter(fake);
    assertThat(card.findForPrescription(Ids.newId(), "E_PRESCRIPTION", "Dr X", null)).isPresent();
    assertThat(card.findForPrescription(Ids.newId(), "E_PRESCRIPTION", "  ", Ids.newId()))
        .isPresent();
    assertThat(card.findForPrescription(Ids.newId(), "UPLOADED", "  ", null)).isEmpty();
  }

  @Test
  void auditGetAndAutoFlagRemainingBranches() {
    ConcurrentHashMap<UUID, RxAuditEntry> audits = new ConcurrentHashMap<>();
    DoctorStore doctors = mock(DoctorStore.class);
    PrescriptionStore prescriptions = mock(PrescriptionStore.class);
    RxAuditStore auditStore = mock(RxAuditStore.class);
    when(auditStore.findByRxId(any()))
        .thenAnswer(inv -> Optional.ofNullable(audits.get(inv.getArgument(0))));
    org.mockito.Mockito.doAnswer(
            inv -> {
              audits.put(((RxAuditEntry) inv.getArgument(0)).rxId(), inv.getArgument(0));
              return null;
            })
        .when(auditStore)
        .insert(any());
    org.mockito.Mockito.doAnswer(
            inv -> {
              audits.put(((RxAuditEntry) inv.getArgument(0)).rxId(), inv.getArgument(0));
              return null;
            })
        .when(auditStore)
        .update(any());
    when(auditStore.dispenseContext(any(), any())).thenReturn(Optional.empty());
    when(auditStore.pharmacyName(any())).thenReturn(Optional.of("Pharm"));
    when(auditStore.listActivity(any())).thenReturn(List.of());

    RxComplianceAuditService service =
        new RxComplianceAuditService(
            auditStore,
            prescriptions,
            mock(CatalogueSchedulePort.class),
            mock(ComplianceExportStore.class),
            mock(NotificationDispatchPort.class),
            (a, b, c, d) -> Optional.of(new DoctorCardPort.DoctorCard(c, "MBBS", "MH", true)),
            doctors,
            new PresignedUrlService() {
              @Override
              public PresignedUrl createPutUrl(String key, String contentType, Duration ttl) {
                return new PresignedUrl("p", key, ttl);
              }

              @Override
              public PresignedUrl createGetUrl(String key, Duration ttl) {
                return new PresignedUrl("g", key, ttl);
              }
            },
            new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC)),
            Clock.fixed(NOW, ZoneOffset.UTC));

    UUID rx = Ids.newId();
    UUID pharmacy = Ids.newId();
    UUID doctorId = Ids.newId();
    PrescriptionRecord rxRec =
        new PrescriptionRecord(
            rx,
            Ids.newId(),
            "UPLOADED",
            "DISPENSED",
            "k",
            1,
            "image/jpeg",
            "P",
            null,
            "Dr",
            LocalDate.now(),
            "UPLOAD",
            List.of(),
            null,
            null,
            NOW.plusSeconds(10),
            null,
            NOW,
            NOW,
            null);
    when(prescriptions.findById(rx)).thenReturn(Optional.of(rxRec));
    when(doctors.findLink(rx)).thenReturn(Optional.of(new Link(rx, doctorId, true, true)));
    when(doctors.findById(doctorId))
        .thenReturn(
            Optional.of(
                new DoctorRecord(
                    doctorId,
                    "B",
                    "Dr",
                    null,
                    "Cardio",
                    "BLACKLISTED",
                    "OCR",
                    1,
                    0,
                    null,
                    null,
                    null,
                    null,
                    "r",
                    Ids.newId(),
                    NOW,
                    NOW,
                    NOW,
                    null)));

    RxAuditEntry awaiting =
        new RxAuditEntry(
            Ids.newId(),
            rx,
            null,
            pharmacy,
            "H",
            "AWAITING_AUDIT",
            NOW.plusSeconds(100),
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
    audits.put(rx, awaiting);
    Map<String, Object> detail = service.get(COMPLIANCE, rx);
    assertThat(detail.get("doctor")).isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> doctor = (Map<String, Object>) detail.get("doctor");
    assertThat(doctor.get("blacklisted")).isEqualTo(true);

    service.applyPendingFlags(rx, pharmacy);
    assertThat(audits.get(rx).flagReason()).isEqualTo("BLACKLISTED_DOCTOR");

    // doctor missing on link
    UUID rx2 = Ids.newId();
    when(doctors.findLink(rx2)).thenReturn(Optional.of(new Link(rx2, doctorId, true, false)));
    when(doctors.findById(doctorId)).thenReturn(Optional.empty());
    when(prescriptions.findById(rx2)).thenReturn(Optional.empty());
    service.applyPendingFlags(rx2, pharmacy);
    assertThat(audits.containsKey(rx2)).isFalse();

    // already flagged with unrecognised — skip
    UUID rx3 = Ids.newId();
    audits.put(
        rx3,
        new RxAuditEntry(
            Ids.newId(),
            rx3,
            null,
            pharmacy,
            "NONE",
            "FLAGGED",
            NOW,
            false,
            null,
            null,
            null,
            "UNRECOGNISED_QUALIFICATION",
            "MEDIUM",
            null,
            NOW,
            "UNRECOGNISED_QUALIFICATION",
            NOW));
    when(doctors.findLink(rx3)).thenReturn(Optional.of(new Link(rx3, doctorId, true, false)));
    when(doctors.findById(doctorId))
        .thenReturn(
            Optional.of(
                new DoctorRecord(
                    doctorId,
                    "Q",
                    "Dr",
                    null,
                    null,
                    "UNVERIFIED",
                    "OCR",
                    1,
                    0,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    NOW,
                    NOW,
                    null)));
    service.applyPendingFlags(rx3, pharmacy);

    // doctorStore null short-circuit
    RxComplianceAuditService noDoctors =
        new RxComplianceAuditService(
            auditStore,
            prescriptions,
            mock(CatalogueSchedulePort.class),
            mock(ComplianceExportStore.class),
            mock(NotificationDispatchPort.class),
            (a, b, c, d) -> Optional.empty(),
            new PresignedUrlService() {
              @Override
              public PresignedUrl createPutUrl(String key, String contentType, Duration ttl) {
                return new PresignedUrl("p", key, ttl);
              }

              @Override
              public PresignedUrl createGetUrl(String key, Duration ttl) {
                return new PresignedUrl("g", key, ttl);
              }
            },
            new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC)),
            Clock.fixed(NOW, ZoneOffset.UTC));
    noDoctors.applyPendingFlags(rx, pharmacy);

    // createFromDispense with linked clean doctor (no auto-flag)
    UUID rx4 = Ids.newId();
    when(doctors.findLink(rx4)).thenReturn(Optional.of(new Link(rx4, doctorId, false, false)));
    when(doctors.findById(doctorId))
        .thenReturn(
            Optional.of(
                new DoctorRecord(
                    doctorId,
                    "OK",
                    "Dr",
                    "MBBS",
                    null,
                    "VERIFIED",
                    "OCR",
                    1,
                    0,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    NOW,
                    NOW,
                    null)));
    PrescriptionRecord okRx =
        new PrescriptionRecord(
            rx4,
            Ids.newId(),
            "UPLOADED",
            "DISPENSED",
            "k",
            1,
            "image/jpeg",
            "P",
            null,
            "Dr",
            LocalDate.now(),
            "UPLOAD",
            List.of(new PrescriptionRecord.MedicineExtracted("Metformin", "10", "1-0-0", "H")),
            null,
            null,
            NOW.plusSeconds(10),
            null,
            NOW,
            NOW,
            null);
    assertThat(service.createFromDispense(rx4, null, pharmacy, List.of(), okRx, NOW))
        .isPresent()
        .get()
        .extracting(RxAuditEntry::auditStatus)
        .isEqualTo("AWAITING_AUDIT");

    // applyDoctorAutoFlags when link missing
    UUID rx5 = Ids.newId();
    when(doctors.findLink(rx5)).thenReturn(Optional.empty());
    PrescriptionRecord plain =
        new PrescriptionRecord(
            rx5,
            Ids.newId(),
            "UPLOADED",
            "DISPENSED",
            "k",
            1,
            "image/jpeg",
            "P",
            null,
            "Dr",
            LocalDate.now(),
            "UPLOAD",
            List.of(new PrescriptionRecord.MedicineExtracted("Metformin", "10", "1-0-0", "H")),
            null,
            null,
            NOW.plusSeconds(10),
            null,
            NOW,
            NOW,
            null);
    assertThat(service.createFromDispense(rx5, null, pharmacy, List.of(), plain, NOW)).isPresent();

    // pending blacklist flag without doctor blacklisted status
    UUID rx6 = Ids.newId();
    when(doctors.findLink(rx6)).thenReturn(Optional.of(new Link(rx6, doctorId, false, true)));
    when(doctors.findById(doctorId))
        .thenReturn(
            Optional.of(
                new DoctorRecord(
                    doctorId,
                    "P",
                    "Dr",
                    "MBBS",
                    null,
                    "VERIFIED",
                    "OCR",
                    1,
                    0,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    NOW,
                    NOW,
                    null)));
    when(prescriptions.findById(rx6)).thenReturn(Optional.empty());
    service.applyPendingFlags(rx6, pharmacy);
    assertThat(audits.get(rx6).flagReason()).isEqualTo("BLACKLISTED_DOCTOR");

    // applyDoctorAutoFlags: doctor absent + pending; notes from entry when not unrecognized
    UUID rx7 = Ids.newId();
    when(doctors.findLink(rx7)).thenReturn(Optional.of(new Link(rx7, doctorId, false, true)));
    when(doctors.findById(doctorId)).thenReturn(Optional.empty());
    PrescriptionRecord rx7rec =
        new PrescriptionRecord(
            rx7,
            Ids.newId(),
            "UPLOADED",
            "DISPENSED",
            "k",
            1,
            "image/jpeg",
            "P",
            null,
            "Dr",
            LocalDate.now(),
            "UPLOAD",
            List.of(new PrescriptionRecord.MedicineExtracted("Metformin", "10", "1-0-0", "H")),
            null,
            null,
            NOW.plusSeconds(10),
            null,
            NOW,
            NOW,
            null);
    assertThat(service.createFromDispense(rx7, null, pharmacy, List.of(), rx7rec, NOW))
        .isPresent()
        .get()
        .extracting(RxAuditEntry::flagReason)
        .isEqualTo("BLACKLISTED_DOCTOR");

    // get() with doctorStore but no link
    UUID rx8 = Ids.newId();
    audits.put(
        rx8,
        new RxAuditEntry(
            Ids.newId(),
            rx8,
            null,
            pharmacy,
            "H",
            "AWAITING_AUDIT",
            NOW.plusSeconds(10),
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            NOW));
    when(prescriptions.findById(rx8))
        .thenReturn(
            Optional.of(
                new PrescriptionRecord(
                    rx8,
                    Ids.newId(),
                    "UPLOADED",
                    "DISPENSED",
                    "k",
                    1,
                    "image/jpeg",
                    "P",
                    null,
                    "Dr",
                    LocalDate.now(),
                    "UPLOAD",
                    List.of(),
                    null,
                    null,
                    NOW.plusSeconds(10),
                    null,
                    NOW,
                    NOW,
                    null)));
    when(doctors.findLink(rx8)).thenReturn(Optional.empty());
    assertThat(service.get(COMPLIANCE, rx8).get("doctor")).isInstanceOf(Map.class);

    // already FLAGGED BLACKLISTED_DOCTOR → skip re-flag
    UUID rx9 = Ids.newId();
    audits.put(
        rx9,
        new RxAuditEntry(
            Ids.newId(),
            rx9,
            null,
            pharmacy,
            "H",
            "FLAGGED",
            NOW,
            false,
            null,
            null,
            null,
            "BLACKLISTED_DOCTOR",
            "HIGH",
            null,
            NOW,
            null,
            NOW));
    when(doctors.findLink(rx9)).thenReturn(Optional.of(new Link(rx9, doctorId, false, true)));
    when(doctors.findById(doctorId))
        .thenReturn(
            Optional.of(
                new DoctorRecord(
                    doctorId,
                    "B",
                    "Dr",
                    "MBBS",
                    null,
                    "BLACKLISTED",
                    "OCR",
                    1,
                    0,
                    null,
                    null,
                    null,
                    null,
                    "r",
                    Ids.newId(),
                    NOW,
                    NOW,
                    NOW,
                    null)));
    service.applyPendingFlags(rx9, pharmacy);
    assertThat(audits.get(rx9).flagReason()).isEqualTo("BLACKLISTED_DOCTOR");

    // FLAGGED with unrelated reason should be upgraded
    UUID rx10 = Ids.newId();
    audits.put(
        rx10,
        new RxAuditEntry(
            Ids.newId(),
            rx10,
            null,
            pharmacy,
            "H",
            "FLAGGED",
            NOW,
            false,
            null,
            null,
            null,
            "OTHER_REASON",
            "LOW",
            null,
            NOW,
            "keep-notes",
            NOW));
    when(doctors.findLink(rx10)).thenReturn(Optional.of(new Link(rx10, doctorId, false, true)));
    service.applyPendingFlags(rx10, pharmacy);
    assertThat(audits.get(rx10).flagReason()).isEqualTo("BLACKLISTED_DOCTOR");
    assertThat(audits.get(rx10).notes()).isEqualTo("keep-notes");
  }
}
