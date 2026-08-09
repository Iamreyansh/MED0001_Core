package com.nammamedmate.inventory.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.inventory.application.InventoryBatchService;
import com.nammamedmate.inventory.application.InventoryBatchService.FileExport;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pharmacy/inventory")
@Tag(name = "Pharmacy inventory batches")
public class PharmacyInventoryBatchController {

  private final InventoryBatchService service;

  public PharmacyInventoryBatchController(InventoryBatchService service) {
    this.service = service;
  }

  @GetMapping("/expiry-alerts")
  @Operation(summary = "Expiry alert dashboard buckets")
  public ApiResponse<Map<String, Object>> expiryAlerts(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(service.expiryAlerts(principal));
  }

  @GetMapping("/expiry-report")
  @Operation(summary = "Full expiry report (JSON / EXCEL / PDF)")
  public Object expiryReport(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(value = "within_months", required = false) Integer withinMonths,
      @RequestParam(value = "export", required = false) String export) {
    Object result = service.expiryReport(principal, withinMonths, export);
    if (result instanceof FileExport file) {
      return ResponseEntity.ok()
          .header(
              HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename() + "\"")
          .contentType(MediaType.parseMediaType(file.contentType()))
          .body(file.bytes());
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) result;
    return ApiResponse.ok(data);
  }

  @GetMapping("/{productId}/batches")
  @Operation(summary = "List batches for a product (FEFO order)")
  public ApiResponse<Map<String, Object>> listBatches(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("productId") UUID productId,
      @RequestParam(value = "include_inactive", required = false, defaultValue = "false")
          boolean includeInactive) {
    return ApiResponse.ok(service.listBatches(principal, productId, includeInactive));
  }

  @PostMapping("/{productId}/batches")
  @Operation(summary = "Manually add a batch (top-up on duplicate)")
  public ResponseEntity<ApiResponse<Map<String, Object>>> addBatch(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("productId") UUID productId,
      @RequestBody(required = false) AddBatchRequest body) {
    AddBatchRequest req =
        body == null ? new AddBatchRequest(null, null, null, null, null, null, null) : body;
    Map<String, Object> data =
        service.addBatch(
            principal,
            productId,
            req.batchNumber(),
            req.expiryDate(),
            req.manufacturedDate(),
            req.quantity(),
            req.freeQuantity(),
            req.purchasePricePerUnit(),
            req.mrpPerUnit());
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data));
  }

  @PatchMapping("/{productId}/batches/{batchId}")
  @Operation(summary = "Adjust batch quantity")
  public ApiResponse<Map<String, Object>> adjustBatch(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("productId") UUID productId,
      @PathVariable("batchId") UUID batchId,
      @RequestBody(required = false) AdjustBatchRequest body) {
    AdjustBatchRequest req = body == null ? new AdjustBatchRequest(null, null) : body;
    return ApiResponse.ok(
        service.adjustBatch(principal, productId, batchId, req.adjustment(), req.reason()));
  }

  @DeleteMapping("/{productId}/batches/{batchId}")
  @Operation(summary = "Write off a batch (owner only)")
  public ApiResponse<Map<String, Object>> writeOffBatch(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("productId") UUID productId,
      @PathVariable("batchId") UUID batchId,
      @RequestBody(required = false) WriteOffRequest body) {
    WriteOffRequest req = body == null ? new WriteOffRequest(null, null) : body;
    return ApiResponse.ok(
        service.writeOffBatch(principal, productId, batchId, req.writeOffReason(), req.notes()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record AddBatchRequest(
      String batchNumber,
      LocalDate expiryDate,
      LocalDate manufacturedDate,
      Integer quantity,
      Integer freeQuantity,
      BigDecimal purchasePricePerUnit,
      BigDecimal mrpPerUnit) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record AdjustBatchRequest(Integer adjustment, String reason) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record WriteOffRequest(String writeOffReason, String notes) {}
}
