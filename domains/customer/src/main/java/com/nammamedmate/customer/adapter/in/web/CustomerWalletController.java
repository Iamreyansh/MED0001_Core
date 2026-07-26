package com.nammamedmate.customer.adapter.in.web;

import com.nammamedmate.customer.application.WalletService;
import com.nammamedmate.customer.application.WalletService.TxPage;
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
@RequestMapping("/api/v1/customers/me/wallet")
@Tag(name = "Customer wallet")
public class CustomerWalletController {

  private final WalletService service;

  public CustomerWalletController(WalletService service) {
    this.service = service;
  }

  @GetMapping
  @Operation(summary = "Get Namma Money wallet balance")
  public ApiResponse<Map<String, Object>> get(@AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(service.getMyWallet(principal));
  }

  @GetMapping("/transactions")
  @Operation(summary = "List wallet transactions")
  public ApiResponse<List<Map<String, Object>>> transactions(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) String order,
      @RequestParam(required = false) String type) {
    TxPage result = service.listMyTransactions(principal, page, limit, sort, order, type);
    return ApiResponse.ok(result.data(), result.meta());
  }
}
