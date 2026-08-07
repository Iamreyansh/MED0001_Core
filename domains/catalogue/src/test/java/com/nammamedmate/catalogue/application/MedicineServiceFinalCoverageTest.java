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
class MedicineServiceFinalCoverageTest {

  private static final Instant NOW = Instant.parse("2026-08-08T00:00:00Z");
  private static final UUID CATEGORY = UUID.fromString("c0000001-0000-4000-8000-000000000001");
  private static final UUID CATEGORY_2 = UUID.fromString("c0000001-0000-4000-8000-000000000002");

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
  void createOtcRxTrue_andUpdateAllFieldsWithNullDescription() {
    when(store.categoryActive(any())).thenReturn(true);
    when(store.hsnExists("30041090")).thenReturn(true);

    service.create(
        admin,
        new CreateCommand(
            "Crocin",
            "Paracetamol (500mg)",
            "GSK",
            CATEGORY,
            "TABLET",
            10,
            "TABLET",
            "OTC",
            "30041090",
            5,
            20,
            true,
            null,
            List.of(),
            null));

    UUID id = UUID.randomUUID();
    UUID oldSub = UUID.randomUUID();
    UUID newSub = UUID.randomUUID();
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
            5,
            1000L,
            null,
            false,
            false,
            null,
            0,
            0,
            List.of(oldSub),
            null,
            admin.subject(),
            NOW,
            NOW);
    when(store.findById(id)).thenReturn(Optional.of(existing));
    when(store.countExistingIds(List.of(newSub))).thenReturn(1);

    service.update(
        admin,
        id,
        new UpdateCommand(
            "Renamed", "Fresh desc", CATEGORY_2, "H", 18, 50.00, false, List.of(newSub), null));
    verify(store)
        .update(
            eq(id),
            eq("Renamed"),
            eq("Fresh desc"),
            eq(CATEGORY_2),
            eq("H"),
            eq(18),
            eq(5000L),
            eq(true),
            eq(List.of(newSub)),
            eq(NOW));
  }

  @Test
  void clearNullDescription_andScheduleOnlyWhenAlreadyRx() {
    UUID id = UUID.randomUUID();
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
            "H",
            "30041090",
            12,
            21850L,
            null,
            true,
            false,
            null,
            0,
            0,
            List.of(),
            null,
            admin.subject(),
            NOW,
            NOW);
    when(store.findById(id)).thenReturn(Optional.of(existing));

    service.update(
        admin, id, new UpdateCommand(null, "", null, "H1", null, null, null, List.of(), null));

    when(store.list(any()))
        .thenReturn(
            new ListResult(
                List.of(
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
                        "H",
                        "30041090",
                        12,
                        21850L,
                        null,
                        true,
                        true,
                        "reason",
                        0,
                        0,
                        List.of(),
                        "d",
                        admin.subject(),
                        NOW,
                        NOW)),
                1));
    assertThat(
            service
                .list(admin, null, null, null, null, true, " ", "name", "asc", 1, 20)
                .data()
                .get("medicines"))
        .asList()
        .isNotEmpty();
  }

  @Test
  void remainingValidationBranches() {
    when(store.categoryActive(CATEGORY)).thenReturn(true);
    when(store.hsnExists("30041090")).thenReturn(true);

    assertThatThrownBy(
            () ->
                service.create(
                    admin,
                    new CreateCommand(
                        "Ok",
                        null,
                        "m",
                        CATEGORY,
                        "TABLET",
                        1,
                        "TABLET",
                        "OTC",
                        "30041090",
                        5,
                        10,
                        false,
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
                        null,
                        CATEGORY,
                        "TABLET",
                        1,
                        "TABLET",
                        "OTC",
                        "30041090",
                        5,
                        10,
                        false,
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
                        "  ",
                        1,
                        "TABLET",
                        "OTC",
                        "30041090",
                        5,
                        10,
                        false,
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
                        1,
                        "  ",
                        "OTC",
                        "30041090",
                        5,
                        10,
                        false,
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
                        "  ",
                        "30041090",
                        5,
                        10,
                        false,
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
                        "Ok", "s", "m", CATEGORY, "TABLET", 1, "TABLET", "OTC", null, 5, 10, false,
                        null, null, null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_HSN_CODE");

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
                        "OTC",
                        "30041090",
                        null,
                        10,
                        false,
                        null,
                        null,
                        null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_GST_RATE");

    UUID id = UUID.randomUUID();
    when(store.findById(id))
        .thenReturn(
            Optional.of(
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
                    null,
                    false,
                    false,
                    null,
                    0,
                    0,
                    List.of(),
                    "d",
                    admin.subject(),
                    NOW,
                    NOW)));
    when(store.countExistingIds(List.of())).thenReturn(0);
    service.update(
        admin, id, new UpdateCommand(null, null, null, null, null, null, null, List.of(), null));

    assertThatThrownBy(() -> service.unban(null, id, "r"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");

    MedmatePrincipal compliance =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");
    when(store.list(any())).thenReturn(new ListResult(List.of(), 0));
    service.list(compliance, null, null, null, null, false, null, null, null, 1, 20);

    when(store.hsnExists("30041090")).thenReturn(true);
    service.create(
        admin,
        new CreateCommand(
            "Schedule X Med",
            "Morphine (10mg)",
            "Mfr",
            CATEGORY,
            "TABLET",
            10,
            "TABLET",
            "X",
            "30041090",
            5,
            100,
            false,
            null,
            List.of(),
            null));

    assertThatThrownBy(() -> service.ban(admin, id, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("REASON_REQUIRED");

    when(store.categoryActive(CATEGORY)).thenReturn(true);
    assertThatThrownBy(
            () ->
                service.create(
                    admin,
                    new CreateCommand(
                        "Blank Hsn",
                        "salt",
                        "mfr",
                        CATEGORY,
                        "TABLET",
                        1,
                        "TABLET",
                        "OTC",
                        "   ",
                        5,
                        10,
                        false,
                        null,
                        null,
                        null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_HSN_CODE");

    assertThatThrownBy(
            () ->
                service.create(
                    admin,
                    new CreateCommand(
                        "Bad Pack Unit",
                        "salt",
                        "mfr",
                        CATEGORY,
                        "TABLET",
                        1,
                        "NOPE",
                        "OTC",
                        "30041090",
                        5,
                        10,
                        false,
                        null,
                        null,
                        null)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    when(store.list(any()))
        .thenReturn(
            new ListResult(
                List.of(
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
                        null,
                        false,
                        false,
                        null,
                        0,
                        0,
                        List.of(),
                        "d",
                        admin.subject(),
                        NOW,
                        NOW)),
                1));
    assertThat(
            service
                .list(admin, null, null, null, null, false, null, null, null, 1, 20)
                .data()
                .get("medicines"))
        .asList()
        .hasSize(1);
  }
}
