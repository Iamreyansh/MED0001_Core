package com.nammamedmate.catalogue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore;
import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore.AdminListResult;
import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore.ListResult;
import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore.MappingRow;
import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore.MedicineRef;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
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
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class MappingServiceCoverageTest {

  private static final Instant NOW = Instant.parse("2026-08-08T00:00:00Z");
  private static final UUID PHARMACY = UUID.randomUUID();
  private static final UUID MEDICINE = UUID.randomUUID();

  @Mock private MedicineMappingStore store;
  @Mock private MedicineService medicineService;
  @Mock private ObjectProvider<BulkMapJobProcessor> processorProvider;
  @Mock private RateLimiter rateLimiter;

  private MappingService service;
  private MedmatePrincipal owner;

  @BeforeEach
  void setUp() {
    owner =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_OWNER, PHARMACY, TokenScope.FULL, "j");
    when(rateLimiter.tryAcquire(any(), anyInt(), anyInt())).thenReturn(true);
    service =
        new MappingService(
            store,
            medicineService,
            processorProvider,
            rateLimiter,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void list_defaultsAndRateLimit() {
    when(store.listForPharmacy(any())).thenReturn(new ListResult(List.of(), 0));
    service.list(owner, null, null, null, null, "bogus", "nope", 0, 999);
    service.list(owner, false, false, null, "  ", null, null, null, null);

    when(rateLimiter.tryAcquire(any(), anyInt(), anyInt())).thenReturn(false);
    assertThatThrownBy(() -> service.list(owner, null, null, null, null, null, null, 1, 20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMIT_EXCEEDED");
  }

  @Test
  void update_noopAndNegativeStockAndNotFound() {
    UUID id = UUID.randomUUID();
    MappingRow row = new MappingRow(id, PHARMACY, MEDICINE, 10000L, 5, true, NOW, NOW);
    when(store.findById(id)).thenReturn(Optional.of(row));

    Map<String, Object> noop = service.update(owner, id, null, 5, true);
    assertThat(noop.get("updated_fields")).isEqualTo(List.of());

    assertThatThrownBy(() -> service.update(owner, id, null, -1, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NEGATIVE_STOCK");

    when(store.findById(id)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.update(owner, id, null, null, false))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MAPPING_NOT_FOUND");
  }

  @Test
  void delete_forbiddenAndNotFound_adminListNotFound() {
    UUID id = UUID.randomUUID();
    when(store.findById(id))
        .thenReturn(
            Optional.of(new MappingRow(id, UUID.randomUUID(), MEDICINE, 1L, 0, true, NOW, NOW)));
    assertThatThrownBy(() -> service.delete(owner, id))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");

    when(store.findById(id)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.delete(owner, id))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MAPPING_NOT_FOUND");

    MedmatePrincipal admin =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    when(store.findMedicine(MEDICINE)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.adminList(admin, MEDICINE, null, null, null, 1, 20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEDICINE_NOT_FOUND");

    when(store.findMedicine(MEDICINE))
        .thenReturn(Optional.of(new MedicineRef(MEDICINE, "A", 100L, null, "H", false)));
    when(store.listForAdmin(any())).thenReturn(new AdminListResult(List.of(), 0, 0));
    assertThat(
            service
                .adminList(admin, MEDICINE, UUID.randomUUID(), true, false, null, null)
                .meta()
                .total())
        .isZero();
  }

  @Test
  void create_nullMedicineAndMissingPharmacyContext() {
    when(store.pharmacyStatus(PHARMACY)).thenReturn(Optional.of("ACTIVE"));
    assertThatThrownBy(() -> service.create(owner, null, 10, 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    MedmatePrincipal noPharmacy =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_OWNER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.list(noPharmacy, null, null, null, null, null, null, 1, 20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");

    assertThatThrownBy(() -> service.list(null, null, null, null, null, null, null, 1, 20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> service.adminList(null, MEDICINE, null, null, null, 1, 20))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> service.bulkMap(null, MEDICINE, List.of(PHARMACY), true, null, 0))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void bulkMap_moreValidations() {
    MedmatePrincipal admin =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.bulkMap(admin, null, List.of(PHARMACY), true, null, 0))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.bulkMap(admin, MEDICINE, List.of(PHARMACY), null, null, 0))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");

    when(store.findMedicine(MEDICINE))
        .thenReturn(Optional.of(new MedicineRef(MEDICINE, "A", 10000L, null, "H", false)));
    assertThatThrownBy(() -> service.bulkMap(admin, MEDICINE, List.of(PHARMACY), true, null, -1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NEGATIVE_STOCK");

    when(store.pharmacyStatus(PHARMACY)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.createForBulk(PHARMACY, MEDICINE, 1000L, 0))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PHARMACY_NOT_FOUND");

    when(store.pharmacyStatus(PHARMACY)).thenReturn(Optional.of("PENDING_KYC"));
    assertThatThrownBy(() -> service.createForBulk(PHARMACY, MEDICINE, 1000L, 0))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PHARMACY_NOT_ACTIVE");

    ObjectProvider<BulkMapJobProcessor> empty = mock(ObjectProvider.class);
    MappingService s =
        new MappingService(
            store, medicineService, empty, rateLimiter, Clock.fixed(NOW, ZoneOffset.UTC));
    Map<String, Object> data = s.bulkMap(admin, MEDICINE, List.of(PHARMACY), false, 50.00, null);
    assertThat(data).containsEntry("total_pharmacies", 1);
  }
}
