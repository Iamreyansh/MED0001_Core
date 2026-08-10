package com.nammamedmate.prescription.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.prescription.application.DoctorRegistryServiceTest.FakeAuditStore;
import com.nammamedmate.prescription.application.DoctorRegistryServiceTest.FakeDoctorStore;
import com.nammamedmate.prescription.application.port.out.DoctorAutoFlagPort;
import com.nammamedmate.prescription.application.port.out.DoctorStore;
import com.nammamedmate.prescription.application.port.out.NotificationDispatchPort;
import com.nammamedmate.prescription.application.port.out.PharmacyRxQueueStore;
import com.nammamedmate.prescription.domain.DoctorRecord;
import com.nammamedmate.prescription.domain.PharmacyRxQueueEntry;
import com.nammamedmate.prescription.domain.RxAuditEntry;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class DoctorRegistryCoverageExtraTest {

  private static final Instant NOW = Instant.parse("2026-07-24T09:00:00Z");
  private static final MedmatePrincipal COMPLIANCE =
      new MedmatePrincipal(
          UUID.fromString("a1000001-0000-4000-8000-0000000000a1"),
          AuthRole.ADMIN_COMPLIANCE,
          null,
          TokenScope.FULL,
          "j");
  private static final MedmatePrincipal SUPPORT =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPPORT, null, TokenScope.FULL, "j");

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
  void upsertBranches_blankNameSpecialtyAndBlacklistedExisting() {
    var first = service.upsertFromOcr(Ids.newId(), "  ", "  R1  ", "  mbbs  ", "  ");
    assertThat(first.doctor().name()).isEqualTo("Unknown Doctor");
    assertThat(first.doctor().qualification()).isEqualTo("MBBS");

    service.blacklist(COMPLIANCE, first.doctor().id(), "bad actor enough chars");
    var again = service.upsertFromOcr(Ids.newId(), "Dr Renamed", "R1", null, "Neuro");
    assertThat(again.doctor().blacklisted()).isTrue();
    assertThat(again.doctor().name()).isEqualTo("Dr Renamed");
  }

  @Test
  void teleconsultUpsert_existingAndNewAndBlacklisted() {
    DoctorRecord created = service.upsertFromTeleconsult(Ids.newId(), null, "T1", "MBBS MD", "GP");
    assertThat(created.name()).isEqualTo("Teleconsult Doctor");
    assertThat(created.status()).isEqualTo("VERIFIED");

    DoctorRecord again = service.upsertFromTeleconsult(Ids.newId(), "Dr Tele", "T1", null, null);
    assertThat(again.id()).isEqualTo(created.id());
    assertThat(again.prescriptionCount()).isEqualTo(2);

    service.blacklist(COMPLIANCE, created.id(), "ban teleconsult doctor permanently");
    DoctorRecord afterBan =
        service.upsertFromTeleconsult(Ids.newId(), "Dr Tele", "T1", "MBBS", "GP");
    assertThat(afterBan.blacklisted()).isTrue();
  }

  @Test
  void recordScheduledDrug_noLinkAndBelowThreshold() {
    service.recordScheduledDrug(Ids.newId());
    verify(outbox, never()).publish(any());

    var d = service.upsertFromOcr(Ids.newId(), "Dr", "S1", "MBBS", null);
    UUID rx = store.links.keySet().iterator().next();
    service.recordScheduledDrug(rx);
    assertThat(store.findById(d.doctor().id()).orElseThrow().scheduledDrugCount()).isEqualTo(1);
    verify(notifications, never()).notifyComplianceDoctorScheduleAlert(any(), any(Long.class));
  }

  @Test
  void listDefaultsAndSupportRead_opsCannotUnverified() {
    service.upsertFromOcr(Ids.newId(), "Dr", "L1", "MBBS", null);
    assertThat(service.list(SUPPORT, null, null, null, null, null, null, null).data()).hasSize(1);
    assertThat(new DoctorRegistryService.ListResult(null, null).data()).isEmpty();
    assertThat(new DoctorRegistryService.UnverifiedResult(null, null).data()).isEmpty();
    assertThat(new DoctorStore.Page(null, 0).items()).isEmpty();

    assertThatThrownBy(() -> service.listUnverified(SUPPORT, null, null, null))
        .extracting(ex -> ((AppException) ex).httpStatus())
        .isEqualTo(403);

    MedmatePrincipal pharmacy =
        new MedmatePrincipal(Ids.newId(), AuthRole.PHARMACY_OWNER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.list(pharmacy, null, null, null, 1, 20, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.list(null, null, null, null, 1, 20, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void verifyRejectedAndBlacklistedAndValidation() {
    var d = service.upsertFromOcr(Ids.newId(), "Dr", "V9", "MBBS", null);
    assertThat(
            service
                .verify(COMPLIANCE, d.doctor().id(), false, "NMC_REGISTRY", "unverifiable")
                .get("status"))
        .isEqualTo("UNVERIFIED");

    assertThatThrownBy(() -> service.verify(COMPLIANCE, d.doctor().id(), true, "NOPE", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.verify(COMPLIANCE, d.doctor().id(), true, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () -> service.verify(COMPLIANCE, d.doctor().id(), true, "MANUAL", "x".repeat(501)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    service.blacklist(COMPLIANCE, d.doctor().id(), "reason long enough");
    assertThatThrownBy(() -> service.verify(COMPLIANCE, d.doctor().id(), true, "MANUAL", "x"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("DOCTOR_ALREADY_BLACKLISTED");

    assertThatThrownBy(() -> service.blacklist(COMPLIANCE, Ids.newId(), "x".repeat(1001)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void retroactive_alreadyFlaggedAndNoQueue() {
    var d = service.upsertFromOcr(Ids.newId(), "Dr", "F1", "MBBS", null);
    UUID rx = store.links.keySet().iterator().next();
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
            "BLACKLISTED_DOCTOR",
            "HIGH",
            null,
            NOW,
            null,
            NOW));
    when(queues.findLatestByRxId(any())).thenReturn(Optional.empty());
    service.blacklist(COMPLIANCE, d.doctor().id(), "ban for fraud case number 1");
    verify(autoFlags, never()).applyPendingFlags(any(), any());
  }

  @Test
  void retroactive_callsAutoFlagWhenQueued() {
    var d = service.upsertFromOcr(Ids.newId(), "Dr", "F2", "MBBS", null);
    UUID rx = store.links.keySet().iterator().next();
    UUID pharmacy = Ids.newId();
    when(queues.findLatestByRxId(rx))
        .thenReturn(
            Optional.of(
                new PharmacyRxQueueEntry(
                    Ids.newId(),
                    rx,
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
    service.blacklist(COMPLIANCE, d.doctor().id(), "ban for fraud case number 2");
    verify(autoFlags).applyPendingFlags(rx, pharmacy);
  }

  @Test
  void normalizeAndRateLimit() {
    assertThat(DoctorRegistryService.normalizeQualification(null)).isNull();
    assertThat(DoctorRegistryService.normalizeQualification("  ")).isNull();
    assertThat(DoctorRegistryService.normalizeQualification("BDS")).isEqualTo("BDS");
    InMemoryRateLimiter limiter = new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC));
    DoctorRegistryService tight =
        new DoctorRegistryService(
            store,
            audits,
            queues,
            notifications,
            outbox,
            mock(ObjectProvider.class),
            limiter,
            Clock.fixed(NOW, ZoneOffset.UTC));
    tight.list(COMPLIANCE, null, null, "ALL", 1, 20, "prescription_count", "desc");
    // burn rate limit
    for (int i = 0; i < 60; i++) {
      try {
        tight.list(COMPLIANCE, null, null, "ALL", 1, 20, "name", "asc");
      } catch (AppException ignored) {
        break;
      }
    }
    assertThatThrownBy(() -> tight.list(COMPLIANCE, null, null, "ALL", 1, 20, "name", "asc"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMITED");
  }
}
