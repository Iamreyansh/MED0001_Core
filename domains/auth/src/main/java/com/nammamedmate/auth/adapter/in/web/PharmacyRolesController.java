package com.nammamedmate.auth.adapter.in.web;

import com.nammamedmate.auth.adapter.in.web.dto.CreatePharmacyRoleRequest;
import com.nammamedmate.auth.adapter.in.web.dto.UpdateRolePermissionsRequest;
import com.nammamedmate.auth.application.PharmacyRolesService;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pharmacy/roles")
@Tag(name = "Pharmacy RBAC")
public class PharmacyRolesController {

  private final PharmacyRolesService pharmacyRolesService;

  public PharmacyRolesController(PharmacyRolesService pharmacyRolesService) {
    this.pharmacyRolesService = pharmacyRolesService;
  }

  @GetMapping
  @Operation(summary = "List system and custom pharmacy roles for the active pharmacy")
  public ApiResponse<List<Map<String, Object>>> list(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(pharmacyRolesService.listRoles(principal));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Create a custom pharmacy role")
  public ApiResponse<Map<String, Object>> create(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody CreatePharmacyRoleRequest request) {
    return ApiResponse.ok(
        pharmacyRolesService.createRole(
            principal, request.name(), request.displayName(), request.permissions()));
  }

  @GetMapping("/{id}/permissions")
  @Operation(summary = "Get permissions for a pharmacy role")
  public ApiResponse<Map<String, Object>> getPermissions(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") String id) {
    return ApiResponse.ok(pharmacyRolesService.getPermissions(principal, id));
  }

  @PutMapping("/{id}/permissions")
  @Operation(summary = "Replace permissions on a custom pharmacy role")
  public ApiResponse<Map<String, Object>> updatePermissions(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") String id,
      @RequestBody UpdateRolePermissionsRequest request) {
    return ApiResponse.ok(
        pharmacyRolesService.updatePermissions(principal, id, request.permissions()));
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Soft-delete a custom pharmacy role")
  public void delete(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") String id) {
    pharmacyRolesService.deleteRole(principal, id);
  }
}
