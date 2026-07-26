package com.nammamedmate.customer.adapter.in.web;

import com.nammamedmate.customer.application.LoyaltyService;
import com.nammamedmate.customer.application.LoyaltyService.TxPage;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
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
}
