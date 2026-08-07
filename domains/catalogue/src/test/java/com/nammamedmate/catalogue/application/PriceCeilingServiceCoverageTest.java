package com.nammamedmate.catalogue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.catalogue.application.port.out.AuditLogStore;
import com.nammamedmate.catalogue.application.port.out.MedicineStore;
import com.nammamedmate.catalogue.application.port.out.MedicineStore.MedicineRow;
import com.nammamedmate.catalogue.application.port.out.NotifyRateLimitPort;
import com.nammamedmate.catalogue.application.port.out.PriceCeilingStore;
import com.nammamedmate.catalogue.application.port.out.PriceCeilingStore.CeilingListResult;
import com.nammamedmate.catalogue.application.port.out.PriceCeilingViolationStore;
import com.nammamedmate.catalogue.application.port.out.PriceCeilingViolationStore.ViolationListResult;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
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
class PriceCeilingServiceCoverageTest {

  private static final Instant NOW = Instant.parse("2026-08-08T00:00:00Z");

  @Mock private MedicineStore medicineStore;
  @Mock private PriceCeilingStore ceilingStore;
  @Mock private PriceCeilingViolationStore violationStore;
  @Mock private AuditLogStore auditLog;
  @Mock private NotifyRateLimitPort notifyRateLimit;

  private PriceCeilingService service;
  private final MedmatePrincipal superAdmin =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
  private final MedmatePrincipal customer =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
  private final MedmatePrincipal ops =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");

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
  void validationAndAuthBranches() {
    UUID id = UUID.randomUUID();

    assertThatThrownBy(() -> service.listCeilings(null, null, null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> service.listCeilings(customer, null, null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    assertThatThrownBy(() -> service.setCeiling(null, id, 1, null, "r"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> service.setCeiling(ops, id, 1, null, "r"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    when(medicineStore.findById(id)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.setCeiling(superAdmin, id, 1, null, "r"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEDICINE_NOT_FOUND");

    when(medicineStore.findById(id)).thenReturn(Optional.of(med(id, 8500L, null)));
    assertThatThrownBy(() -> service.setCeiling(superAdmin, id, 0, null, "r"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CEILING_PRICE_MUST_BE_POSITIVE");
    assertThatThrownBy(() -> service.setCeiling(superAdmin, id, -1, null, "r"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CEILING_PRICE_MUST_BE_POSITIVE");
    assertThatThrownBy(() -> service.setCeiling(superAdmin, id, null, null, "r"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CEILING_PRICE_MUST_BE_POSITIVE");
    assertThatThrownBy(() -> service.setCeiling(superAdmin, id, "x", null, "r"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CEILING_PRICE_MUST_BE_POSITIVE");
    assertThatThrownBy(() -> service.setCeiling(superAdmin, id, Boolean.TRUE, null, "r"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("CEILING_PRICE_MUST_BE_POSITIVE");
    assertThatThrownBy(() -> service.setCeiling(superAdmin, id, new BigDecimal("1.001"), null, "r"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.setCeiling(superAdmin, id, 10, null, " "))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REASON_REQUIRED");
    assertThatThrownBy(() -> service.setCeiling(superAdmin, id, 10, null, "x".repeat(501)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.setCeiling(superAdmin, id, 10, "not-a-date", "ok"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    when(ceilingStore.findAdminName(any())).thenReturn(Optional.empty());
    when(ceilingStore.findAboveCeilingMappings(id)).thenReturn(List.of());
    when(ceilingStore.countAboveCeiling(id)).thenReturn(0L);
    var set = service.setCeiling(superAdmin, id, "10.50", " ", "ok reason");
    assertThat(set.get("new_ceiling_price")).isEqualTo(new BigDecimal("10.50"));

    when(medicineStore.findById(id)).thenReturn(Optional.of(med(id, 8500L, null)));
    assertThatThrownBy(() -> service.removeCeiling(superAdmin, id, "r"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NO_CEILING_SET");
    when(medicineStore.findById(id)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.removeCeiling(superAdmin, id, "r"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEDICINE_NOT_FOUND");
    assertThatThrownBy(() -> service.removeCeiling(null, id, "r"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> service.removeCeiling(ops, id, "r"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    when(medicineStore.findById(id)).thenReturn(Optional.of(med(id, 8500L, 100L)));
    assertThatThrownBy(() -> service.removeCeiling(superAdmin, id, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REASON_REQUIRED");

    assertThatThrownBy(() -> service.listViolations(null, null, null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    when(violationStore.list(any(), any(), eq(1), eq(100)))
        .thenReturn(new ViolationListResult(List.of(), 0));
    assertThat(service.listViolations(ops, null, null, 0, 200).meta().limit()).isEqualTo(100);

    assertThatThrownBy(() -> service.notifyViolations(null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> service.notifyViolations(ops, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    when(notifyRateLimit.tryAcquire(any(), any(), any())).thenReturn(Optional.empty());
    when(violationStore.listOpen(null)).thenReturn(List.of());
    assertThat(service.notifyViolations(superAdmin, null, "  hi  ").get("violations_covered"))
        .isEqualTo(0);
    assertThatThrownBy(() -> service.notifyViolations(superAdmin, null, "m".repeat(501)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    when(ceilingStore.listCeilings(any(), any(), eq(1), eq(20)))
        .thenReturn(new CeilingListResult(List.of(), 0));
    assertThat(service.listCeilings(ops, UUID.randomUUID(), false, null, null).meta().total())
        .isEqualTo(0);

    assertThat(PriceCeilingService.overagePct(0, 0)).isEqualByComparingTo("0.0");
    assertThat(PriceCeilingService.parseCeilingPaise(new BigDecimal("1.25"))).isEqualTo(125L);
    assertThat(PriceCeilingService.paiseToRupees(125)).isEqualByComparingTo("1.25");
  }

  @Test
  void rateLimitExceeded() {
    RateLimiter denied = mock(RateLimiter.class);
    when(denied.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(false);
    PriceCeilingService limited =
        new PriceCeilingService(
            medicineStore,
            ceilingStore,
            violationStore,
            auditLog,
            notifyRateLimit,
            new OutboxPublisher(new InMemoryOutboxStore(), new ObjectMapper()),
            denied,
            Clock.fixed(NOW, ZoneOffset.UTC));
    assertThatThrownBy(() -> limited.listCeilings(ops, null, null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMIT_EXCEEDED");
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
