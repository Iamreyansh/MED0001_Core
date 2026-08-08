package com.nammamedmate.auth.adapter.in.web;

import com.nammamedmate.auth.application.AdminRolesService;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin RBAC")
public class AdminRolesController {

  private final AdminRolesService adminRolesService;

  public AdminRolesController(AdminRolesService adminRolesService) {
    this.adminRolesService = adminRolesService;
  }

  @GetMapping("/roles")
  @Operation(summary = "List fixed admin roles and permission matrices")
  public ApiResponse<List<Map<String, Object>>> listRoles(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(adminRolesService.listRoles(principal));
  }

  @GetMapping("/roles/{role}/permissions")
  @Operation(summary = "Get expanded permissions for a fixed admin role")
  public ApiResponse<Map<String, Object>> getRolePermissions(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("role") String role) {
    return ApiResponse.ok(adminRolesService.getRolePermissions(principal, role));
  }

  @GetMapping("/permissions")
  @Operation(summary = "List admin permission catalog")
  public ApiResponse<List<Map<String, Object>>> listPermissions(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(value = "resource", required = false) String resource) {
    return ApiResponse.ok(adminRolesService.listPermissions(principal, resource));
  }
}
