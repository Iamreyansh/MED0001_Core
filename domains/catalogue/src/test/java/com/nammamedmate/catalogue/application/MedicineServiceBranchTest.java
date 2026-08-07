package com.nammamedmate.catalogue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.catalogue.application.MedicineService.CreateCommand;
import com.nammamedmate.catalogue.application.MedicineService.UpdateCommand;
import com.nammamedmate.catalogue.application.port.out.AuditLogStore;
import com.nammamedmate.catalogue.application.port.out.BanMappingHidePort;
import com.nammamedmate.catalogue.application.port.out.MedicineBanJobStore;
import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore;
import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore.AdminListResult;
import com.nammamedmate.catalogue.application.port.out.MedicineStore;
import com.nammamedmate.catalogue.application.port.out.MedicineStore.ListResult;
import com.nammamedmate.catalogue.application.port.out.MedicineStore.MedicineRow;
import com.nammamedmate.catalogue.application.port.out.OrderDemandPort;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
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
class MedicineServiceBranchTest {

  private static final Instant NOW = Instant.parse("2026-08-08T00:00:00Z");
  private static final UUID CATEGORY = UUID.fromString("c0000001-0000-4000-8000-000000000001");

  @Mock private MedicineStore store;
  @Mock private AuditLogStore auditLog;
  @Mock private BanMappingHidePort banHide;
  @Mock private MedicineBanJobStore banJobs;
  @Mock private MedicineMappingStore mappings;
  @Mock private OrderDemandPort orderDemand;

  private MedicineService service;
  private final MedmatePrincipal admin =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    service =
        new MedicineService(
            store,
            auditLog,
            banHide,
            banJobs,
            mappings,
            orderDemand,
            new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC)),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void listBlankFilters_andNotFoundPaths() {
    when(store.list(any())).thenReturn(new ListResult(List.of(), 0));
    service.list(admin, null, "  ", null, null, false, null, "  ", "  ", 2, 5);

    UUID id = UUID.randomUUID();
    when(store.findById(id)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.get(admin, id))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEDICINE_NOT_FOUND");
    assertThatThrownBy(
            () ->
                service.update(
                    admin,
                    id,
                    new UpdateCommand("x", null, null, null, null, null, null, null, null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEDICINE_NOT_FOUND");
    assertThatThrownBy(() -> service.ban(admin, id, "r"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEDICINE_NOT_FOUND");
    assertThatThrownBy(() -> service.unban(admin, id, "r"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEDICINE_NOT_FOUND");
  }

  @Test
  void updateNoopBranches_andCeilingOk_andSubstitutesSame() {
    UUID id = UUID.randomUUID();
    UUID sub = UUID.randomUUID();
    MedicineRow existing =
        new MedicineRow(
            id,
            "Med",
            "Salt",
            "Mfr",
            CATEGORY,
            "Antibiotics",
            "TABLET",
            new BigDecimal("10.00"),
            "TABLET",
            "OTC",
            "30041090",
            12,
            21850L,
            10000L,
            false,
            false,
            null,
            0,
            0,
            List.of(sub),
            "d",
            null,
            NOW,
            NOW);
    when(store.findById(id)).thenReturn(Optional.of(existing));
    when(store.categoryActive(CATEGORY)).thenReturn(true);
    when(store.countExistingIds(List.of(sub))).thenReturn(1);

    service.update(
        admin,
        id,
        new UpdateCommand("Med", "d", CATEGORY, "OTC", 12, 218.50, false, List.of(sub), null));

    when(store.findById(id)).thenReturn(Optional.of(existing));
    service.update(
        admin, id, new UpdateCommand(null, null, null, null, null, 300.00, true, null, null));

    when(store.findById(id)).thenReturn(Optional.of(existing));
    when(store.findSubstituteRefs(List.of(sub))).thenReturn(List.of());
    when(mappings.listForAdmin(any())).thenReturn(new AdminListResult(List.of(), 0, 0));
    assertThat(service.get(admin, id).get("mrp_ceiling")).isNotNull();
    assertThat(service.get(admin, id).get("created_by")).isNull();
    assertThat(service.fieldValue(existing, "unknown")).isNull();
    assertThat(service.fieldValue(existing, "name")).isEqualTo("Med");
    assertThat(service.fieldValue(existing, "description")).isEqualTo("d");
    assertThat(service.fieldValue(existing, "category_id")).isEqualTo(CATEGORY.toString());
    assertThat(service.fieldValue(existing, "schedule")).isEqualTo("OTC");
    assertThat(service.fieldValue(existing, "gst_pct")).isEqualTo(12);
    assertThat(service.fieldValue(existing, "mrp")).isEqualTo(21850L);
    assertThat(service.fieldValue(existing, "is_rx_only")).isEqualTo(false);
    assertThat(service.fieldValue(existing, "substitutes")).asList().contains(sub.toString());
  }

  @Test
  void createValidationExtremes() {
    when(store.categoryActive(CATEGORY)).thenReturn(true);
    when(store.hsnExists("30041090")).thenReturn(true);

    assertThatThrownBy(
            () ->
                service.create(
                    admin,
                    new CreateCommand(
                        null,
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
                        "s".repeat(501),
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
                        "m".repeat(201),
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
                        null,
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
        .isEqualTo("INVALID_CATEGORY");

    assertThatThrownBy(
            () ->
                service.create(
                    admin,
                    new CreateCommand(
                        "Ok",
                        "s",
                        "m",
                        CATEGORY,
                        null,
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
        .isEqualTo("INVALID_FORM");

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
                        null,
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
                        1,
                        null,
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
                        1,
                        "TABLET",
                        null,
                        "30041090",
                        5,
                        10,
                        true,
                        null,
                        null,
                        null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_SCHEDULE");

    assertThatThrownBy(
            () ->
                service.create(
                    admin,
                    new CreateCommand(
                        "x".repeat(256),
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

    assertThatThrownBy(() -> service.ban(admin, UUID.randomUUID(), "r".repeat(501)))
        .extracting(ex -> ((AppException) ex).code())
        .isIn("MEDICINE_NOT_FOUND", "VALIDATION_ERROR");

    when(store.findById(any())).thenReturn(Optional.of(row(UUID.randomUUID(), "H", false)));
    assertThatThrownBy(() -> service.ban(admin, UUID.randomUUID(), "r".repeat(501)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void updateScheduleOnlyForcesRx_andListBannedRow() {
    UUID id = UUID.randomUUID();
    MedicineRow existing = row(id, "OTC", false);
    when(store.findById(id)).thenReturn(Optional.of(existing));
    service.update(
        admin, id, new UpdateCommand(null, null, null, "H1", null, null, null, null, null));
    verify(store)
        .update(
            eq(id), eq(null), eq(null), eq(null), eq("H1"), eq(null), eq(null), eq(true), eq(null),
            eq(NOW));

    when(store.list(any())).thenReturn(new ListResult(List.of(row(id, "H", true)), 1));
    assertThat(service.list(admin, null, null, null, null, true, null, null, null, 1, 20).data())
        .isNotEmpty();
  }

  @Test
  void createWithNullSubstitutes_andEmptyDescription() {
    when(store.categoryActive(CATEGORY)).thenReturn(true);
    when(store.hsnExists("30041090")).thenReturn(true);
    service.create(
        admin,
        new CreateCommand(
            "OTC Med",
            "Paracetamol (500mg)",
            "Cipla",
            CATEGORY,
            "TABLET",
            "10",
            "TABLET",
            "OTC",
            "30041090",
            5,
            new BigDecimal("12.00"),
            false,
            "   ",
            null,
            null));
  }

  @Test
  void recordNullCopies() {
    assertThat(
            new MedicineStore.MedicineRow(
                    UUID.randomUUID(),
                    "n",
                    "s",
                    "m",
                    CATEGORY,
                    null,
                    "TABLET",
                    BigDecimal.ONE,
                    "TABLET",
                    "OTC",
                    "30041090",
                    5,
                    100,
                    null,
                    false,
                    false,
                    null,
                    0,
                    0,
                    null,
                    null,
                    null,
                    NOW,
                    NOW)
                .substitutes())
        .isEmpty();
    assertThat(new MedicineStore.ListResult(null, 0).rows()).isEmpty();
    assertThat(
            new AuditLogStore.AuditLogRecord(
                    UUID.randomUUID(),
                    "MEDICINE",
                    UUID.randomUUID(),
                    "X",
                    UUID.randomUUID(),
                    "R",
                    null,
                    null,
                    NOW)
                .payload())
        .isEmpty();
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
        "H".equals(schedule) || "H1".equals(schedule) || "X".equals(schedule),
        banned,
        banned ? "ban" : null,
        0,
        0,
        List.of(),
        "d",
        admin.subject(),
        NOW,
        NOW);
  }
}
