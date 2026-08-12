package com.nammamedmate.automation.adapter.in.web;

import com.nammamedmate.automation.application.TriggerCatalogService;
import com.nammamedmate.kernel.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/automation/triggers")
@Tag(name = "Admin automation triggers")
public class AdminAutomationTriggersController {

  private final TriggerCatalogService catalog;

  public AdminAutomationTriggersController(TriggerCatalogService catalog) {
    this.catalog = catalog;
  }

  @GetMapping
  @Operation(summary = "List automation trigger registry")
  public ApiResponse<Map<String, Object>> list(@RequestParam(required = false) String category) {
    return ApiResponse.ok(catalog.list(category));
  }
}
