package com.nammamedmate.catalogue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore;
import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore.AdminListResult;
import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore.AdminMappingRow;
import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore.ListResult;
import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore.MappingListRow;
import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore.MappingRow;
import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore.MedicineRef;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MappingServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-08T00:00:00Z");
  private static final UUID PHARMACY = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
  private static final UUID MEDICINE = UUID.fromString("11111111-2222-4333-8444-555555555555");

  @Mock private MedicineMappingStore store;
  @Mock private MedicineService medicineService;
  @Mock private ObjectProvider<BulkMapJobProcessor> processorProvider;
  @Mock private BulkMapJobProcessor processor;

  private MappingService service;

  private final MedmatePrincipal owner =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.PHARMACY_OWNER, PHARMACY, TokenScope.FULL, "j");
  private final MedmatePrincipal staff =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.PHARMACY_STAFF, PHARMACY, TokenScope.FULL, "j");
  private final MedmatePrincipal admin =
      new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
  private final MedmatePrincipal ops =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
  private final MedmatePrincipal compliance =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    doAnswer(
            inv -> {
              java.util.function.Consumer<BulkMapJobProcessor> c = inv.getArgument(0);
              c.accept(processor);
              return null;
            })
        .when(processorProvider)
        .ifAvailable(any());
    service =
        new MappingService(
            store,
            medicineService,
            processorProvider,
            new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC)),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void ac_priceAboveMrp_rejected() {
    when(store.pharmacyStatus(PHARMACY)).thenReturn(Optional.of("ACTIVE"));
    when(store.findMedicine(MEDICINE))
        .thenReturn(Optional.of(new MedicineRef(MEDICINE, "Augmentin", 21850L, null, "H", false)));

    assertThatThrownBy(() -> service.create(owner, MEDICINE, 220.00, 48))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PRICE_ABOVE_MRP");
    verify(store, never()).insert(any());
  }

  @Test
  void ac_createMapping_success() {
    when(store.pharmacyStatus(PHARMACY)).thenReturn(Optional.of("ACTIVE"));
    when(store.findMedicine(MEDICINE))
        .thenReturn(Optional.of(new MedicineRef(MEDICINE, "Augmentin", 21850L, null, "H", false)));
    when(store.exists(PHARMACY, MEDICINE)).thenReturn(false);

    Map<String, Object> data = service.create(owner, MEDICINE, 215.00, 48);

    assertThat(data)
        .containsEntry("medicine_name", "Augmentin")
        .containsEntry("stock_quantity", 48)
        .containsEntry("is_visible", true);
    assertThat(data.get("pharmacy_price")).isEqualTo(new BigDecimal("215.00"));
    verify(store).insert(any());
    verify(store).incrementMappedCount(MEDICINE, 1);
    verify(medicineService).assertOnlineStorefrontAllowed(MEDICINE);
  }

  @Test
  void ac_hideVisibility_keepsStock() {
    UUID mappingId = UUID.randomUUID();
    MappingRow existing = new MappingRow(mappingId, PHARMACY, MEDICINE, 21500L, 48, true, NOW, NOW);
    MappingRow hidden = new MappingRow(mappingId, PHARMACY, MEDICINE, 21500L, 48, false, NOW, NOW);
    when(store.findById(mappingId))
        .thenReturn(Optional.of(existing))
        .thenReturn(Optional.of(hidden));

    Map<String, Object> data = service.update(owner, mappingId, null, null, false);

    assertThat(data.get("updated_fields")).isEqualTo(List.of("is_visible"));
    assertThat(data).containsEntry("stock_quantity", 48).containsEntry("is_visible", false);
    verify(store).update(eq(mappingId), eq(null), eq(null), eq(false), eq(NOW));
  }

  @Test
  void ac_duplicateMapping_conflict() {
    when(store.pharmacyStatus(PHARMACY)).thenReturn(Optional.of("ACTIVE"));
    when(store.findMedicine(MEDICINE))
        .thenReturn(Optional.of(new MedicineRef(MEDICINE, "Augmentin", 21850L, null, "H", false)));
    when(store.exists(PHARMACY, MEDICINE)).thenReturn(true);

    assertThatThrownBy(() -> service.create(owner, MEDICINE, 215.00, 10))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MAPPING_ALREADY_EXISTS");
  }

  @Test
  void ac_scheduleX_rejected() {
    when(store.pharmacyStatus(PHARMACY)).thenReturn(Optional.of("ACTIVE"));
    when(store.findMedicine(MEDICINE))
        .thenReturn(Optional.of(new MedicineRef(MEDICINE, "Morphine", 10000L, null, "X", false)));
    doThrow(new AppException("SCHEDULE_X_NOT_AVAILABLE_ONLINE", "Schedule X not online", 409))
        .when(medicineService)
        .assertOnlineStorefrontAllowed(MEDICINE);

    assertThatThrownBy(() -> service.create(owner, MEDICINE, 90.00, 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("SCHEDULE_X_NOT_AVAILABLE_ONLINE");
  }

  @Test
  void ac_adminAboveCeilingFilter() {
    when(store.findMedicine(MEDICINE))
        .thenReturn(
            Optional.of(new MedicineRef(MEDICINE, "Augmentin", 21850L, 20000L, "H", false)));
    when(store.listForAdmin(any()))
        .thenReturn(
            new AdminListResult(
                List.of(
                    new AdminMappingRow(
                        UUID.randomUUID(),
                        PHARMACY,
                        "Sharma",
                        "Koramangala Zone",
                        21000L,
                        5,
                        true,
                        true,
                        NOW)),
                1,
                1));

    MappingService.PageResult result = service.adminList(admin, MEDICINE, null, null, true, 1, 20);

    assertThat(result.data().get("total_pharmacies_stocking")).isEqualTo(1L);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> pharmacies =
        (List<Map<String, Object>>) result.data().get("pharmacies");
    assertThat(pharmacies.getFirst()).containsEntry("is_above_ceiling", true);
  }

  @Test
  void ac_bulkMap_queuesJob() {
    when(store.findMedicine(MEDICINE))
        .thenReturn(Optional.of(new MedicineRef(MEDICINE, "Augmentin", 21850L, null, "H", false)));
    List<UUID> pharmacies = List.of(PHARMACY, UUID.randomUUID());

    Map<String, Object> data = service.bulkMap(ops, MEDICINE, pharmacies, true, null, 5);

    assertThat(data)
        .containsEntry("status", "QUEUED")
        .containsEntry("total_pharmacies", 2)
        .containsEntry("medicine_name", "Augmentin");
    assertThat(data.get("poll_url").toString()).startsWith("/api/v1/admin/bulk-jobs/");
    verify(store).insertBulkJob(any(), eq(pharmacies), any(), eq(ops.subject()), eq(NOW));
    verify(processor).processJob(any());
  }

  @Test
  void list_andDelete_andStaffPatch() {
    when(store.listForPharmacy(any()))
        .thenReturn(
            new ListResult(
                List.of(
                    new MappingListRow(
                        UUID.randomUUID(),
                        MEDICINE,
                        "Augmentin",
                        "Amox",
                        "GSK",
                        "Antibiotics",
                        "TABLET",
                        new BigDecimal("10"),
                        "H",
                        true,
                        21850L,
                        null,
                        21500L,
                        48,
                        true,
                        NOW,
                        NOW)),
                1));

    MappingService.PageResult page =
        service.list(staff, true, true, null, "aug", "pharmacy_price", "desc", 1, 20);
    assertThat(page.meta().total()).isEqualTo(1);

    UUID mappingId = UUID.randomUUID();
    MappingRow existing = new MappingRow(mappingId, PHARMACY, MEDICINE, 21500L, 48, true, NOW, NOW);
    when(store.findById(mappingId)).thenReturn(Optional.of(existing));
    when(store.findMedicine(MEDICINE))
        .thenReturn(
            Optional.of(new MedicineRef(MEDICINE, "Augmentin", 21850L, 21000L, "H", false)));

    Map<String, Object> patched = service.update(staff, mappingId, 200.00, 40, null);
    assertThat(patched.get("updated_fields"))
        .isEqualTo(List.of("pharmacy_price", "stock_quantity"));

    when(store.findById(mappingId)).thenReturn(Optional.of(existing));
    Map<String, Object> deleted = service.delete(owner, mappingId);
    assertThat(deleted).containsEntry("deleted", true).containsEntry("medicine_name", "Augmentin");
    verify(store).incrementMappedCount(MEDICINE, -1);
  }

  @Test
  void create_rejectsBannedCeilingNegativeStockInactiveAndDuplicateKey() {
    when(store.pharmacyStatus(PHARMACY)).thenReturn(Optional.of("SUSPENDED"));
    assertThatThrownBy(() -> service.create(owner, MEDICINE, 100, 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PHARMACY_NOT_ACTIVE");

    when(store.pharmacyStatus(PHARMACY)).thenReturn(Optional.of("ACTIVE"));
    when(store.findMedicine(MEDICINE))
        .thenReturn(Optional.of(new MedicineRef(MEDICINE, "X", 10000L, null, "H", true)));
    assertThatThrownBy(() -> service.create(owner, MEDICINE, 50, 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEDICINE_IS_BANNED");

    when(store.findMedicine(MEDICINE))
        .thenReturn(Optional.of(new MedicineRef(MEDICINE, "A", 10000L, 5000L, "H", false)));
    assertThatThrownBy(() -> service.create(owner, MEDICINE, 60.00, 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PRICE_ABOVE_CEILING");

    when(store.findMedicine(MEDICINE))
        .thenReturn(Optional.of(new MedicineRef(MEDICINE, "A", 10000L, null, "H", false)));
    assertThatThrownBy(() -> service.create(owner, MEDICINE, 50, -1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NEGATIVE_STOCK");

    when(store.exists(PHARMACY, MEDICINE)).thenReturn(false);
    doThrow(new DuplicateKeyException("dup")).when(store).insert(any());
    assertThatThrownBy(() -> service.create(owner, MEDICINE, 50, 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MAPPING_ALREADY_EXISTS");
  }

  @Test
  void bulkMap_validationsAndCreateForBulk() {
    RateLimiter unlimitedLimiter = mock(RateLimiter.class);
    when(unlimitedLimiter.tryAcquire(any(), any(Integer.class), any(Integer.class)))
        .thenReturn(true);
    // also match primitive int overloads
    when(unlimitedLimiter.tryAcquire(
            any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(true);
    MappingService unlimited =
        new MappingService(
            store,
            medicineService,
            processorProvider,
            unlimitedLimiter,
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertThatThrownBy(
            () -> unlimited.bulkMap(compliance, MEDICINE, List.of(PHARMACY), true, null, 0))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> unlimited.bulkMap(admin, MEDICINE, List.of(), true, null, 0))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PHARMACY_IDS_REQUIRED");
    assertThatThrownBy(
            () ->
                unlimited.bulkMap(
                    admin, MEDICINE, java.util.Collections.nCopies(201, PHARMACY), true, null, 0))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("TOO_MANY_PHARMACIES");

    when(store.findMedicine(MEDICINE)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> unlimited.bulkMap(admin, MEDICINE, List.of(PHARMACY), true, null, 0))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEDICINE_NOT_FOUND");

    when(store.findMedicine(MEDICINE))
        .thenReturn(Optional.of(new MedicineRef(MEDICINE, "A", 10000L, null, "H", true)));
    assertThatThrownBy(() -> unlimited.bulkMap(admin, MEDICINE, List.of(PHARMACY), true, null, 0))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEDICINE_IS_BANNED");

    when(store.findMedicine(MEDICINE))
        .thenReturn(Optional.of(new MedicineRef(MEDICINE, "A", 10000L, null, "H", false)));
    assertThatThrownBy(() -> unlimited.bulkMap(admin, MEDICINE, List.of(PHARMACY), false, null, 0))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> unlimited.bulkMap(admin, MEDICINE, List.of(PHARMACY), false, 120, 0))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PRICE_ABOVE_MRP");

    when(store.pharmacyStatus(PHARMACY)).thenReturn(Optional.of("ACTIVE"));
    when(store.exists(PHARMACY, MEDICINE)).thenReturn(false);
    unlimited.createForBulk(PHARMACY, MEDICINE, 9000L, 3);
    verify(store).insert(any());

    when(store.exists(PHARMACY, MEDICINE)).thenReturn(true);
    assertThatThrownBy(() -> unlimited.createForBulk(PHARMACY, MEDICINE, 9000L, 3))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MAPPING_ALREADY_EXISTS");
  }

  @Test
  void update_forbiddenWrongPharmacy_andPriceCeiling() {
    UUID mappingId = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    when(store.findById(mappingId))
        .thenReturn(
            Optional.of(new MappingRow(mappingId, other, MEDICINE, 10000L, 1, true, NOW, NOW)));
    assertThatThrownBy(() -> service.update(owner, mappingId, 90, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    when(store.findById(mappingId))
        .thenReturn(
            Optional.of(new MappingRow(mappingId, PHARMACY, MEDICINE, 10000L, 1, true, NOW, NOW)));
    when(store.findMedicine(MEDICINE))
        .thenReturn(Optional.of(new MedicineRef(MEDICINE, "A", 10000L, 8000L, "H", false)));
    assertThatThrownBy(() -> service.update(owner, mappingId, 90.00, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PRICE_ABOVE_CEILING");
  }

  @Test
  void parsePrice_branches() {
    assertThat(MappingService.parsePositivePricePaise(new BigDecimal("10.50"))).isEqualTo(1050L);
    assertThat(MappingService.parsePositivePricePaise("12.00")).isEqualTo(1200L);
    assertThatThrownBy(() -> MappingService.parsePositivePricePaise(null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> MappingService.parsePositivePricePaise("x"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> MappingService.parsePositivePricePaise(new BigDecimal("1.999")))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> MappingService.parsePositivePricePaise(0))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> MappingService.parsePositivePricePaise(Map.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void roleGuards_andMissingMedicine() {
    MedmatePrincipal customer =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.list(customer, null, null, null, null, null, null, 1, 20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.create(staff, MEDICINE, 10, 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.delete(staff, UUID.randomUUID()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.adminList(customer, MEDICINE, null, null, null, 1, 20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    when(store.pharmacyStatus(PHARMACY)).thenReturn(Optional.of("ACTIVE"));
    when(store.findMedicine(MEDICINE)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.create(owner, MEDICINE, 10, 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEDICINE_NOT_FOUND");
  }

  @Test
  void processorAbsent_stillQueues() {
    @SuppressWarnings("unchecked")
    ObjectProvider<BulkMapJobProcessor> empty = mock(ObjectProvider.class);
    MappingService noProc =
        new MappingService(
            store,
            medicineService,
            empty,
            new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC)),
            Clock.fixed(NOW, ZoneOffset.UTC));
    when(store.findMedicine(MEDICINE))
        .thenReturn(Optional.of(new MedicineRef(MEDICINE, "A", 10000L, null, "H", false)));
    Map<String, Object> data = noProc.bulkMap(admin, MEDICINE, List.of(PHARMACY), true, null, 0);
    assertThat(data).containsEntry("status", "QUEUED");
  }
}
