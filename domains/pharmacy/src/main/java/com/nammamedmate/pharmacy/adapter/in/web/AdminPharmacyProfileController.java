package com.nammamedmate.pharmacy.adapter.in.web;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.pharmacy.application.AdminPharmacyProfileService;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/pharmacies")
@Tag(name = "Admin pharmacy profile")
public class AdminPharmacyProfileController {

  private final AdminPharmacyProfileService service;

  public AdminPharmacyProfileController(AdminPharmacyProfileService service) {
    this.service = service;
  }

  @PatchMapping("/{id}/profile")
  @RequiresPermission("pharmacies:update")
  @Operation(summary = "Admin: update pharmacy profile with audit trail")
  public ApiResponse<Map<String, Object>> patchProfile(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody(required = false) Map<String, Object> body,
      HttpServletRequest request) {
    return ApiResponse.ok(
        service.patchProfile(principal, id, body == null ? Map.of() : body, clientIp(request)));
  }

  @GetMapping("/{id}/bank-account")
  @RequiresPermission("pharmacies:read")
  @Operation(summary = "Admin: view pharmacy bank account")
  public ApiResponse<Map<String, Object>> getBankAccount(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(service.getBankAccount(principal, id));
  }

  private static String clientIp(HttpServletRequest request) {
    String remote = request.getRemoteAddr();
    return remote == null || remote.isBlank() ? null : remote.trim();
  }
}
