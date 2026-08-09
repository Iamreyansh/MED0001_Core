package com.nammamedmate.payment.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.payment.application.InternalWalletTokenAuth;
import com.nammamedmate.payment.application.WalletFacadeService;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/wallet")
@Tag(name = "Wallet (internal)")
public class WalletController {

  private final WalletFacadeService wallets;
  private final InternalWalletTokenAuth internalAuth;

  public WalletController(WalletFacadeService wallets, InternalWalletTokenAuth internalAuth) {
    this.wallets = wallets;
    this.internalAuth = internalAuth;
  }

  @PostMapping("/debit")
  @Operation(summary = "Debit customer wallet at checkout (internal token required)")
  public ApiResponse<Map<String, Object>> debit(
      @RequestHeader(value = "X-Internal-Token", required = false) String internalToken,
      @RequestBody(required = false) DebitRequest body) {
    internalAuth.require(internalToken);
    DebitRequest req = body == null ? new DebitRequest(null, null, null, null) : body;
    return ApiResponse.ok(
        wallets.debit(req.customerId(), req.amount(), req.orderId(), req.idempotencyKey()));
  }

  @PostMapping("/credit")
  @Operation(summary = "Credit customer wallet (internal token OR finance/support/super JWT)")
  public ApiResponse<Map<String, Object>> credit(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestHeader(value = "X-Internal-Token", required = false) String internalToken,
      @RequestBody(required = false) CreditRequest body) {
    if (!WalletFacadeService.canAdminCredit(principal)) {
      internalAuth.require(internalToken);
    }
    CreditRequest req = body == null ? new CreditRequest(null, null, null, null, null) : body;
    return ApiResponse.ok(
        wallets.credit(
            principal,
            req.customerId(),
            req.amount(),
            req.reason(),
            req.referenceId(),
            req.note()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record DebitRequest(UUID customerId, Object amount, UUID orderId, String idempotencyKey) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CreditRequest(
      UUID customerId, Object amount, String reason, String referenceId, String note) {}
}
