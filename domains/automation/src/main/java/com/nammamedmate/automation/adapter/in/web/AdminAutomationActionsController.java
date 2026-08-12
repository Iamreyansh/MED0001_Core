package com.nammamedmate.automation.adapter.in.web;

import com.nammamedmate.automation.application.ActionCatalogService;
import com.nammamedmate.kernel.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/automation/actions")
@Tag(name = "Admin automation actions")
public class AdminAutomationActionsController {

  private final ActionCatalogService catalog;

  public AdminAutomationActionsController(ActionCatalogService catalog) {
    this.catalog = catalog;
  }

  @GetMapping
  @Operation(summary = "List automation action registry")
  public ApiResponse<Map<String, Object>> list() {
    return ApiResponse.ok(catalog.list());
  }
}
