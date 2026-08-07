package com.nammamedmate.pharmacy.adapter.in.web;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.pharmacy.application.AdminZoneService;
import com.nammamedmate.pharmacy.application.AdminZoneService.ZoneListResult;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/zones")
@Tag(name = "Admin zones")
public class AdminZoneController {

  private final AdminZoneService service;

  public AdminZoneController(AdminZoneService service) {
    this.service = service;
  }

  @GetMapping
  @RequiresPermission("pharmacies:read")
  @Operation(summary = "Admin: list delivery zones with pharmacy coverage stats")
  public ApiResponse<Map<String, Object>> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) String city,
      @RequestParam(name = "is_active", required = false) Boolean isActive) {
    ZoneListResult result = service.list(principal, city, isActive);
    return ApiResponse.ok(result.data(), result.meta());
  }
}
