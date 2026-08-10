package com.nammamedmate.prescription.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.prescription.application.PharmacyRxQueueService;
import com.nammamedmate.prescription.application.PharmacyRxQueueService.ListResult;
import com.nammamedmate.prescription.domain.PharmacyRxQueueEntry.ApprovedMedicine;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pharmacy/prescriptions")
@Tag(name = "Pharmacy prescription queue")
public class PharmacyPrescriptionController {

  private final PharmacyRxQueueService service;

  public PharmacyPrescriptionController(PharmacyRxQueueService service) {
    this.service = service;
  }

  @GetMapping
  @Operation(summary = "List pharmacy Rx queue with KPIs")
  public ApiResponse<Map<String, Object>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "source", required = false) String source,
      @RequestParam(name = "search", required = false) String search,
      @RequestParam(name = "page", required = false) Integer page,
      @RequestParam(name = "limit", required = false) Integer limit,
      @RequestParam(name = "sort", required = false) String sort) {
    ListResult result = service.list(principal, status, source, search, page, limit, sort);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @GetMapping("/{rxId}")
  @Operation(summary = "Get pharmacy Rx queue detail")
  public ApiResponse<Map<String, Object>> get(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable UUID rxId) {
    return ApiResponse.ok(service.get(principal, rxId));
  }

  @PostMapping("/{rxId}/approve")
  @Operation(summary = "Approve prescription and set order line items")
  public ApiResponse<Map<String, Object>> approve(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID rxId,
      @RequestBody(required = false) ApproveRequest body) {
    List<ApprovedMedicine> meds = body == null ? List.of() : body.toMedicines();
    String notes = body == null ? null : body.notes();
    return ApiResponse.ok(service.approve(principal, rxId, meds, notes));
  }

  @PostMapping("/{rxId}/reject")
  @Operation(summary = "Reject prescription and notify customer")
  public ApiResponse<Map<String, Object>> reject(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID rxId,
      @RequestBody(required = false) RejectRequest body) {
    String reason = body == null ? null : body.reason();
    String custom = body == null ? null : body.customMessage();
    return ApiResponse.ok(service.reject(principal, rxId, reason, custom));
  }

  @PostMapping("/{rxId}/dispense")
  @Operation(summary = "Mark prescription dispensed and ready for pickup")
  public ApiResponse<Map<String, Object>> dispense(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable UUID rxId) {
    return ApiResponse.ok(service.dispense(principal, rxId));
  }

  @PostMapping("/{rxId}/dispense-to-billing")
  @Operation(summary = "Push approved medicines to POS billing cart")
  public ApiResponse<Map<String, Object>> dispenseToBilling(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable UUID rxId) {
    return ApiResponse.ok(service.dispenseToBilling(principal, rxId));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ApproveRequest(List<ApprovedMedicineBody> approvedMedicines, String notes) {
    public ApproveRequest {
      approvedMedicines =
          approvedMedicines == null
              ? null
              : java.util.Collections.unmodifiableList(new ArrayList<>(approvedMedicines));
    }

    List<ApprovedMedicine> toMedicines() {
      if (approvedMedicines == null) {
        return List.of();
      }
      List<ApprovedMedicine> out = new ArrayList<>();
      for (ApprovedMedicineBody b : approvedMedicines) {
        if (b == null) {
          continue;
        }
        int qty = b.quantity() == null ? 0 : b.quantity();
        BigDecimal price = b.price() == null ? BigDecimal.ZERO : b.price();
        out.add(new ApprovedMedicine(b.name(), qty, price, b.schedule()));
      }
      return out;
    }
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ApprovedMedicineBody(
      String name, Integer quantity, BigDecimal price, String schedule) {
    public ApprovedMedicineBody(String name, Integer quantity, BigDecimal price) {
      this(name, quantity, price, null);
    }
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record RejectRequest(String reason, String customMessage) {}
}
