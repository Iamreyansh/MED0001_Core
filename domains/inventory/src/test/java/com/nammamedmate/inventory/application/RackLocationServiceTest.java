package com.nammamedmate.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.inventory.application.port.out.RackLocationStore;
import com.nammamedmate.inventory.application.port.out.RackLocationStore.Kpi;
import com.nammamedmate.inventory.application.port.out.RackLocationStore.ListResult;
import com.nammamedmate.inventory.application.port.out.RackLocationStore.ListRow;
import com.nammamedmate.inventory.application.port.out.RackLocationStore.ProductPreview;
import com.nammamedmate.inventory.application.port.out.RackLocationStore.UnlocatedPage;
import com.nammamedmate.inventory.domain.PharmacyProduct;
import com.nammamedmate.inventory.domain.RackLocation;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RackLocationServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-09T10:00:00Z");
  private static final UUID PHARMACY = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
  private static final UUID PRODUCT = UUID.fromString("11111111-2222-4333-8444-555555555555");
  private static final UUID PRODUCT2 = UUID.fromString("22222222-3333-4444-8555-666666666666");
  private static final UUID PRODUCT3 = UUID.fromString("33333333-4444-4555-8666-777777777777");

  @Mock private RackLocationStore store;
  @Mock private RateLimiter rateLimiter;

  private RackLocationService service;

  private final MedmatePrincipal owner =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.PHARMACY_OWNER, PHARMACY, TokenScope.FULL, "j");
  private final MedmatePrincipal staff =
      new MedmatePrincipal(
          UUID.randomUUID(), AuthRole.PHARMACY_STAFF, PHARMACY, TokenScope.FULL, "j");

  @BeforeEach
  void setUp() {
    when(rateLimiter.tryAcquire(any(), anyInt(), anyInt())).thenReturn(true);
    service = new RackLocationService(store, rateLimiter, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void ac_createValidRack_returns201Payload() {
    when(store.findByCode(PHARMACY, "Z99-99")).thenReturn(Optional.empty());
    when(store.insert(any())).thenAnswer(inv -> inv.getArgument(0));

    Map<String, Object> data = service.create(owner, "z99-99", "Zone Z", "OTC vitamins");

    assertThat(data.get("rack_code")).isEqualTo("Z99-99");
    assertThat(data.get("medicine_count")).isEqualTo(0);
    ArgumentCaptor<RackLocation> cap = ArgumentCaptor.forClass(RackLocation.class);
    verify(store).insert(cap.capture());
    assertThat(cap.getValue().zoneName()).isEqualTo("Zone Z");
  }

  @Test
  void ac_createInvalidRackCode_returnsInvalidFormat() {
    assertThatThrownBy(() -> service.create(owner, "invalid_code", "Zone A", null))
        .isInstanceOf(AppException.class)
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_RACK_CODE_FORMAT");
  }

  @Test
  void createDuplicate_returnsExists() {
    when(store.findByCode(PHARMACY, "A1-01")).thenReturn(Optional.of(sampleRack("A1-01")));
    assertThatThrownBy(() -> service.create(owner, "A1-01", "Zone A", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RACK_CODE_EXISTS");
  }

  @Test
  void createRace_duplicateKeyMapped() {
    when(store.findByCode(PHARMACY, "A1-01")).thenReturn(Optional.empty());
    when(store.insert(any())).thenThrow(new DuplicateKeyException("dup"));
    assertThatThrownBy(() -> service.create(owner, "A1-01", "Zone A", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RACK_CODE_EXISTS");
  }

  @Test
  void createValidationBranches() {
    assertThatThrownBy(() -> service.create(owner, "A1-01", " ", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.create(owner, "A1-01", "x".repeat(101), null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.create(owner, "A1-01", "Zone", "y".repeat(301)))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.create(staff, "A1-01", "Zone", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void ac_deleteNonEmpty_returnsRackNotEmptyWithProducts() {
    when(store.findByCode(PHARMACY, "A1-01")).thenReturn(Optional.of(sampleRack("A1-01")));
    when(store.blockingProducts(PHARMACY, "A1-01", 50))
        .thenReturn(List.of(new ProductPreview(PRODUCT, "Amoxicillin 250mg Cap")));

    assertThatThrownBy(() -> service.delete(owner, "A1-01"))
        .isInstanceOf(AppException.class)
        .satisfies(
            ex -> {
              AppException ae = (AppException) ex;
              assertThat(ae.code()).isEqualTo("RACK_NOT_EMPTY");
              assertThat(ae.details()).containsKey("products");
            });
    verify(store, never()).softDelete(any(), any(), any());
  }

  @Test
  void deleteEmpty_succeeds() {
    when(store.findByCode(PHARMACY, "C3-07")).thenReturn(Optional.of(sampleRack("C3-07")));
    when(store.blockingProducts(PHARMACY, "C3-07", 50)).thenReturn(List.of());
    when(store.softDelete(eq(PHARMACY), eq("C3-07"), eq(NOW)))
        .thenReturn(Optional.of(sampleRack("C3-07")));

    Map<String, Object> data = service.delete(owner, "C3-07");
    assertThat(data.get("rack_code")).isEqualTo("C3-07");
    assertThat(data.get("deleted_at")).isEqualTo(NOW.toString());
  }

  @Test
  void deleteNotFound() {
    when(store.findByCode(PHARMACY, "A1-01")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.delete(owner, "A1-01"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RACK_NOT_FOUND");
  }

  @Test
  void ac_assignThreeProducts_appendsRack() {
    when(store.findByCode(PHARMACY, "A1-01")).thenReturn(Optional.of(sampleRack("A1-01")));
    when(store.assignRack(eq(PHARMACY), anyList(), eq("A1-01"), eq(NOW)))
        .thenReturn(List.of(PRODUCT, PRODUCT2, PRODUCT3));

    Map<String, Object> data = service.assign(staff, List.of(PRODUCT, PRODUCT2, PRODUCT3), "A1-01");

    assertThat(data.get("assigned_count")).isEqualTo(3);
    assertThat(data.get("skipped_count")).isEqualTo(0);
  }

  @Test
  void ac_assignIdempotent_skipsAlreadyAssigned() {
    when(store.findByCode(PHARMACY, "A1-01")).thenReturn(Optional.of(sampleRack("A1-01")));
    when(store.assignRack(eq(PHARMACY), anyList(), eq("A1-01"), eq(NOW))).thenReturn(List.of());

    Map<String, Object> data = service.assign(staff, List.of(PRODUCT), "A1-01");
    assertThat(data.get("assigned_count")).isEqualTo(0);
    assertThat(data.get("skipped_count")).isEqualTo(1);
  }

  @Test
  void assignEmptyAndNotFound() {
    assertThatThrownBy(() -> service.assign(staff, List.of(), "A1-01"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("EMPTY_PRODUCT_LIST");
    when(store.findByCode(PHARMACY, "A1-01")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.assign(staff, List.of(PRODUCT), "A1-01"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RACK_NOT_FOUND");
  }

  @Test
  void ac_printLabels_returnsDataUrl() {
    when(store.findByCodes(eq(PHARMACY), anyList()))
        .thenReturn(List.of(sampleRack("A1-01"), sampleRack("B2-03")));
    when(store.medicineCount(eq(PHARMACY), any())).thenReturn(2L);

    Map<String, Object> data = service.printLabels(staff, List.of("A1-01", "B2-03"));

    assertThat(data.get("label_count")).isEqualTo(2);
    assertThat(data.get("pdf_url").toString()).startsWith("data:application/pdf;base64,");
    assertThat(data.get("expires_at")).isEqualTo(NOW.plusSeconds(7200).toString());
  }

  @Test
  void printLabelsErrors() {
    assertThatThrownBy(() -> service.printLabels(staff, List.of()))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("EMPTY_RACK_CODES");
    List<String> tooMany = new ArrayList<>();
    for (int i = 0; i < 121; i++) {
      tooMany.add("A1-" + String.format("%02d", i % 100));
    }
    assertThatThrownBy(() -> service.printLabels(staff, tooMany))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("TOO_MANY_LABELS");
    when(store.findByCodes(eq(PHARMACY), anyList())).thenReturn(List.of(sampleRack("A1-01")));
    assertThatThrownBy(() -> service.printLabels(staff, List.of("A1-01", "B2-03")))
        .satisfies(
            ex -> {
              AppException ae = (AppException) ex;
              assertThat(ae.code()).isEqualTo("RACK_CODES_NOT_FOUND");
              assertThat(ae.details().get("invalid_rack_codes")).isEqualTo(List.of("B2-03"));
            });
  }

  @Test
  void ac_unlocated_onlyEmptyRacks() {
    PharmacyProduct unlocated = sampleProduct(List.of());
    when(store.unlocated(PHARMACY, 1, 20)).thenReturn(new UnlocatedPage(List.of(unlocated), 1));

    RackLocationService.PageResult page = service.unlocated(staff, null, null);

    assertThat(page.data().get("unlocated_count")).isEqualTo(1L);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> products = (List<Map<String, Object>>) page.data().get("products");
    assertThat(products).hasSize(1);
    assertThat(products.get(0).get("product_id")).isEqualTo(PRODUCT.toString());
  }

  @Test
  void listAndDetail() {
    RackLocation rack = sampleRack("A1-01");
    when(store.list(any()))
        .thenReturn(
            new ListResult(
                List.of(new ListRow(rack, 1, List.of(sampleProduct(List.of("A1-01"))))), 1));
    when(store.kpi(PHARMACY)).thenReturn(new Kpi(1, 1, 1, 0));

    RackLocationService.PageResult list = service.list(staff, "Zone A", "A1", 1, 50);
    assertThat(list.meta().get("total")).isEqualTo(1L);
    assertThat(list.data().get("kpi")).isInstanceOf(Map.class);

    when(store.findByCode(PHARMACY, "A1-01")).thenReturn(Optional.of(rack));
    when(store.medicinesInRack(PHARMACY, "A1-01"))
        .thenReturn(List.of(sampleProduct(List.of("A1-01"))));
    Map<String, Object> detail = service.detail(staff, "A1-01");
    assertThat(detail.get("medicine_count")).isEqualTo(1);
  }

  @Test
  void patchProductRackAddRemove() {
    when(store.findByCode(PHARMACY, "A1-01")).thenReturn(Optional.of(sampleRack("A1-01")));
    when(store.addRackToProduct(PHARMACY, PRODUCT, "A1-01", NOW))
        .thenReturn(Optional.of(sampleProduct(List.of("A1-01"))));
    Map<String, Object> added = service.patchProductRack(staff, PRODUCT, "A1-01", "ADD");
    assertThat(added.get("rack_locations")).isEqualTo(List.of("A1-01"));

    when(store.removeRackFromProduct(PHARMACY, PRODUCT, "A1-01", NOW))
        .thenReturn(Optional.of(sampleProduct(List.of())));
    Map<String, Object> removed = service.patchProductRack(staff, PRODUCT, "A1-01", "REMOVE");
    assertThat(removed.get("rack_locations")).isEqualTo(List.of());

    assertThatThrownBy(() -> service.patchProductRack(staff, PRODUCT, "A1-01", "MOVE"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("VALIDATION_ERROR");
    when(store.findByCode(PHARMACY, "B2-03")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.patchProductRack(staff, PRODUCT, "B2-03", "ADD"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RACK_NOT_FOUND");
    when(store.addRackToProduct(PHARMACY, PRODUCT, "A1-01", NOW)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.patchProductRack(staff, PRODUCT, "A1-01", null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PRODUCT_NOT_FOUND");
    when(store.removeRackFromProduct(PHARMACY, PRODUCT, "A1-01", NOW)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.patchProductRack(staff, PRODUCT, "A1-01", "REMOVE"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("PRODUCT_NOT_FOUND");
  }

  @Test
  void authAndRateLimitBranches() {
    assertThatThrownBy(() -> service.list(null, null, null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");
    MedmatePrincipal customer =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.list(customer, null, null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("FORBIDDEN");
    MedmatePrincipal noPharmacy =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_OWNER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.list(noPharmacy, null, null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("UNAUTHORIZED");

    when(rateLimiter.tryAcquire(any(), anyInt(), anyInt())).thenReturn(false);
    assertThatThrownBy(() -> service.list(owner, null, null, null, null))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RATE_LIMIT_EXCEEDED");
  }

  @Test
  void softDeleteRace_notFound() {
    when(store.findByCode(PHARMACY, "A1-01")).thenReturn(Optional.of(sampleRack("A1-01")));
    when(store.blockingProducts(any(), any(), anyInt())).thenReturn(List.of());
    when(store.softDelete(any(), any(), any())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.delete(owner, "A1-01"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RACK_NOT_FOUND");
  }

  @Test
  void detailNotFound_andAssignDedup() {
    when(store.findByCode(PHARMACY, "A1-01")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.detail(staff, "A1-01"))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("RACK_NOT_FOUND");

    when(store.findByCode(PHARMACY, "A1-01")).thenReturn(Optional.of(sampleRack("A1-01")));
    when(store.assignRack(eq(PHARMACY), eq(List.of(PRODUCT)), eq("A1-01"), eq(NOW)))
        .thenReturn(List.of(PRODUCT));
    service.assign(staff, java.util.Arrays.asList(PRODUCT, PRODUCT, null), "A1-01");
    verify(store).assignRack(eq(PHARMACY), eq(List.of(PRODUCT)), eq("A1-01"), eq(NOW));
  }

  @Test
  void normalizeBlankCode() {
    assertThatThrownBy(() -> RackLocationService.normalizeCode("  "))
        .extracting(ex -> ((AppException) ex).code())
        .isEqualTo("INVALID_RACK_CODE_FORMAT");
  }

  private static RackLocation sampleRack(String code) {
    return new RackLocation(UUID.randomUUID(), PHARMACY, code, "Zone A", "desc", NOW, NOW, null);
  }

  private static PharmacyProduct sampleProduct(List<String> racks) {
    return new PharmacyProduct(
        PRODUCT,
        PHARMACY,
        null,
        "Amoxicillin 250mg Cap",
        null,
        null,
        10,
        "capsules",
        null,
        "Antibiotics",
        "CAPSULE",
        "H",
        null,
        BigDecimal.valueOf(12),
        4500L,
        true,
        false,
        false,
        0,
        racks,
        200,
        1,
        LocalDate.of(2027, 2, 28),
        0L,
        null,
        null,
        NOW,
        NOW);
  }
}
