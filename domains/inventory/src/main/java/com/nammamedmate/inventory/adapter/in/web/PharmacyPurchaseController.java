package com.nammamedmate.inventory.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.inventory.application.PurchaseGrnService;
import com.nammamedmate.inventory.application.PurchaseGrnService.ListPage;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/pharmacy/purchases")
@Tag(name = "Pharmacy purchases / GRN")
public class PharmacyPurchaseController {

  private final PurchaseGrnService service;

  public PharmacyPurchaseController(PurchaseGrnService service) {
    this.service = service;
  }

  @GetMapping
  @Operation(summary = "List GRNs with KPI")
  public Map<String, Object> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(value = "status", required = false) String status,
      @RequestParam(value = "distributor_id", required = false) UUID distributorId,
      @RequestParam(value = "from_date", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate fromDate,
      @RequestParam(value = "to_date", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate toDate,
      @RequestParam(value = "q", required = false) String q,
      @RequestParam(value = "page", required = false) Integer page,
      @RequestParam(value = "limit", required = false) Integer limit) {
    ListPage result =
        service.list(principal, status, distributorId, fromDate, toDate, q, page, limit);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("success", true);
    body.put("data", result.data());
    body.put("meta", result.meta());
    return body;
  }

  @PostMapping
  @Operation(summary = "Create GRN header (DRAFT)")
  public ResponseEntity<ApiResponse<Map<String, Object>>> create(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) CreateGrnRequest body) {
    CreateGrnRequest req = body == null ? new CreateGrnRequest(null, null, null) : body;
    Map<String, Object> data =
        service.create(principal, req.distributorId(), req.invoiceNumber(), req.invoiceDate());
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data));
  }

  @PostMapping("/{grnId}/items")
  @Operation(summary = "Add GRN line item")
  public ResponseEntity<ApiResponse<Map<String, Object>>> addItem(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("grnId") UUID grnId,
      @RequestBody(required = false) AddItemRequest body) {
    AddItemRequest req =
        body == null
            ? new AddItemRequest(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null)
            : body;
    Map<String, Object> data =
        service.addItem(
            principal,
            grnId,
            req.productSearchQuery(),
            req.productId(),
            req.createNewProduct(),
            req.newProductName(),
            req.newProductManufacturer(),
            req.newProductPackSize(),
            req.newProductForm(),
            req.batchNumber(),
            req.expiryDate(),
            req.manufacturedDate(),
            req.quantity(),
            req.freeQuantity(),
            req.purchasePricePerUnit(),
            req.mrpPerUnit(),
            req.gstPct());
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data));
  }

  @PatchMapping("/{grnId}/items/{itemId}")
  @Operation(summary = "Edit GRN line item")
  public ApiResponse<Map<String, Object>> patchItem(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("grnId") UUID grnId,
      @PathVariable("itemId") UUID itemId,
      @RequestBody(required = false) PatchItemRequest body) {
    PatchItemRequest req =
        body == null ? new PatchItemRequest(null, null, null, null, null, null) : body;
    return ApiResponse.ok(
        service.patchItem(
            principal,
            grnId,
            itemId,
            req.quantity(),
            req.freeQuantity(),
            req.purchasePricePerUnit(),
            req.mrpPerUnit(),
            req.expiryDate(),
            req.gstPct()));
  }

  @DeleteMapping("/{grnId}/items/{itemId}")
  @Operation(summary = "Remove GRN line item")
  public ApiResponse<Map<String, Object>> deleteItem(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("grnId") UUID grnId,
      @PathVariable("itemId") UUID itemId) {
    return ApiResponse.ok(service.deleteItem(principal, grnId, itemId));
  }

  @PostMapping("/{grnId}/save-and-stock")
  @Operation(summary = "Finalize GRN and stock batches (owner only)")
  public ApiResponse<Map<String, Object>> saveAndStock(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("grnId") UUID grnId) {
    return ApiResponse.ok(service.saveAndStock(principal, grnId));
  }

  @PostMapping(value = "/import-csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @Operation(summary = "CSV import preview (step 1)")
  public ApiResponse<Map<String, Object>> importCsv(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestPart("csv_file") MultipartFile csvFile,
      @RequestParam(value = "distributor_id", required = false) UUID distributorId,
      @RequestParam("invoice_number") String invoiceNumber,
      @RequestParam("invoice_date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate invoiceDate) {
    return ApiResponse.ok(
        service.importCsv(principal, csvFile, distributorId, invoiceNumber, invoiceDate));
  }

  @PostMapping("/{grnId}/confirm-import")
  @Operation(summary = "Confirm CSV unmatched rows as new products (step 2)")
  public ApiResponse<Map<String, Object>> confirmImport(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("grnId") UUID grnId) {
    return ApiResponse.ok(service.confirmImport(principal, grnId));
  }

  @GetMapping("/{grnId}")
  @Operation(summary = "Get GRN detail")
  public ApiResponse<Map<String, Object>> get(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("grnId") UUID grnId) {
    return ApiResponse.ok(service.get(principal, grnId));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CreateGrnRequest(UUID distributorId, String invoiceNumber, LocalDate invoiceDate) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record AddItemRequest(
      String productSearchQuery,
      UUID productId,
      Boolean createNewProduct,
      String newProductName,
      String newProductManufacturer,
      Integer newProductPackSize,
      String newProductForm,
      String batchNumber,
      LocalDate expiryDate,
      LocalDate manufacturedDate,
      Integer quantity,
      Integer freeQuantity,
      BigDecimal purchasePricePerUnit,
      BigDecimal mrpPerUnit,
      BigDecimal gstPct) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record PatchItemRequest(
      Integer quantity,
      Integer freeQuantity,
      BigDecimal purchasePricePerUnit,
      BigDecimal mrpPerUnit,
      LocalDate expiryDate,
      BigDecimal gstPct) {}
}
