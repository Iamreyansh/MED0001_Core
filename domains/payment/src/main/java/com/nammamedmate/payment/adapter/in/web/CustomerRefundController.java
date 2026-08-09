package com.nammamedmate.payment.adapter.in.web;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.payment.application.RefundFacadeService;
import com.nammamedmate.payment.application.RefundFacadeService.PagedResult;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customers/me/refunds")
@Tag(name = "Customer refunds")
public class CustomerRefundController {

  private final RefundFacadeService refunds;

  public CustomerRefundController(RefundFacadeService refunds) {
    this.refunds = refunds;
  }

  @GetMapping
  @Operation(summary = "Customer: own refund history")
  public ApiResponse<Map<String, Object>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit) {
    PagedResult result = refunds.listCustomer(principal, page, limit);
    return ApiResponse.ok(result.data(), result.meta());
  }
}
