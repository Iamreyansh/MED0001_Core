package com.nammamedmate.support.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.support.application.AgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/support/agents")
@Tag(name = "Admin support agents")
public class AdminSupportAgentController {

  private final AgentService agents;

  public AdminSupportAgentController(AgentService agents) {
    this.agents = agents;
  }

  @GetMapping
  @Operation(summary = "List support agents roster")
  public ResponseEntity<ApiResponse<Map<String, Object>>> list(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    return ResponseEntity.ok(ApiResponse.ok(agents.listAgents(principal)));
  }

  @GetMapping("/suggest-assignment")
  @Operation(summary = "Suggest agent assignment for a ticket")
  public ResponseEntity<ApiResponse<Map<String, Object>>> suggest(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(name = "ticket_id") UUID ticketId) {
    return ResponseEntity.ok(ApiResponse.ok(agents.suggestAssignment(principal, ticketId)));
  }

  @GetMapping("/{id}")
  @Operation(summary = "Get agent detail and performance")
  public ResponseEntity<ApiResponse<Map<String, Object>>> detail(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable UUID id) {
    return ResponseEntity.ok(ApiResponse.ok(agents.getDetail(principal, id)));
  }

  @GetMapping("/{id}/workload")
  @Operation(summary = "Get agent workload breakdown")
  public ResponseEntity<ApiResponse<Map<String, Object>>> workload(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable UUID id) {
    return ResponseEntity.ok(ApiResponse.ok(agents.getWorkload(principal, id)));
  }

  @PatchMapping("/{id}/status")
  @Operation(summary = "Toggle agent online status")
  public ResponseEntity<ApiResponse<Map<String, Object>>> status(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable UUID id,
      @RequestBody(required = false) StatusRequest body) {
    StatusRequest req = body == null ? new StatusRequest(null) : body;
    return ResponseEntity.ok(ApiResponse.ok(agents.toggleStatus(principal, id, req.isOnline())));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record StatusRequest(Boolean isOnline) {}
}
