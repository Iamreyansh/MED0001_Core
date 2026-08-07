package com.nammamedmate.catalogue.adapter.in.web;

import com.nammamedmate.catalogue.application.ScheduleRulesService;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/catalogue/schedule-rules")
@Tag(name = "Admin catalogue schedule rules")
public class AdminScheduleRulesController {

  private final ScheduleRulesService service;

  public AdminScheduleRulesController(ScheduleRulesService service) {
    this.service = service;
  }

  @GetMapping
  @Operation(summary = "Get schedule classification rules (OTC/H/H1/X)")
  public ApiResponse<Map<String, Object>> get(@AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(service.get(principal));
  }
}
