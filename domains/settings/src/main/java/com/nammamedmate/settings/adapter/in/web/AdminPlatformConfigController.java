package com.nammamedmate.settings.adapter.in.web;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.settings.application.PlatformConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/config")
@Tag(name = "Admin platform config")
public class AdminPlatformConfigController {

  private final PlatformConfigService service;

  public AdminPlatformConfigController(PlatformConfigService service) {
    this.service = service;
  }

  @GetMapping
  @Operation(summary = "List platform config (optional domain filter)")
  public ApiResponse<Map<String, Object>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) String domain) {
    return ApiResponse.ok(service.list(principal, domain));
  }

  @PatchMapping
  @Operation(summary = "Bulk update platform config (admin_super)")
  public ApiResponse<Map<String, Object>> bulkUpdate(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) Map<String, Object> body) {
    return ApiResponse.ok(service.bulkUpdate(principal, body));
  }

  /**
   * Dotted keys (e.g. orders.delivery_fee) via Spring 6 path pattern {@code /{*key}}. Leading slash
   * from the catch-all is stripped in the service.
   */
  @GetMapping("/{*key}")
  @Operation(summary = "Get single config key with history")
  public ApiResponse<Map<String, Object>> get(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("key") String key) {
    return ApiResponse.ok(service.get(principal, key));
  }
}
