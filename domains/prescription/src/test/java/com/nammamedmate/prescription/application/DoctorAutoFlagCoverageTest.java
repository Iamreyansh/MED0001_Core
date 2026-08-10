package com.nammamedmate.prescription.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.kernel.storage.PresignedUrlService;
import com.nammamedmate.prescription.application.port.out.CatalogueSchedulePort;
import com.nammamedmate.prescription.application.port.out.ComplianceExportStore;
import com.nammamedmate.prescription.application.port.out.DoctorCardPort;
import com.nammamedmate.prescription.application.port.out.DoctorStore;
import com.nammamedmate.prescription.application.port.out.DoctorStore.Link;
import com.nammamedmate.prescription.application.port.out.NotificationDispatchPort;
import com.nammamedmate.prescription.application.port.out.PrescriptionStore;
import com.nammamedmate.prescription.application.port.out.RxAuditStore;
import com.nammamedmate.prescription.domain.DoctorRecord;
import com.nammamedmate.prescription.domain.PrescriptionRecord;
import com.nammamedmate.prescription.domain.RxAuditEntry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DoctorAutoFlagCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");

  private final ConcurrentHashMap<UUID, RxAuditEntry> audits = new ConcurrentHashMap<>();
  private DoctorStore doctors;
  private PrescriptionStore prescriptions;
  private RxComplianceAuditService service;

  @BeforeEach
  void setUp() {
    doctors = mock(DoctorStore.class);
    prescriptions = mock(PrescriptionStore.class);
    RxAuditStore auditStore = mock(RxAuditStore.class);
    when(auditStore.findByRxId(any()))
        .thenAnswer(inv -> Optional.ofNullable(audits.get(inv.getArgument(0))));
    org.mockito.Mockito.doAnswer(
            inv -> {
              RxAuditEntry e = inv.getArgument(0);
              audits.put(e.rxId(), e);
              return null;
            })
        .when(auditStore)
        .insert(any());
    org.mockito.Mockito.doAnswer(
            inv -> {
              RxAuditEntry e = inv.getArgument(0);
              audits.put(e.rxId(), e);
              return null;
            })
        .when(auditStore)
        .update(any());

    PresignedUrlService presigner =
        new PresignedUrlService() {
          @Override
          public PresignedUrl createPutUrl(String key, String contentType, Duration ttl) {
            return new PresignedUrl("put", key, ttl);
          }

          @Override
          public PresignedUrl createGetUrl(String key, Duration ttl) {
            return new PresignedUrl("get", key, ttl);
          }
        };

    service =
        new RxComplianceAuditService(
            auditStore,
            prescriptions,
            mock(CatalogueSchedulePort.class),
            mock(ComplianceExportStore.class),
            mock(NotificationDispatchPort.class),
            (rxId, type, name, tele) ->
                Optional.of(new DoctorCardPort.DoctorCard(name, "MBBS", "MH1", false)),
            doctors,
            presigner,
            new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC)),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void applyPendingFlags_blacklistCreatesFlaggedAudit() {
    UUID rx = Ids.newId();
    UUID pharmacy = Ids.newId();
    UUID doctorId = Ids.newId();
    when(doctors.findLink(rx)).thenReturn(Optional.of(new Link(rx, doctorId, false, true)));
    when(doctors.findById(doctorId))
        .thenReturn(
            Optional.of(
                new DoctorRecord(
                    doctorId,
                    "BAD",
                    "Dr Bad",
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
                    "fraud",
                    Ids.newId(),
                    NOW,
                    NOW,
                    NOW,
                    null)));
    when(prescriptions.findById(rx))
        .thenReturn(
            Optional.of(
                new PrescriptionRecord(
                    rx,
                    Ids.newId(),
                    "UPLOADED",
                    "UPLOADED",
                    "k",
                    10,
                    "image/jpeg",
                    "Pat",
                    null,
                    "Dr Bad",
                    LocalDate.now(),
                    "UPLOAD",
                    List.of(
                        new PrescriptionRecord.MedicineExtracted(
                            "Alprazolam H1", "10", "1-0-0", "H1")),
                    null,
                    null,
                    NOW.plusSeconds(1000),
                    null,
                    NOW,
                    NOW,
                    null)));

    service.applyPendingFlags(rx, pharmacy);
    assertThat(audits.get(rx).auditStatus()).isEqualTo("FLAGGED");
    assertThat(audits.get(rx).flagReason()).isEqualTo("BLACKLISTED_DOCTOR");
    assertThat(audits.get(rx).flagSeverity()).isEqualTo("HIGH");
  }

  @Test
  void applyPendingFlags_unrecognisedOnlyAndExistingUpdate() {
    UUID rx = Ids.newId();
    UUID pharmacy = Ids.newId();
    UUID doctorId = Ids.newId();
    when(doctors.findLink(rx)).thenReturn(Optional.of(new Link(rx, doctorId, true, false)));
    when(doctors.findById(doctorId))
        .thenReturn(
            Optional.of(
                new DoctorRecord(
                    doctorId,
                    "Q1",
                    "Dr Q",
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
    when(prescriptions.findById(rx)).thenReturn(Optional.empty());

    service.applyPendingFlags(null, pharmacy);
    service.applyPendingFlags(rx, null);
    service.applyPendingFlags(rx, pharmacy);
    assertThat(audits.get(rx).flagReason()).isEqualTo("UNRECOGNISED_QUALIFICATION");

    // existing entry path
    service.applyPendingFlags(rx, pharmacy);
    assertThat(audits.get(rx).auditStatus()).isEqualTo("FLAGGED");
  }

  @Test
  void applyPendingFlags_noopWhenNoFlags() {
    UUID rx = Ids.newId();
    when(doctors.findLink(rx)).thenReturn(Optional.empty());
    service.applyPendingFlags(rx, Ids.newId());
    assertThat(audits).isEmpty();

    UUID doctorId = Ids.newId();
    when(doctors.findLink(rx)).thenReturn(Optional.of(new Link(rx, doctorId, false, false)));
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
    service.applyPendingFlags(rx, Ids.newId());
    assertThat(audits).isEmpty();
  }

  @Test
  void createFromDispense_appliesAutoFlags() {
    UUID rx = Ids.newId();
    UUID pharmacy = Ids.newId();
    UUID doctorId = Ids.newId();
    when(doctors.findLink(rx)).thenReturn(Optional.of(new Link(rx, doctorId, true, false)));
    when(doctors.findById(doctorId))
        .thenReturn(
            Optional.of(
                new DoctorRecord(
                    doctorId,
                    "Q2",
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
    PrescriptionRecord prescription =
        new PrescriptionRecord(
            rx,
            Ids.newId(),
            "UPLOADED",
            "DISPENSED",
            "k",
            10,
            "image/jpeg",
            "Pat",
            null,
            "Dr",
            LocalDate.now(),
            "UPLOAD",
            List.of(new PrescriptionRecord.MedicineExtracted("Metformin", "10", "1-0-0", "H")),
            null,
            null,
            NOW.plusSeconds(1000),
            null,
            NOW,
            NOW,
            null);
    Optional<RxAuditEntry> created =
        service.createFromDispense(rx, null, pharmacy, List.of(), prescription, NOW);
    assertThat(created).isPresent();
    assertThat(created.orElseThrow().auditStatus()).isEqualTo("FLAGGED");
  }
}
