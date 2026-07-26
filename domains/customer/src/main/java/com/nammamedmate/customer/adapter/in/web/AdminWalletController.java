package com.nammamedmate.customer.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.customer.application.WalletService;
import com.nammamedmate.customer.application.WalletService.AdminCreditCommand;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/customers")
@Tag(name = "Admin customers")
public class AdminWalletController {

  private final WalletService service;

  public AdminWalletController(WalletService service) {
    this.service = service;
  }

  @PostMapping("/{id}/wallet/credit")
  @ResponseStatus(HttpStatus.CREATED)
  @RequiresPermission("finance:*")
  @Operation(summary = "Manually credit a customer wallet")
  public ApiResponse<Map<String, Object>> credit(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody(required = false) CreditRequest body) {
    return ApiResponse.ok(
        service.adminCredit(
            principal,
            id,
            body == null
                ? null
                : new AdminCreditCommand(
                    body.amount(),
                    body.reason(),
                    body.note(),
                    body.referenceId(),
                    idempotencyKey)));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CreditRequest(Object amount, String reason, String note, String referenceId) {}
}
