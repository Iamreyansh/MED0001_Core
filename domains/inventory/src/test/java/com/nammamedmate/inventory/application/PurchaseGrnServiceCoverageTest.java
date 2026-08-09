package com.nammamedmate.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.inventory.application.port.out.DistributorStore;
import com.nammamedmate.inventory.application.port.out.DistributorSupplyItemStore;
import com.nammamedmate.inventory.application.port.out.PharmacyProductStore;
import com.nammamedmate.inventory.application.port.out.ProductBatchStore;
import com.nammamedmate.inventory.application.port.out.PurchaseGrnStore;
import com.nammamedmate.inventory.domain.Distributor;
import com.nammamedmate.inventory.domain.GrnStatus;
import com.nammamedmate.inventory.domain.PharmacyProduct;
import com.nammamedmate.inventory.domain.PurchaseGrn;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PurchaseGrnServiceCoverageTest {

  private static final UUID PHARMACY = UUID.fromString("aaaaaaaa-0001-4000-8000-000000000099");
  private static final UUID DIST = UUID.fromString("bbbbbbbb-0001-4000-8000-000000000099");
  private static final Instant NOW = Instant.parse("2026-08-09T10:00:00Z");

  @Mock private PurchaseGrnStore grnStore;
  @Mock private DistributorStore distributorStore;
  @Mock private DistributorSupplyItemStore supplyItemStore;
  @Mock private PharmacyProductStore productStore;
  @Mock private ProductBatchStore batchStore;
  @Mock private RateLimiter rateLimiter;

  private PurchaseGrnService service;
  private MedmatePrincipal owner;

  @BeforeEach
  void setUp() {
    when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(true);
    service =
        new PurchaseGrnService(
            grnStore,
            distributorStore,
            supplyItemStore,
            productStore,
            batchStore,
            rateLimiter,
            Clock.fixed(NOW, ZoneOffset.UTC),
            new ObjectMapper());
    owner =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_OWNER, PHARMACY, TokenScope.FULL, "j");
  }

  @Test
  void validationBranches_distributorInactive_createNew_confirmImport_emptyStock() {
    when(distributorStore.findByIdIncludingDeleted(PHARMACY, DIST))
        .thenReturn(Optional.of(Distributor.minimal(DIST, PHARMACY, "X", false, NOW)));
    assertThatThrownBy(() -> service.create(owner, DIST, "I", LocalDate.of(2026, 7, 1)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("DISTRIBUTOR_INACTIVE");

    when(distributorStore.findByIdIncludingDeleted(PHARMACY, DIST)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.create(owner, DIST, "I", LocalDate.of(2026, 7, 1)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("DISTRIBUTOR_NOT_FOUND");

    UUID grnId = UUID.randomUUID();
    when(grnStore.findById(PHARMACY, grnId))
        .thenReturn(
            Optional.of(
                new PurchaseGrn(
                    grnId,
                    PHARMACY,
                    DIST,
                    "I",
                    LocalDate.of(2026, 7, 1),
                    GrnStatus.DRAFT,
                    null,
                    null,
                    owner.subject(),
                    null,
                    NOW,
                    NOW,
                    null)));
    when(productStore.insert(any())).thenAnswer(inv -> inv.getArgument(0));
    when(grnStore.insertItem(any())).thenAnswer(inv -> inv.getArgument(0));

    Map<String, Object> created =
        service.addItem(
            owner,
            grnId,
            null,
            null,
            true,
            "New Med",
            "Labs",
            10,
            "TABLET",
            "BN1",
            LocalDate.of(2027, 1, 1),
            null,
            5,
            0,
            new BigDecimal("10.00"),
            new BigDecimal("20.00"),
            new BigDecimal("5"));
    assertThat(created.get("is_new_product")).isEqualTo(true);

    when(grnStore.listItems(PHARMACY, grnId)).thenReturn(List.of());
    assertThatThrownBy(() -> service.saveAndStock(owner, grnId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("GRN_EMPTY");

    assertThatThrownBy(() -> service.create(owner, DIST, "FUT", LocalDate.of(2099, 1, 1)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("DISTRIBUTOR_NOT_FOUND");

    when(distributorStore.findByIdIncludingDeleted(PHARMACY, DIST))
        .thenReturn(Optional.of(Distributor.minimal(DIST, PHARMACY, "X", true, NOW)));
    assertThatThrownBy(() -> service.create(owner, DIST, "FUT", LocalDate.of(2099, 1, 1)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FUTURE_INVOICE_DATE");

    when(grnStore.findById(PHARMACY, grnId))
        .thenReturn(
            Optional.of(
                new PurchaseGrn(
                    grnId,
                    PHARMACY,
                    DIST,
                    "I",
                    LocalDate.of(2026, 7, 1),
                    GrnStatus.DRAFT,
                    null,
                    null,
                    owner.subject(),
                    "[]",
                    NOW,
                    NOW,
                    null)));
    when(grnStore.updateImportUnmatched(any(), any(), any()))
        .thenAnswer(inv -> inv.getArgument(0) == null ? null : draft(grnId, null));
    Map<String, Object> confirmed = service.confirmImport(owner, grnId);
    assertThat(confirmed.get("items_created")).isEqualTo(0);

    String pending =
        """
        [{"row_number":2,"csv_row":{"product_name":"U1","manufacturer":"M","batch_number":"B1",\
        "expiry_date":"2027-06-30","quantity":"3","free_quantity":"1","purchase_price":"10.00",\
        "mrp":"15.00","gst_pct":"12"}}]
        """;
    when(grnStore.findById(PHARMACY, grnId)).thenReturn(Optional.of(draft(grnId, pending)));
    when(grnStore.updateImportUnmatched(eq(grnId), isNull(), any())).thenReturn(draft(grnId, null));
    Map<String, Object> createdItems = service.confirmImport(owner, grnId);
    assertThat(createdItems.get("items_created")).isEqualTo(1);

    MockMultipartFile bad =
        new MockMultipartFile(
            "csv_file", "x.csv", "text/csv", "a,b\n1,2".getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> service.importCsv(owner, bad, DIST, "C1", LocalDate.of(2026, 7, 1)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_CSV_FORMAT");

    assertThatThrownBy(
            () ->
                service.importCsv(
                    owner,
                    new MockMultipartFile(
                        "csv_file", "x.csv", "text/csv", new byte[(int) (5L * 1024 * 1024 + 1)]),
                    DIST,
                    "C2",
                    LocalDate.of(2026, 7, 1)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FILE_TOO_LARGE");

    when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(false);
    assertThatThrownBy(() -> service.list(owner, null, null, null, null, null, 1, 20))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RATE_LIMIT_EXCEEDED");

    assertThat(PurchaseGrnService.paiseToRupees(100)).isEqualByComparingTo("1.00");
    assertThat(PurchaseGrnService.rupeesToPaise(new BigDecimal("1.50"), "x")).isEqualTo(150L);
    assertThatThrownBy(() -> PurchaseGrnService.rupeesToPaise(null, "x"))
        .isInstanceOf(AppException.class);
  }

  @Test
  void authAndSearchAndInvalidGst() {
    MedmatePrincipal noPharmacy =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_OWNER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.list(noPharmacy, null, null, null, null, null, 1, 20))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    assertThatThrownBy(() -> service.list(null, null, null, null, null, null, 1, 20))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
    MedmatePrincipal customer =
        new MedmatePrincipal(UUID.randomUUID(), AuthRole.CUSTOMER, PHARMACY, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.list(customer, null, null, null, null, null, 1, 20))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    UUID grnId = UUID.randomUUID();
    when(grnStore.findById(PHARMACY, grnId)).thenReturn(Optional.of(draft(grnId, null)));
    when(productStore.searchByName(eq(PHARMACY), anyString(), anyInt())).thenReturn(List.of());
    assertThatThrownBy(
            () ->
                service.addItem(
                    owner,
                    grnId,
                    "missing",
                    null,
                    false,
                    null,
                    null,
                    null,
                    null,
                    "BN",
                    LocalDate.of(2027, 1, 1),
                    null,
                    1,
                    0,
                    new BigDecimal("1.00"),
                    new BigDecimal("2.00"),
                    new BigDecimal("12")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PRODUCT_NOT_FOUND");

    PharmacyProduct p =
        new PharmacyProduct(
            UUID.randomUUID(),
            PHARMACY,
            null,
            "Found",
            null,
            "M",
            1,
            "u",
            null,
            null,
            "TABLET",
            "OTC",
            null,
            BigDecimal.TEN,
            100,
            false,
            false,
            false,
            0,
            List.of(),
            0,
            0,
            null,
            0,
            null,
            null,
            NOW,
            NOW);
    when(productStore.searchByName(eq(PHARMACY), anyString(), anyInt())).thenReturn(List.of(p));
    when(grnStore.insertItem(any())).thenAnswer(inv -> inv.getArgument(0));
    Map<String, Object> viaSearch =
        service.addItem(
            owner,
            grnId,
            "Found",
            null,
            false,
            null,
            null,
            null,
            null,
            "BN2",
            LocalDate.of(2027, 1, 1),
            null,
            1,
            0,
            new BigDecimal("1.00"),
            new BigDecimal("2.00"),
            new BigDecimal("12"));
    assertThat(viaSearch.get("product_name")).isEqualTo("Found");

    assertThatThrownBy(
            () ->
                service.addItem(
                    owner,
                    grnId,
                    null,
                    p.id(),
                    false,
                    null,
                    null,
                    null,
                    null,
                    "BN3",
                    LocalDate.of(2027, 1, 1),
                    null,
                    1,
                    0,
                    new BigDecimal("1.00"),
                    new BigDecimal("2.00"),
                    new BigDecimal("7")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_GST_PCT");

    when(grnStore.findById(PHARMACY, grnId))
        .thenReturn(
            Optional.of(
                new PurchaseGrn(
                    grnId,
                    PHARMACY,
                    DIST,
                    "I",
                    LocalDate.of(2026, 7, 1),
                    GrnStatus.STOCKED,
                    NOW,
                    owner.subject(),
                    owner.subject(),
                    null,
                    NOW,
                    NOW,
                    null)));
    assertThatThrownBy(
            () ->
                service.addItem(
                    owner,
                    grnId,
                    null,
                    p.id(),
                    false,
                    null,
                    null,
                    null,
                    null,
                    "BN",
                    LocalDate.of(2027, 1, 1),
                    null,
                    1,
                    0,
                    new BigDecimal("1.00"),
                    new BigDecimal("2.00"),
                    new BigDecimal("12")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("GRN_ALREADY_STOCKED");

    assertThatThrownBy(() -> service.list(owner, "NOPE", null, null, null, null, 1, 20))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  private PurchaseGrn draft(UUID grnId, String unmatched) {
    return new PurchaseGrn(
        grnId,
        PHARMACY,
        DIST,
        "I",
        LocalDate.of(2026, 7, 1),
        GrnStatus.DRAFT,
        null,
        null,
        owner.subject(),
        unmatched,
        NOW,
        NOW,
        null);
  }
}
