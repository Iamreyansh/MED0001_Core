package com.nammamedmate.payment.adapter.in.web;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.payment.application.SettlementFacadeService;
import com.nammamedmate.payment.application.SettlementFacadeService.PagedResult;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pharmacy/finance/settlements")
@Tag(name = "Pharmacy finance settlements")
public class PharmacyFinanceSettlementController {

  private final SettlementFacadeService settlements;

  public PharmacyFinanceSettlementController(SettlementFacadeService settlements) {
    this.settlements = settlements;
  }

  @GetMapping
  @Operation(summary = "Pharmacy: own settlement history")
  public ApiResponse<Map<String, Object>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit) {
    PagedResult result = settlements.listPharmacy(principal, status, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }

  @GetMapping("/{id}")
  @Operation(summary = "Pharmacy: own settlement detail")
  public ApiResponse<Map<String, Object>> detail(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(settlements.getPharmacyDetail(principal, id));
  }
}
