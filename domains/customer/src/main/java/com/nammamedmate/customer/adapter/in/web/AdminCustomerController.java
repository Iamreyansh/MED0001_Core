package com.nammamedmate.customer.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.customer.application.AdminCustomerService;
import com.nammamedmate.customer.application.AdminCustomerService.AdminListResult;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/customers")
@Tag(name = "Admin customers")
public class AdminCustomerController {

  private final AdminCustomerService service;

  public AdminCustomerController(AdminCustomerService service) {
    this.service = service;
  }

  @GetMapping
  @RequiresPermission("customers:read")
  @Operation(summary = "List customers with filters")
  public ApiResponse<?> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) String order,
      @RequestParam(required = false) String search,
      @RequestParam(required = false) String segment,
      @RequestParam(name = "is_flagged", required = false) Boolean isFlagged,
      @RequestParam(required = false) String city,
      @RequestParam(required = false) Boolean export) {
    AdminListResult result =
        service.list(principal, page, limit, sort, order, search, segment, isFlagged, city, export);
    if (result.meta() == null) {
      return ApiResponse.ok(result.data());
    }
    return ApiResponse.ok(result.data(), result.meta());
  }

  @GetMapping("/{id}")
  @RequiresPermission("customers:read")
  @Operation(summary = "Get customer detail")
  public ApiResponse<Map<String, Object>> get(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(service.get(principal, id));
  }

  @PostMapping("/{id}/flag")
  @RequiresPermission("customers:notify")
  @Operation(summary = "Flag a customer for trust & safety")
  public ApiResponse<Map<String, Object>> flag(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody(required = false) FlagRequest body) {
    return ApiResponse.ok(
        service.flag(
            principal, id, body == null ? null : body.reason(), body == null ? null : body.note()));
  }

  @DeleteMapping("/{id}/flag")
  @RequiresPermission("customers:notify")
  @Operation(summary = "Remove customer flag")
  public ApiResponse<Map<String, Object>> unflag(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(service.unflag(principal, id));
  }

  @PostMapping("/{id}/notify")
  @RequiresPermission("customers:notify")
  @Operation(summary = "Send notification to a customer")
  public ApiResponse<Map<String, Object>> notify(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody(required = false) NotifyRequest body) {
    return ApiResponse.ok(
        service.notify(
            principal,
            id,
            body == null ? null : body.channel(),
            body == null ? null : body.title(),
            body == null ? null : body.body(),
            body == null ? null : body.deepLink()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record FlagRequest(String reason, String note) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record NotifyRequest(String channel, String title, String body, String deepLink) {}
}
