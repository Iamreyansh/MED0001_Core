package com.nammamedmate.catalogue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore;
import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore.ListResult;
import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore.MappingListRow;
import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore.MappingRow;
import com.nammamedmate.catalogue.application.port.out.MedicineMappingStore.MedicineRef;
import com.nammamedmate.kernel.api.PaginationMeta;
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
class MappingServiceFinalCoverageTest {

  private static final Instant NOW = Instant.parse("2026-08-08T00:00:00Z");
  private static final UUID PHARMACY = UUID.randomUUID();
  private static final UUID MEDICINE = UUID.randomUUID();

  @Mock private MedicineMappingStore store;
  @Mock private MedicineService medicineService;
  @Mock private ObjectProvider<BulkMapJobProcessor> processorProvider;

  private MappingService service;
  private MedmatePrincipal owner;

  @BeforeEach
  void setUp() {
    owner =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_OWNER, PHARMACY, TokenScope.FULL, "j");
    service =
        new MappingService(
            store,
            medicineService,
            processorProvider,
            new InMemoryRateLimiter(Clock.fixed(NOW, ZoneOffset.UTC)),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void pageResultNullData_andListBlankSort_andCeilingCreate() {
    assertThat(new MappingService.PageResult(null, PaginationMeta.of(1, 20, 0)).data()).isEmpty();

    when(store.listForPharmacy(any()))
        .thenReturn(
            new ListResult(
                List.of(
                    new MappingListRow(
                        UUID.randomUUID(),
                        MEDICINE,
                        "A",
                        "s",
                        "m",
                        null,
                        "TABLET",
                        BigDecimal.ONE,
                        "H",
                        true,
                        100L,
                        90L,
                        80L,
                        1,
                        true,
                        NOW,
                        NOW)),
                1));
    MappingService.PageResult page = service.list(owner, null, null, null, null, "  ", "  ", 1, 20);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> mappings = (List<Map<String, Object>>) page.data().get("mappings");
    assertThat(mappings.getFirst().get("category")).isEqualTo(Map.of("name", ""));
    assertThat(mappings.getFirst().get("mrp_ceiling")).isEqualTo(new BigDecimal("0.90"));

    when(store.pharmacyStatus(PHARMACY)).thenReturn(Optional.of("ACTIVE"));
    when(store.findMedicine(MEDICINE))
        .thenReturn(Optional.of(new MedicineRef(MEDICINE, "A", 10000L, 9000L, "H", false)));
    when(store.exists(PHARMACY, MEDICINE)).thenReturn(false);
    Map<String, Object> created = service.create(owner, MEDICINE, 80.00, 1);
    assertThat(created.get("mrp_ceiling")).isEqualTo(new BigDecimal("90.00"));
  }

  @Test
  void updateMedicineMissing_deleteFallbackName_pharmacyNotFound() {
    UUID mappingId = UUID.randomUUID();
    when(store.findById(mappingId))
        .thenReturn(
            Optional.of(new MappingRow(mappingId, PHARMACY, MEDICINE, 10000L, 1, true, NOW, NOW)));
    when(store.findMedicine(MEDICINE)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.update(owner, mappingId, 50.00, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("MEDICINE_NOT_FOUND");

    when(store.findById(mappingId))
        .thenReturn(
            Optional.of(new MappingRow(mappingId, PHARMACY, MEDICINE, 10000L, 1, true, NOW, NOW)));
    when(store.findMedicine(MEDICINE)).thenReturn(Optional.empty());
    Map<String, Object> deleted = service.delete(owner, mappingId);
    assertThat(deleted).containsEntry("medicine_name", "Medicine");

    when(store.pharmacyStatus(PHARMACY)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.create(owner, MEDICINE, 10, 1))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PHARMACY_NOT_FOUND");
  }

  @Test
  void priceSameAsExisting_skipsUpdateField() {
    UUID mappingId = UUID.randomUUID();
    when(store.findById(mappingId))
        .thenReturn(
            Optional.of(new MappingRow(mappingId, PHARMACY, MEDICINE, 10000L, 5, true, NOW, NOW)));
    when(store.findMedicine(MEDICINE))
        .thenReturn(Optional.of(new MedicineRef(MEDICINE, "A", 20000L, null, "H", false)));
    Map<String, Object> data = service.update(owner, mappingId, 100.00, null, null);
    assertThat(data.get("updated_fields")).isEqualTo(List.of());
  }

  @Test
  void compactors_nullRows() {
    assertThat(new MedicineMappingStore.ListResult(null, 0).rows()).isEmpty();
    assertThat(new MedicineMappingStore.AdminListResult(null, 0, 0).rows()).isEmpty();
    assertThat(
            new MedicineMappingStore.BulkJobRow(
                    UUID.randomUUID(), "a", "s", null, Map.of(), UUID.randomUUID(), NOW)
                .pharmacyIds())
        .isEmpty();
  }

  @Test
  void remainingBranches_nullStock_nullPharmacyIds_adminRoles_pageLt1() {
    when(store.pharmacyStatus(PHARMACY)).thenReturn(Optional.of("ACTIVE"));
    assertThatThrownBy(() -> service.create(owner, MEDICINE, 10, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("NEGATIVE_STOCK");

    MedmatePrincipal admin =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    MedmatePrincipal ops =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
    MedmatePrincipal compliance =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.ADMIN_COMPLIANCE, null, TokenScope.FULL, "j");

    assertThatThrownBy(() -> service.bulkMap(admin, MEDICINE, null, true, null, 0))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PHARMACY_IDS_REQUIRED");

    when(store.findMedicine(MEDICINE))
        .thenReturn(Optional.of(new MedicineRef(MEDICINE, "A", 100L, null, "H", false)));
    when(store.listForAdmin(any()))
        .thenReturn(new MedicineMappingStore.AdminListResult(List.of(), 0, 0));
    assertThat(service.adminList(ops, MEDICINE, null, null, null, 0, 5).meta().page()).isEqualTo(1);
    assertThat(service.adminList(compliance, MEDICINE, null, null, null, 2, null).meta().limit())
        .isEqualTo(20);
  }
}
