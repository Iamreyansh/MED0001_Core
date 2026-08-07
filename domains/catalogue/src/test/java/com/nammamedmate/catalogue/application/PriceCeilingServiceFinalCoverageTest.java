package com.nammamedmate.catalogue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import com.nammamedmate.kernel.api.PaginationMeta;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.messaging.InMemoryOutboxStore;
import com.nammamedmate.messaging.OutboxPublisher;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PriceCeilingServiceFinalCoverageTest {

  private static final Instant NOW = Instant.parse("2026-08-08T00:00:00Z");

  @Mock private MedicineStore medicineStore;
  @Mock private PriceCeilingStore ceilingStore;
  @Mock private PriceCeilingViolationStore violationStore;
  @Mock private AuditLogStore auditLog;
  @Mock private NotifyRateLimitPort notifyRateLimit;

  private PriceCeilingService service;
  private final MedmatePrincipal superAdmin =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
  private final MedmatePrincipal compliance =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    service =
        new PriceCeilingService(
            medicineStore,
            ceilingStore,
            violationStore,
            auditLog,
            notifyRateLimit,
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC)),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void remainingBranches() {
    assertThat(new PriceCeilingService.PageResult(null, PaginationMeta.of(1, 20, 0)).data())
        .isEmpty();
    assertThat(new CeilingListResult(null, 0).rows()).isEmpty();
    assertThat(new ViolationListResult(null, 0).rows()).isEmpty();

    UUID med = UUID.randomUUID();
    when(ceilingStore.listCeilings(null, null, 2, 1))
        .thenReturn(
            new CeilingListResult(
                List.of(
                    new CeilingRow(
                        med, "Amox", "Cat", "H", 8500L, 7200L, 0, null, null, null, null, null,
                        null)),
                1));
    var ceilings = service.listCeilings(compliance, null, null, 2, 0);
    assertThat(ceilings.meta().page()).isEqualTo(2);
    @SuppressWarnings("unchecked")
    Map<String, Object> first =
        (Map<String, Object>) ((List<?>) ceilings.data().get("price_ceilings")).getFirst();
    assertThat(first.get("effective_from")).isNull();
    assertThat(((Map<?, ?>) first.get("set_by")).get("admin_id")).isNull();
    assertThat(first.get("set_at")).isNull();

    when(ceilingStore.listCeilings(null, null, 1, 20))
        .thenReturn(new CeilingListResult(List.of(), 0));
    assertThat(service.listCeilings(compliance, null, null, null, null).meta().limit())
        .isEqualTo(20);

    when(medicineStore.findById(med)).thenReturn(Optional.of(med(med, 8500L, 7000L)));
    when(ceilingStore.findAdminName(any())).thenReturn(Optional.of("Kavya"));
    when(ceilingStore.findAboveCeilingMappings(med)).thenReturn(List.of());
    when(ceilingStore.countAboveCeiling(med)).thenReturn(0L);
    Map<String, Object> set = service.setCeiling(superAdmin, med, 72, null, "overwrite");
    assertThat(set.get("previous_ceiling")).isEqualTo(new BigDecimal("70.00"));
    when(medicineStore.findById(med)).thenReturn(Optional.of(med(med, 8500L, 7200L)));
    assertThat(
            service.setCeiling(superAdmin, med, 71, "  ", "blank effective").get("effective_from"))
        .isNotNull();

    when(violationStore.list(eq(med), any(), eq(3), eq(5)))
        .thenReturn(
            new ViolationListResult(
                List.of(
                    new ViolationRow(
                        UUID.randomUUID(),
                        med,
                        "Amox",
                        7200L,
                        UUID.randomUUID(),
                        "P",
                        8000L,
                        800L,
                        "Z",
                        null,
                        NOW,
                        "NOTIFIED")),
                1));
    var violations = service.listViolations(superAdmin, med, UUID.randomUUID(), 3, 5);
    @SuppressWarnings("unchecked")
    Map<String, Object> v =
        (Map<String, Object>) ((List<?>) violations.data().get("violations")).getFirst();
    assertThat(v.get("detected_at")).isNull();
    assertThat(v.get("last_notified_at")).isEqualTo(NOW.toString());

    UUID pharmacy = UUID.randomUUID();
    when(notifyRateLimit.tryAcquire(eq(med), any(), eq(NOW))).thenReturn(Optional.empty());
    when(violationStore.listOpen(med))
        .thenReturn(
            List.of(new OpenViolation(UUID.randomUUID(), med, "Amox", pharmacy, 7200L, 8000L)));
    Map<String, Object> notified = service.notifyViolations(compliance, med, "   ");
    assertThat(notified.get("violations_covered")).isEqualTo(1);
    when(notifyRateLimit.tryAcquire(eq(med), any(), eq(NOW))).thenReturn(Optional.empty());
    assertThat(service.notifyViolations(compliance, med, null).get("violations_covered"))
        .isEqualTo(1);

    when(ceilingStore.findAllAboveCeilingMappings())
        .thenReturn(List.of(new AboveCeilingMapping(pharmacy, med, 8000L, 7200L)));
    service.detectViolations();

    assertThat(PriceCeilingService.parseCeilingPaise(12)).isEqualTo(1200L);
  }

  private static MedicineRow med(UUID id, long mrp, Long ceiling) {
    return new MedicineRow(
        id,
        "Amox",
        "s",
        "m",
        UUID.randomUUID(),
        "c",
        "TABLET",
        new BigDecimal("1"),
        "TABLET",
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
