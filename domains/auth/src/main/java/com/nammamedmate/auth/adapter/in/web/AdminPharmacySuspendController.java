package com.nammamedmate.auth.adapter.in.web;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal admin pharmacy action gated by RBAC until EPIC-004 owns the full suspend workflow. Exists
 * so {@code pharmacies:suspend} is enforced server-side on a real route.
 */
@RestController
@RequestMapping("/api/v1/admin/pharmacies")
@Tag(name = "Admin pharmacies")
public class AdminPharmacySuspendController {

  @PostMapping("/{id}/suspend")
  @RequiresPermission("pharmacies:suspend")
  @Operation(summary = "Suspend a pharmacy (RBAC stub until EPIC-004)")
  public ApiResponse<Map<String, Object>> suspend(@PathVariable("id") UUID id) {
    // ponytail: no persistence/audit until EPIC-004; AuthZ wiring only
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", id);
    data.put("status", "suspended");
    return ApiResponse.ok(data);
  }
}
