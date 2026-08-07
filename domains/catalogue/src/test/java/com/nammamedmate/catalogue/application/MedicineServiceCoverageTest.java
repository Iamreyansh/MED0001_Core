package com.nammamedmate.catalogue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.nammamedmate.catalogue.application.MedicineService.CreateCommand;
import com.nammamedmate.catalogue.application.MedicineService.UpdateCommand;
import com.nammamedmate.catalogue.application.port.out.AuditLogStore;
import com.nammamedmate.catalogue.application.port.out.BanMappingHidePort;
import com.nammamedmate.catalogue.application.port.out.MedicineBanJobStore;
import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore;
import com.nammamedmate.catalogue.application.port.out.MedicineStore;
import com.nammamedmate.catalogue.application.port.out.MedicineStore.MedicineRow;
import com.nammamedmate.catalogue.application.port.out.MedicineStore.SummaryStats;
import com.nammamedmate.catalogue.application.port.out.OrderDemandPort;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
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
class MedicineServiceCoverageTest {

  private static final Instant NOW = Instant.parse("2026-08-08T00:00:00Z");
  private static final UUID CATEGORY = UUID.fromString("c0000001-0000-4000-8000-000000000001");

  @Mock private MedicineStore store;
  @Mock private AuditLogStore auditLog;
  @Mock private BanMappingHidePort banHide;
  @Mock private MedicineBanJobStore banJobs;
  @Mock private MedicineMappingStore mappings;
  @Mock private OrderDemandPort orderDemand;
  @Mock private RateLimiter rateLimiter;

  private MedicineService service;
  private final MedmatePrincipal admin =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    when(rateLimiter.tryAcquire(any(), any(Integer.class), any(Integer.class))).thenReturn(true);
    service =
        new MedicineService(
            store,
            auditLog,
            banHide,
            banJobs,
            mappings,
            orderDemand,
            rateLimiter,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void rateLimited_andEmptySummaryAvg_andNoOpUpdate() {
    when(rateLimiter.tryAcquire(any(), any(Integer.class), any(Integer.class))).thenReturn(false);
    assertThatThrownBy(() -> service.summary(admin))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMIT_EXCEEDED");

    when(rateLimiter.tryAcquire(any(), any(Integer.class), any(Integer.class))).thenReturn(true);
    when(store.summary(NOW)).thenReturn(new SummaryStats(0, 0, 0, 0, 0, 0, 0, 0, null, 0, NOW));
    assertThat((java.math.BigDecimal) service.summary(admin).get("avg_mrp"))
        .isEqualByComparingTo("0");

    UUID id = UUID.randomUUID();
    MedicineRow row = row(id, "OTC", false);
    when(store.findById(id)).thenReturn(Optional.of(row));
    assertThat(
            service
                .update(
                    admin,
                    id,
                    new UpdateCommand("Med", null, null, null, null, null, null, null, null))
                .get("updated_fields"))
        .asList()
        .isEmpty();
  }

  @Test
  void createValidationEdges() {
    when(store.categoryActive(CATEGORY)).thenReturn(true);
    when(store.hsnExists("30041090")).thenReturn(true);

    assertThatThrownBy(
            () ->
                service.create(
                    admin,
                    new CreateCommand(
                        "A",
                        "s",
                        "m",
                        CATEGORY,
                        "TABLET",
                        1,
                        "BAD",
                        "OTC",
                        "30041090",
                        5,
                        10,
                        true,
                        null,
                        null,
                        null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.create(
                    admin,
                    new CreateCommand(
                        " ",
                        "s",
                        "m",
                        CATEGORY,
                        "TABLET",
                        1,
                        "TABLET",
                        "OTC",
                        "30041090",
                        5,
                        10,
                        true,
                        null,
                        null,
                        null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.create(
                    admin,
                    new CreateCommand(
                        "Ok",
                        " ",
                        "m",
                        CATEGORY,
                        "TABLET",
                        1,
                        "TABLET",
                        "OTC",
                        "30041090",
                        5,
                        10,
                        true,
                        null,
                        null,
                        null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.create(
                    admin,
                    new CreateCommand(
                        "Ok",
                        "s",
                        " ",
                        CATEGORY,
                        "TABLET",
                        1,
                        "TABLET",
                        "OTC",
                        "30041090",
                        5,
                        10,
                        true,
                        null,
                        null,
                        null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.create(
                    admin,
                    new CreateCommand(
                        "Ok",
                        "s",
                        "m",
                        CATEGORY,
                        "TABLET",
                        0,
                        "TABLET",
                        "OTC",
                        "30041090",
                        5,
                        10,
                        true,
                        null,
                        null,
                        null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.create(
                    admin,
                    new CreateCommand(
                        "Ok",
                        "s",
                        "m",
                        CATEGORY,
                        "TABLET",
                        "x",
                        "TABLET",
                        "OTC",
                        "30041090",
                        5,
                        10,
                        true,
                        null,
                        null,
                        null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.create(
                    admin,
                    new CreateCommand(
                        "Ok", "s", "m", CATEGORY, "TABLET", 1, "TABLET", "OTC", "123", 5, 10, true,
                        null, null, null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_HSN_CODE");

    String longDesc = "x".repeat(2001);
    assertThatThrownBy(
            () ->
                service.create(
                    admin,
                    new CreateCommand(
                        "Ok Name",
                        "salt",
                        "mfr",
                        CATEGORY,
                        "TABLET",
                        new BigDecimal("1.5"),
                        "ML",
                        "OTC",
                        "30041090",
                        5,
                        "10.00",
                        false,
                        longDesc,
                        List.of(),
                        null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void updateClearDescription_andUnbanNotBanned_andBanRoleNull() {
    UUID id = UUID.randomUUID();
    when(store.findById(id)).thenReturn(Optional.of(row(id, "OTC", false)));
    service.update(
        admin, id, new UpdateCommand(null, "  ", null, null, null, null, null, null, null));

    when(store.findById(id)).thenReturn(Optional.of(row(id, "OTC", false)));
    assertThatThrownBy(() -> service.unban(admin, id, "ok"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(() -> service.ban(null, id, "r"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> service.get(null, id))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void pageResultNullSafe_andCommandCopy() {
    assertThat(new MedicineService.PageResult(null, null).data()).isEmpty();
    CreateCommand c =
        new CreateCommand(
            "n",
            "s",
            "m",
            CATEGORY,
            "TABLET",
            1,
            "TABLET",
            "OTC",
            "30041090",
            5,
            1,
            true,
            null,
            List.of(UUID.randomUUID()),
            null);
    assertThat(c.substitutes()).hasSize(1);
    UpdateCommand u =
        new UpdateCommand(
            null, null, null, null, null, null, null, List.of(UUID.randomUUID()), null);
    assertThat(u.substitutes()).hasSize(1);
  }

  private MedicineRow row(UUID id, String schedule, boolean banned) {
    return new MedicineRow(
        id,
        "Med",
        "Salt",
        "Mfr",
        CATEGORY,
        "Antibiotics",
        "TABLET",
        new BigDecimal("10.00"),
        "TABLET",
        schedule,
        "30041090",
        12,
        21850L,
        null,
        false,
        banned,
        banned ? "r" : null,
        0,
        0,
        List.of(),
        "d",
        admin.subject(),
        NOW,
        NOW);
  }
}
