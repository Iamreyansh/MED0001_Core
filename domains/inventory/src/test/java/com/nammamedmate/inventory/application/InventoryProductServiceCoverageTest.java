package com.nammamedmate.inventory.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.nammamedmate.inventory.application.port.out.InventoryExcelExporter;
import com.nammamedmate.inventory.application.port.out.InventoryPlanPort;
import com.nammamedmate.inventory.application.port.out.PharmacyProductStore;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
class InventoryProductServiceCoverageTest {

  private static final Instant NOW = Instant.parse("2026-08-09T00:00:00Z");
  private static final UUID PHARMACY = UUID.randomUUID();

  @Mock private PharmacyProductStore store;
  @Mock private InventoryBatchService batchService;
  @Mock private InventoryPlanPort planPort;
  @Mock private InventoryExcelExporter excelExporter;
  @Mock private RateLimiter rateLimiter;

  private InventoryProductService service;
  private MedmatePrincipal owner;

  @BeforeEach
  void setUp() {
    owner =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_OWNER, PHARMACY, TokenScope.FULL, "j");
    when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(true);
    service =
        new InventoryProductService(
            store,
            batchService,
            planPort,
            excelExporter,
            rateLimiter,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void rateLimitExceeded() {
    when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(false);
    assertThatThrownBy(() -> service.summary(owner))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMIT_EXCEEDED");
  }

  @Test
  void customerForbidden() {
    MedmatePrincipal customer =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.summary(customer))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void missingPharmacyContext() {
    MedmatePrincipal noPh =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_OWNER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.summary(noPh))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void invalidFormAndFractionalGst() {
    assertThatThrownBy(
            () ->
                service.patchDetails(
                    owner,
                    UUID.randomUUID(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "BAD",
                    null,
                    null,
                    null,
                    null,
                    null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.patchDetails(
                    owner,
                    UUID.randomUUID(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    new BigDecimal("12.5"),
                    null,
                    null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_GST_PCT");
  }

  @Test
  void staffCannotPatchDetails() {
    MedmatePrincipal staff =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_STAFF, PHARMACY, TokenScope.FULL, "j");
    assertThatThrownBy(
            () ->
                service.patchDetails(
                    staff,
                    UUID.randomUUID(),
                    "n",
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
                    null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void rackCodeTooLong() {
    assertThatThrownBy(
            () -> service.patchSettings(owner, UUID.randomUUID(), null, null, null, "x".repeat(21)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }
}
