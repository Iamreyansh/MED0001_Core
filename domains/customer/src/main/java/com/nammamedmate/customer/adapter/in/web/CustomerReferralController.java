package com.nammamedmate.customer.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.customer.application.ReferralService;
import com.nammamedmate.customer.application.ReferralService.ApplyCommand;
import com.nammamedmate.customer.application.ReferralService.InviteCommand;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customers/me/referral")
@Tag(name = "Customer referral")
public class CustomerReferralController {

  private final ReferralService service;

  public CustomerReferralController(ReferralService service) {
    this.service = service;
  }

  @GetMapping
  @Operation(summary = "Get referral code and stats")
  public ApiResponse<Map<String, Object>> get(@AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(service.getMyReferral(principal));
  }

  @PostMapping("/invite")
  @Operation(summary = "Log referral share and return share payload")
  public ApiResponse<Map<String, Object>> invite(
      @AuthenticationPrincipal MedmatePrincipal principal, @RequestBody InviteRequest body) {
    String channel = body == null ? null : body.channel();
    return ApiResponse.ok(service.invite(principal, new InviteCommand(channel)));
  }

  @PostMapping("/apply")
  @Operation(summary = "Apply a referrer's code before first order")
  public ApiResponse<Map<String, Object>> apply(
      @AuthenticationPrincipal MedmatePrincipal principal, @RequestBody ApplyRequest body) {
    String code = body == null ? null : body.referrerCode();
    return ApiResponse.ok(service.applyCode(principal, new ApplyCommand(code)));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ApplyRequest(String referrerCode) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record InviteRequest(String channel) {}
}
