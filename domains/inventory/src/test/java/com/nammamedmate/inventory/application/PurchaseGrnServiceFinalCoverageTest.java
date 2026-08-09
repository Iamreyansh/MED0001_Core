package com.nammamedmate.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.inventory.application.port.out.DistributorStore;
import com.nammamedmate.inventory.application.port.out.DistributorSupplyItemStore;
import com.nammamedmate.inventory.application.port.out.PharmacyProductStore;
import com.nammamedmate.inventory.application.port.out.ProductBatchStore;
import com.nammamedmate.inventory.application.port.out.PurchaseGrnStore;
import com.nammamedmate.inventory.application.port.out.PurchaseGrnStore.ItemWithProduct;
import com.nammamedmate.inventory.application.port.out.PurchaseGrnStore.KpiRow;
import com.nammamedmate.inventory.application.port.out.PurchaseGrnStore.ListResult;
import com.nammamedmate.inventory.domain.Distributor;
import com.nammamedmate.inventory.domain.GrnStatus;
import com.nammamedmate.inventory.domain.PurchaseGrn;
import com.nammamedmate.inventory.domain.PurchaseGrnItem;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
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
import org.springframework.web.multipart.MultipartFile;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PurchaseGrnServiceFinalCoverageTest {

  private static final UUID PHARMACY = UUID.randomUUID();
  private static final UUID DIST = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2026-08-09T10:00:00Z");

  @Mock private PurchaseGrnStore grnStore;
  @Mock private DistributorStore distributorStore;
  @Mock private DistributorSupplyItemStore supplyItemStore;
  @Mock private PharmacyProductStore productStore;
  @Mock private ProductBatchStore batchStore;
  @Mock private RateLimiter rateLimiter;
  @Mock private ObjectMapper objectMapper;

  private PurchaseGrnService service;
  private MedmatePrincipal owner;

  @BeforeEach
  void setUp() throws Exception {
    when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(true);
    when(objectMapper.writeValueAsString(any())).thenReturn("[]");
    service =
        new PurchaseGrnService(
            grnStore,
            distributorStore,
            supplyItemStore,
            productStore,
            batchStore,
            rateLimiter,
            Clock.fixed(NOW, ZoneOffset.UTC),
            objectMapper);
    owner =
        new MedmatePrincipal(
            UUID.randomUUID(), AuthRole.PHARMACY_OWNER, PHARMACY, TokenScope.FULL, "j");
    when(distributorStore.findByIdIncludingDeleted(PHARMACY, DIST))
        .thenReturn(Optional.of(Distributor.minimal(DIST, PHARMACY, "D", true, NOW)));
    when(grnStore.list(any())).thenReturn(new ListResult(List.of(), 0));
    when(grnStore.kpi(any(), any(), any())).thenReturn(new KpiRow(0, 0, 0));
  }

  @Test
  void remainingBranches() throws Exception {
    service.list(owner, null, null, null, null, null, 2, 10);

    UUID grnId = UUID.randomUUID();
    when(grnStore.findById(PHARMACY, grnId)).thenReturn(Optional.of(draft(grnId, null)));
    when(productStore.insert(any())).thenAnswer(inv -> inv.getArgument(0));
    when(grnStore.insertItem(any())).thenAnswer(inv -> inv.getArgument(0));

    // DROPS pack unit + blank manufacturer + long name reject + null name + pack<=0 + blank form
    service.addItem(
        owner,
        grnId,
        null,
        null,
        true,
        "DropsMed",
        "  ",
        1,
        "DROPS",
        "BN",
        LocalDate.of(2027, 1, 1),
        null,
        1,
        0,
        new BigDecimal("1"),
        new BigDecimal("2"),
        new BigDecimal("12"));
    assertThatThrownBy(
            () ->
                service.addItem(
                    owner,
                    grnId,
                    null,
                    null,
                    true,
                    "x".repeat(201),
                    null,
                    1,
                    "TABLET",
                    "BN",
                    LocalDate.of(2027, 1, 1),
                    null,
                    1,
                    0,
                    new BigDecimal("1"),
                    new BigDecimal("2"),
                    new BigDecimal("12")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.addItem(
                    owner,
                    grnId,
                    null,
                    null,
                    true,
                    null,
                    null,
                    1,
                    "TABLET",
                    "BN",
                    LocalDate.of(2027, 1, 1),
                    null,
                    1,
                    0,
                    new BigDecimal("1"),
                    new BigDecimal("2"),
                    new BigDecimal("12")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.addItem(
                    owner,
                    grnId,
                    null,
                    null,
                    true,
                    "N",
                    null,
                    0,
                    "TABLET",
                    "BN",
                    LocalDate.of(2027, 1, 1),
                    null,
                    1,
                    0,
                    new BigDecimal("1"),
                    new BigDecimal("2"),
                    new BigDecimal("12")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.addItem(
                    owner,
                    grnId,
                    null,
                    null,
                    true,
                    "N",
                    null,
                    1,
                    "  ",
                    "BN",
                    LocalDate.of(2027, 1, 1),
                    null,
                    1,
                    0,
                    new BigDecimal("1"),
                    new BigDecimal("2"),
                    new BigDecimal("12")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.addItem(
                    owner,
                    grnId,
                    null,
                    null,
                    true,
                    "N",
                    null,
                    1,
                    "TABLET",
                    "B".repeat(51),
                    LocalDate.of(2027, 1, 1),
                    null,
                    1,
                    0,
                    new BigDecimal("1"),
                    new BigDecimal("2"),
                    new BigDecimal("12")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.addItem(
                    owner,
                    grnId,
                    null,
                    null,
                    true,
                    "N",
                    null,
                    1,
                    "TABLET",
                    "BN",
                    null,
                    null,
                    1,
                    0,
                    new BigDecimal("1"),
                    new BigDecimal("2"),
                    new BigDecimal("12")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("EXPIRY_DATE_IN_PAST");
    assertThatThrownBy(
            () ->
                service.addItem(
                    owner,
                    grnId,
                    null,
                    null,
                    true,
                    "N",
                    null,
                    1,
                    "TABLET",
                    "BN",
                    LocalDate.of(2027, 1, 1),
                    null,
                    null,
                    0,
                    new BigDecimal("1"),
                    new BigDecimal("2"),
                    new BigDecimal("12")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.addItem(
                    owner,
                    grnId,
                    null,
                    null,
                    true,
                    "N",
                    null,
                    1,
                    "TABLET",
                    "BN",
                    LocalDate.of(2027, 1, 1),
                    null,
                    1,
                    0,
                    new BigDecimal("1"),
                    new BigDecimal("2"),
                    null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_GST_PCT");

    // save-and-stock product vanished
    UUID pid = UUID.randomUUID();
    PurchaseGrnItem item =
        new PurchaseGrnItem(
            UUID.randomUUID(),
            grnId,
            PHARMACY,
            pid,
            "BN",
            LocalDate.of(2027, 1, 1),
            null,
            1,
            0,
            100,
            200,
            12,
            100,
            12,
            112,
            false,
            NOW,
            NOW);
    when(grnStore.listItems(PHARMACY, grnId)).thenReturn(List.of(new ItemWithProduct(item, "P")));
    when(batchStore.findByBatchNumber(any(), any(), any())).thenReturn(Optional.empty());
    when(batchStore.insert(any())).thenAnswer(inv -> inv.getArgument(0));
    when(productStore.findById(PHARMACY, pid)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.saveAndStock(owner, grnId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("PRODUCT_NOT_FOUND");

    // confirm blank unmatched + null free qty value + blank free
    when(grnStore.findById(PHARMACY, grnId)).thenReturn(Optional.of(draft(grnId, "   ")));
    assertThat(service.confirmImport(owner, grnId).get("items_created")).isEqualTo(0);

    when(grnStore.findById(PHARMACY, grnId)).thenReturn(Optional.of(draft(grnId, "pending")));
    Map<String, Object> row = new HashMap<>();
    Map<String, Object> csv = new HashMap<>();
    csv.put("product_name", "U");
    csv.put("manufacturer", "M");
    csv.put("batch_number", "B");
    csv.put("expiry_date", "2027-06-30");
    csv.put("quantity", "2");
    csv.put("free_quantity", null);
    csv.put("purchase_price", "10.00");
    csv.put("mrp", "15.00");
    csv.put("gst_pct", "12");
    row.put("csv_row", csv);
    when(objectMapper.readValue(
            anyString(), any(com.fasterxml.jackson.core.type.TypeReference.class)))
        .thenReturn(List.of(row));
    when(grnStore.updateImportUnmatched(any(), any(), any())).thenReturn(draft(grnId, null));
    assertThat(service.confirmImport(owner, grnId).get("items_created")).isEqualTo(1);

    csv.put("free_quantity", " ");
    assertThat(service.confirmImport(owner, grnId).get("items_created")).isEqualTo(1);
    csv.put("free_quantity", "2");
    assertThat(service.confirmImport(owner, grnId).get("items_created")).isEqualTo(1);

    // GRN not found on editable
    when(grnStore.findById(PHARMACY, grnId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.confirmImport(owner, grnId))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("GRN_NOT_FOUND");

    // CSV IO + writeValue failure + empty header + blank line skip
    MultipartFile boom = mock(MultipartFile.class);
    when(boom.isEmpty()).thenReturn(false);
    when(boom.getSize()).thenReturn(10L);
    when(boom.getInputStream()).thenThrow(new IOException("x"));
    when(grnStore.invoiceExists(any(), any(), anyString())).thenReturn(false);
    assertThatThrownBy(() -> service.importCsv(owner, boom, DIST, "IO", LocalDate.of(2026, 7, 1)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_CSV_FORMAT");

    when(objectMapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("x") {});
    when(productStore.findByNameAndManufacturer(any(), anyString(), anyString()))
        .thenReturn(Optional.empty());
    when(grnStore.insert(any())).thenAnswer(inv -> inv.getArgument(0));
    String csvBody =
        """
        product_name,manufacturer,batch_number,expiry_date,quantity,free_quantity,purchase_price,mrp,gst_pct

        Unknown,M,BN,2027-06-30,1,0,10.00,20.00,12
        """;
    assertThatThrownBy(
            () ->
                service.importCsv(
                    owner,
                    new MockMultipartFile(
                        "csv_file", "a.csv", "text/csv", csvBody.getBytes(StandardCharsets.UTF_8)),
                    DIST,
                    "W",
                    LocalDate.of(2026, 7, 1)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_CSV_FORMAT");

    MultipartFile emptyHeader =
        new MockMultipartFile("csv_file", "e.csv", "text/csv", new byte[0]) {
          @Override
          public java.io.InputStream getInputStream() {
            return new java.io.ByteArrayInputStream(new byte[0]);
          }

          @Override
          public boolean isEmpty() {
            return false;
          }

          @Override
          public long getSize() {
            return 1;
          }
        };
    // empty stream → headerLine null
    assertThatThrownBy(
            () -> service.importCsv(owner, emptyHeader, DIST, "EH", LocalDate.of(2026, 7, 1)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("INVALID_CSV_FORMAT");
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
