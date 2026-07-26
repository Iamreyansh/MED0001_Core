package com.nammamedmate.customer.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.customer.application.PaymentMethodService;
import com.nammamedmate.customer.application.PaymentMethodService.CardCommand;
import com.nammamedmate.customer.application.PaymentMethodService.UpiCommand;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customers/me/payment-methods")
@Tag(name = "Customer payment methods")
public class CustomerPaymentMethodController {

  private final PaymentMethodService service;

  public CustomerPaymentMethodController(PaymentMethodService service) {
    this.service = service;
  }

  @GetMapping
  @Operation(summary = "List saved payment methods (masked)")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "Masked UPI + card list"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "401",
        description = "UNAUTHORIZED")
  })
  public ApiResponse<Map<String, Object>> list(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(service.list(principal));
  }

  @PostMapping("/upi")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Save a UPI VPA after Razorpay validation")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "201",
        description = "UPI saved"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "VALIDATION_ERROR"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "409",
        description = "UPI_ALREADY_SAVED"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "422",
        description = "INVALID_UPI_VPA | PAYMENT_METHOD_LIMIT_REACHED"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "503",
        description = "VPA_VALIDATION_TIMEOUT | VPA_VALIDATION_FAILED")
  })
  public ApiResponse<Map<String, Object>> saveUpi(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody(required = false) UpiRequest body) {
    return ApiResponse.ok(
        service.saveUpi(
            principal,
            body == null ? null : new UpiCommand(body.upiId(), body.nickname()),
            idempotencyKey));
  }

  @PostMapping("/card")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Save a Razorpay-tokenised card")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "201",
        description = "Card saved"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "400",
        description = "VALIDATION_ERROR"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "409",
        description = "CARD_ALREADY_SAVED"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "422",
        description = "INVALID_RAZORPAY_TOKEN | PAYMENT_METHOD_LIMIT_REACHED")
  })
  public ApiResponse<Map<String, Object>> saveCard(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody(required = false) CardRequest body) {
    return ApiResponse.ok(
        service.saveCard(
            principal,
            body == null
                ? null
                : new CardCommand(
                    body.razorpayTokenId(),
                    body.cardLast4(),
                    body.cardNetwork(),
                    body.cardType(),
                    body.nickname()),
            idempotencyKey));
  }

  @DeleteMapping("/{id}")
  @Operation(summary = "Soft-delete a saved payment method")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "Removed"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "PAYMENT_METHOD_NOT_FOUND"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "409",
        description = "PAYMENT_METHOD_IN_ACTIVE_ORDER")
  })
  public ApiResponse<Map<String, Object>> delete(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(service.delete(principal, id));
  }

  @PatchMapping("/{id}/set-default")
  @Operation(summary = "Set default payment method")
  @ApiResponses({
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "200",
        description = "Default updated"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "404",
        description = "PAYMENT_METHOD_NOT_FOUND"),
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
        responseCode = "409",
        description = "ALREADY_DEFAULT")
  })
  public ApiResponse<Map<String, Object>> setDefault(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(service.setDefault(principal, id));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record UpiRequest(String upiId, String nickname) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CardRequest(
      String razorpayTokenId,
      String cardLast4,
      String cardNetwork,
      String cardType,
      String nickname) {}
}
