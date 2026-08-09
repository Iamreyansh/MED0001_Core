package com.nammamedmate.inventory.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.inventory.application.PharmacyReorderService;
import com.nammamedmate.inventory.application.PharmacyReorderService.ListPage;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pharmacy/reorder")
@Tag(name = "Pharmacy reorder suggestions")
public class PharmacyReorderController {

  private final PharmacyReorderService service;

  public PharmacyReorderController(PharmacyReorderService service) {
    this.service = service;
  }

  @GetMapping
  @Operation(summary = "List reorder suggestions")
  public Map<String, Object> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(value = "group_by", required = false) String groupBy,
      @RequestParam(value = "page", required = false) Integer page,
      @RequestParam(value = "limit", required = false) Integer limit) {
    ListPage result = service.listSuggestions(principal, groupBy, page, limit);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("success", true);
    body.put("data", result.data());
    body.put("meta", result.meta());
    return body;
  }

  @PostMapping("/create-po")
  @Operation(summary = "Create draft purchase order from suggestions")
  public ResponseEntity<ApiResponse<Map<String, Object>>> createPo(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) CreatePoRequest body) {
    CreatePoRequest req = body == null ? new CreatePoRequest(null, null) : body;
    Map<String, Object> data = service.createPo(principal, req.distributorId(), req.items());
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data));
  }

  @GetMapping("/purchase-orders")
  @Operation(summary = "List purchase orders")
  public Map<String, Object> listPurchaseOrders(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(value = "status", required = false) String status,
      @RequestParam(value = "distributor_id", required = false) UUID distributorId,
      @RequestParam(value = "page", required = false) Integer page,
      @RequestParam(value = "limit", required = false) Integer limit) {
    ListPage result = service.listPurchaseOrders(principal, status, distributorId, page, limit);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("success", true);
    body.put("data", result.data());
    body.put("meta", result.meta());
    return body;
  }

  @PatchMapping("/purchase-orders/{poId}")
  @Operation(summary = "Update DRAFT purchase order")
  public ApiResponse<Map<String, Object>> patchPo(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("poId") UUID poId,
      @RequestBody(required = false) PatchPoRequest body) {
    PatchPoRequest req = body == null ? new PatchPoRequest(null, null, null) : body;
    return ApiResponse.ok(
        service.patchPo(principal, poId, req.addItems(), req.removeItemIds(), req.updateItems()));
  }

  @PostMapping("/purchase-orders/{poId}/send")
  @Operation(summary = "Send purchase order to distributor")
  public ApiResponse<Map<String, Object>> sendPo(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("poId") UUID poId,
      @RequestBody(required = false) SendPoRequest body) {
    SendPoRequest req = body == null ? new SendPoRequest(null, null) : body;
    return ApiResponse.ok(service.sendPo(principal, poId, req.channel(), req.recipientOverride()));
  }

  @PostMapping("/purchase-orders/{poId}/record-grn")
  @Operation(summary = "Create DRAFT GRN from SENT purchase order")
  public ResponseEntity<ApiResponse<Map<String, Object>>> recordGrn(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("poId") UUID poId,
      @RequestBody(required = false) RecordGrnRequest body) {
    RecordGrnRequest req = body == null ? new RecordGrnRequest(null, null) : body;
    Map<String, Object> data =
        service.recordGrn(principal, poId, req.invoiceNumber(), req.invoiceDate());
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data));
  }

  @PostMapping("/refresh")
  @Operation(summary = "Manually refresh reorder suggestion snapshots")
  public ApiResponse<Map<String, Object>> refresh(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(service.refresh(principal));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CreatePoRequest(UUID distributorId, List<Map<String, Object>> items) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record PatchPoRequest(
      List<Map<String, Object>> addItems,
      List<UUID> removeItemIds,
      List<Map<String, Object>> updateItems) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record SendPoRequest(String channel, String recipientOverride) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record RecordGrnRequest(String invoiceNumber, LocalDate invoiceDate) {}
}
