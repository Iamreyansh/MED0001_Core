package com.nammamedmate.catalogue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.catalogue.application.port.out.AuditLogStore;
import com.nammamedmate.catalogue.application.port.out.MedicineStore;
import com.nammamedmate.catalogue.application.port.out.MedicineStore.MedicineRow;
import com.nammamedmate.catalogue.application.port.out.NotifyRateLimitPort;
import com.nammamedmate.catalogue.application.port.out.PriceCeilingStore;
import com.nammamedmate.catalogue.application.port.out.PriceCeilingStore.AboveCeilingMapping;
import com.nammamedmate.catalogue.application.port.out.PriceCeilingStore.CeilingListResult;
import com.nammamedmate.catalogue.application.port.out.PriceCeilingStore.CeilingRow;
import com.nammamedmate.catalogue.application.port.out.PriceCeilingViolationStore;
import com.nammamedmate.catalogue.application.port.out.PriceCeilingViolationStore.OpenViolation;
import com.nammamedmate.catalogue.application.port.out.PriceCeilingViolationStore.ViolationListResult;
import com.nammamedmate.catalogue.application.port.out.PriceCeilingViolationStore.ViolationRow;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PriceCeilingServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-08T00:00:00Z");

  @Mock private MedicineStore medicineStore;
  @Mock private PriceCeilingStore ceilingStore;
  @Mock private PriceCeilingViolationStore violationStore;
  @Mock private AuditLogStore auditLog;
  @Mock private NotifyRateLimitPort notifyRateLimit;

  private InMemoryOutboxStore outboxStore;
  private PriceCeilingService service;
  private final MedmatePrincipal superAdmin =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
  private final MedmatePrincipal compliance =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");
  private final MedmatePrincipal ops =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    outboxStore = new InMemoryOutboxStore();
    OutboxPublisher outbox = new OutboxPublisher(outboxStore, new ObjectMapper());
    service =
        new PriceCeilingService(
            medicineStore,
            ceilingStore,
            violationStore,
            auditLog,
            notifyRateLimit,
            outbox,
            new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC)),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void setCeiling_aboveMrpRejected() {
    UUID id = UUID.randomUUID();
    when(medicineStore.findById(id)).thenReturn(Optional.of(medicine(id, 8500L, null)));
    assertThatThrownBy(() -> service.setCeiling(superAdmin, id, 90.00, null, "reason"))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CEILING_ABOVE_MRP");
    verify(ceilingStore, never())
        .setCeiling(any(), anyLong(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void setCeiling_createsViolationsImmediately() {
    UUID id = UUID.randomUUID();
    UUID pharmacyId = UUID.randomUUID();
    when(medicineStore.findById(id)).thenReturn(Optional.of(medicine(id, 8500L, null)));
    when(ceilingStore.findAdminName(superAdmin.subject())).thenReturn(Optional.of("Kavya"));
    when(ceilingStore.findAboveCeilingMappings(id))
        .thenReturn(List.of(new AboveCeilingMapping(pharmacyId, id, 8000L, 7200L)));
    when(ceilingStore.countAboveCeiling(id)).thenReturn(1L);

    Map<String, Object> data =
        service.setCeiling(superAdmin, id, 72.00, "2026-07-01", "NLEM price ceiling");
    assertThat(data.get("new_ceiling_price")).isEqualTo(new BigDecimal("72.00"));
    assertThat(data.get("pharmacies_above_ceiling")).isEqualTo(1L);
    verify(ceilingStore)
        .setCeiling(
            eq(id),
            eq(7200L),
            eq(LocalDate.parse("2026-07-01")),
            eq("NLEM price ceiling"),
            eq(superAdmin.subject()),
            eq("Kavya"),
            eq("admin_super"),
            eq(NOW));
    verify(violationStore).upsertOpen(any(), eq(id), eq(pharmacyId), eq(7200L), eq(8000L), eq(NOW));
    verify(auditLog).append(any());
  }

  @Test
  void removeCeiling_resolvesOpenViolations() {
    UUID id = UUID.randomUUID();
    when(medicineStore.findById(id)).thenReturn(Optional.of(medicine(id, 8500L, 7200L)));
    when(violationStore.resolveOpenForMedicine(id, NOW)).thenReturn(3);

    Map<String, Object> data = service.removeCeiling(superAdmin, id, "policy lifted");
    assertThat(data).containsEntry("ceiling_removed", true).containsEntry("violations_resolved", 3);
    verify(ceilingStore).clearCeiling(id, NOW);
    verify(violationStore).resolveOpenForMedicine(id, NOW);
  }

  @Test
  void listViolations_filtersByMedicine() {
    UUID med = UUID.randomUUID();
    UUID pharmacy = UUID.randomUUID();
    when(violationStore.list(eq(med), isNull(), eq(1), eq(20)))
        .thenReturn(
            new ViolationListResult(
                List.of(
                    new ViolationRow(
                        UUID.randomUUID(),
                        med,
                        "Amox",
                        7200L,
                        pharmacy,
                        "City Medicals",
                        8000L,
                        800L,
                        "Indiranagar Zone",
                        NOW,
                        null,
                        "OPEN")),
                1));

    var page = service.listViolations(ops, med, null, null, null);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> violations =
        (List<Map<String, Object>>) page.data().get("violations");
    assertThat(violations).hasSize(1);
    assertThat(violations.getFirst().get("overage_amount")).isEqualTo(new BigDecimal("8.00"));
    assertThat(violations.getFirst().get("overage_pct")).isEqualTo(new BigDecimal("11.1"));
    assertThat(violations.getFirst().get("pharmacy_name")).isEqualTo("City Medicals");
  }

  @Test
  void notify_sendsOutboxAndRateLimits() {
    UUID med = UUID.randomUUID();
    UUID pharmacy = UUID.randomUUID();
    UUID violationId = UUID.randomUUID();
    when(notifyRateLimit.tryAcquire(eq(med), any(), eq(NOW))).thenReturn(Optional.empty());
    when(violationStore.listOpen(med))
        .thenReturn(List.of(new OpenViolation(violationId, med, "Amox", pharmacy, 7200L, 8000L)));

    Map<String, Object> data = service.notifyViolations(compliance, med, "please correct");
    assertThat(data.get("pharmacies_notified")).isEqualTo(1L);
    assertThat(data.get("channels")).isEqualTo(List.of("WHATSAPP", "IN_APP"));
    assertThat(data.get("next_batch_allowed_at")).isEqualTo(NOW.plusSeconds(4 * 3600).toString());

    assertThat(outboxStore.all()).hasSize(1);
    assertThat(outboxStore.all().getFirst().type())
        .isEqualTo("catalogue.notification.price_ceiling_violation");
    assertThat(outboxStore.all().getFirst().payloadJson())
        .contains("PHARMACY_PRICE_CEILING_VIOLATION");
    verify(violationStore).markNotified(List.of(violationId), NOW);

    when(notifyRateLimit.tryAcquire(eq(med), any(), eq(NOW)))
        .thenReturn(Optional.of(NOW.plusSeconds(100)));
    assertThatThrownBy(() -> service.notifyViolations(compliance, med, null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NOTIFICATION_RATE_LIMITED");
  }

  @Test
  void listCeilings_hasViolationsFilter() {
    UUID med = UUID.randomUUID();
    when(ceilingStore.listCeilings(null, true, 1, 20))
        .thenReturn(
            new CeilingListResult(
                List.of(
                    new CeilingRow(
                        med,
                        "Amox",
                        "Antibiotics",
                        "H",
                        8500L,
                        7200L,
                        3,
                        LocalDate.parse("2026-07-01"),
                        superAdmin.subject(),
                        "Kavya",
                        "admin_super",
                        NOW,
                        "NLEM")),
                1));

    var page = service.listCeilings(ops, null, true, null, null);
    @SuppressWarnings("unchecked")
    List<?> ceilings = (List<?>) page.data().get("price_ceilings");
    assertThat(ceilings).hasSize(1);
    assertThat(page.meta().total()).isEqualTo(1);
  }

  @Test
  void detectViolations_upsertsAndResolvesStale() {
    UUID med = UUID.randomUUID();
    UUID pharmacy = UUID.randomUUID();
    when(ceilingStore.findAllAboveCeilingMappings())
        .thenReturn(List.of(new AboveCeilingMapping(pharmacy, med, 8000L, 7200L)));
    service.detectViolations();
    verify(violationStore).upsertOpen(any(), eq(med), eq(pharmacy), eq(7200L), eq(8000L), eq(NOW));
    verify(violationStore).resolveStale(NOW);
  }

  private static MedicineRow medicine(UUID id, long mrp, Long ceiling) {
    return new MedicineRow(
        id,
        "Amox",
        "Amoxicillin",
        "GSK",
        UUID.randomUUID(),
        "Antibiotics",
        "CAPSULE",
        new BigDecimal("10"),
        "CAPSULE",
        "H",
        "30041090",
        12,
        mrp,
        ceiling,
        true,
        false,
        null,
        0,
        0,
        List.of(),
        null,
        null,
        NOW,
        NOW);
  }
}
