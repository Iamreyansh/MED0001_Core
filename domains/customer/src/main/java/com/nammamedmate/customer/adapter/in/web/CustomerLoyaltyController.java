package com.nammamedmate.customer.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.customer.application.LoyaltyService;
import com.nammamedmate.customer.application.LoyaltyService.TxPage;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customers/me/loyalty")
@Tag(name = "Customer loyalty")
public class CustomerLoyaltyController {

  private final LoyaltyService service;

  public CustomerLoyaltyController(LoyaltyService service) {
    this.service = service;
  }

  @GetMapping
  @Operation(summary = "Get loyalty status and tier progress")
  public ApiResponse<Map<String, Object>> get(@AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(service.getMyStatus(principal));
  }

  @GetMapping("/transactions")
  @Operation(summary = "List loyalty point transactions")
  public ApiResponse<List<Map<String, Object>>> transactions(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) String order,
      @RequestParam(required = false) String type) {
    TxPage result = service.listMyTransactions(principal, page, limit, order, type);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @PostMapping("/redeem")
  @Operation(summary = "Redeem loyalty points at checkout")
  public ApiResponse<Map<String, Object>> redeem(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) RedeemRequest body) {
    RedeemRequest req = body == null ? new RedeemRequest(null, null) : body;
    if (req.pointsToRedeem() == null || req.pointsToRedeem() <= 0) {
      throw new AppException("VALIDATION_ERROR", "points_to_redeem must be positive", 400);
    }
    UUID cartId = parseUuid(req.cartId(), "cart_id");
    return ApiResponse.ok(service.redeem(principal, req.pointsToRedeem(), cartId));
  }

  private static UUID parseUuid(String raw, String field) {
    if (raw == null || raw.isBlank()) {
      throw new AppException("VALIDATION_ERROR", field + " is required", 400);
    }
    try {
      return Ids.parse(raw.trim());
    } catch (RuntimeException ex) {
      throw new AppException("VALIDATION_ERROR", field + " must be a UUID", 400);
    }
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record RedeemRequest(Integer pointsToRedeem, String cartId) {}
}
