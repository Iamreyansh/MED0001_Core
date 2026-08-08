package com.nammamedmate.settings.adapter.in.web;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.settings.application.AuditLogService;
import com.nammamedmate.settings.application.AuditLogService.ListResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/audit-log")
@Tag(name = "Admin audit log")
public class AdminAuditLogController {

  private final AuditLogService service;

  public AdminAuditLogController(AuditLogService service) {
    this.service = service;
  }

  @GetMapping
  @Operation(summary = "List platform audit log entries or queue CSV export")
  public ApiResponse<?> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) String order,
      @RequestParam(value = "actor_id", required = false) UUID actorId,
      @RequestParam(value = "actor_type", required = false) String actorType,
      @RequestParam(value = "resource_type", required = false) String resourceType,
      @RequestParam(value = "resource_id", required = false) UUID resourceId,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(required = false) Boolean export) {
    ListResult result =
        service.list(
            principal,
            page,
            limit,
            sort,
            order,
            actorId,
            actorType,
            resourceType,
            resourceId,
            action,
            from,
            to,
            export);
    if (result.meta() == null) {
      return ApiResponse.ok(result.data());
    }
    @SuppressWarnings("unchecked")
    var data = (java.util.List<Map<String, Object>>) result.data();
    return ApiResponse.ok(data, result.meta());
  }

  @GetMapping("/{id}")
  @Operation(summary = "Get audit log entry with computed JSON Patch diff")
  public ApiResponse<Map<String, Object>> get(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(service.get(principal, id));
  }
}
