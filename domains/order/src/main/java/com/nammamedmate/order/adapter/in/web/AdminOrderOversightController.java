package com.nammamedmate.order.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.order.application.AdminOrderService;
import com.nammamedmate.order.application.AdminOrderService.ListResult;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
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
@RequestMapping("/api/v1/admin/orders")
@Tag(name = "Admin order oversight")
public class AdminOrderOversightController {

  private final AdminOrderService service;

  public AdminOrderOversightController(AdminOrderService service) {
    this.service = service;
  }

  @GetMapping
  @Operation(
      summary = "List admin orders with segments, filters, summary chips, optional CSV export")
  public ApiResponse<?> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false, defaultValue = "ALL") String segment,
      @RequestParam(required = false) String search,
      @RequestParam(name = "pharmacy_id", required = false) UUID pharmacyId,
      @RequestParam(name = "rider_id", required = false) UUID riderId,
      @RequestParam(name = "zone_id", required = false) UUID zoneId,
      @RequestParam(name = "payment_method", required = false) String paymentMethod,
      @RequestParam(name = "is_rx_only", required = false) Boolean isRxOnly,
      @RequestParam(name = "from_date", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate fromDate,
      @RequestParam(name = "to_date", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate toDate,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) Boolean export) {
    ListResult result =
        service.list(
            principal,
            segment,
            search,
            pharmacyId,
            riderId,
            zoneId,
            paymentMethod,
            isRxOnly,
            fromDate,
            toDate,
            page,
            limit,
            export);
    if (result.meta() == null) {
      return ApiResponse.ok(result.data());
    }
    return ApiResponse.ok(result.data(), result.meta());
  }

  @GetMapping("/live-feed")
  @Operation(summary = "Live order feed for command dashboard (Redis 10s cache)")
  public ApiResponse<Map<String, Object>> liveFeed(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(service.liveFeed(principal));
  }

  @GetMapping("/{orderId}")
  @Operation(summary = "Admin order detail with role-based Rx redaction")
  public ApiResponse<Map<String, Object>> detail(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("orderId") UUID orderId) {
    return ApiResponse.ok(service.detail(principal, orderId));
  }

  @PatchMapping("/{orderId}/rider")
  @Operation(summary = "Reassign rider before DELIVERED")
  public ApiResponse<Map<String, Object>> reassignRider(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("orderId") UUID orderId,
      @RequestBody(required = false) ReassignRiderRequest body) {
    ReassignRiderRequest req = body == null ? new ReassignRiderRequest(null, null) : body;
    return ApiResponse.ok(service.reassignRider(principal, orderId, req.riderId(), req.reason()));
  }

  @PostMapping("/{orderId}/dispute")
  @Operation(summary = "Flag order as disputed")
  public ApiResponse<Map<String, Object>> flagDispute(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("orderId") UUID orderId,
      @RequestBody(required = false) DisputeRequest body) {
    DisputeRequest req = body == null ? new DisputeRequest(null, null) : body;
    return ApiResponse.ok(service.flagDispute(principal, orderId, req.reason(), req.liableParty()));
  }

  @PostMapping("/{orderId}/note")
  @Operation(summary = "Add append-only internal admin note")
  public ApiResponse<Map<String, Object>> addNote(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("orderId") UUID orderId,
      @RequestBody(required = false) NoteRequest body) {
    NoteRequest req = body == null ? new NoteRequest(null, null) : body;
    return ApiResponse.ok(service.addNote(principal, orderId, req.note(), req.isPinned()));
  }

  @DeleteMapping("/{orderId}/notes/{noteId}")
  @Operation(summary = "Notes are append-only — always 405")
  public org.springframework.http.ResponseEntity<ApiResponse<?>> deleteNote(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("orderId") UUID orderId,
      @PathVariable("noteId") UUID noteId) {
    service.requireNoteDeleteDenied(principal);
    return org.springframework.http.ResponseEntity.status(405)
        .body(ApiResponse.fail("METHOD_NOT_ALLOWED", "Notes are append-only"));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ReassignRiderRequest(UUID riderId, String reason) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record DisputeRequest(String reason, String liableParty) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record NoteRequest(String note, Boolean isPinned) {}
}
