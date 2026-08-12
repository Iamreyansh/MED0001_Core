package com.nammamedmate.automation.adapter.in.web;

import com.nammamedmate.automation.application.SeedAutomationsService;
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
@RequestMapping("/api/v1/admin/automation/seed-rules")
@Tag(name = "Admin automation seed rules")
public class AdminAutomationSeedRulesController {

  private final SeedAutomationsService seeds;

  public AdminAutomationSeedRulesController(SeedAutomationsService seeds) {
    this.seeds = seeds;
  }

  @GetMapping
  @Operation(summary = "List seed rules and workflows")
  public ApiResponse<Map<String, Object>> list(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ApiResponse.ok(seeds.list(principal));
  }

  @PostMapping("/initialize")
  @Operation(summary = "Idempotent initialize of seed rules and workflows")
  public ApiResponse<Map<String, Object>> initialize(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) Map<String, Object> body) {
    if (body != null && body.isEmpty()) {
      // story contract: {}
    }
    return ApiResponse.ok(seeds.initialize(principal));
  }
}
