package com.nammamedmate.support.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.support.application.TicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/support/tickets")
@Tag(name = "Support tickets")
public class SupportTicketController {

  private final TicketService tickets;

  public SupportTicketController(TicketService tickets) {
    this.tickets = tickets;
  }

  @PostMapping
  @Operation(summary = "Create support ticket")
  public ResponseEntity<ApiResponse<Map<String, Object>>> create(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) CreateTicketRequest body) {
    CreateTicketRequest req =
        body == null
            ? new CreateTicketRequest(null, null, null, null, null, null, null, null)
            : body;
    Map<String, Object> data =
        tickets.create(
            principal,
            new TicketService.CreateCommand(
                req.category(),
                req.subject(),
                req.description(),
                req.channel(),
                req.orderId(),
                req.pharmacyId(),
                req.attachments(),
                req.priority()));
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data));
  }

  @GetMapping("/{id}")
  @Operation(summary = "Get ticket detail")
  public ApiResponse<Map<String, Object>> get(
      @AuthenticationPrincipal MedmatePrincipal principal, @PathVariable("id") UUID id) {
    return ApiResponse.ok(tickets.get(principal, id));
  }

  @PostMapping("/{id}/reply")
  @Operation(summary = "Reply to ticket")
  public ResponseEntity<ApiResponse<Map<String, Object>>> reply(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody(required = false) ReplyRequest body) {
    ReplyRequest req = body == null ? new ReplyRequest(null, null, null, null) : body;
    Map<String, Object> data =
        tickets.reply(
            principal,
            id,
            new TicketService.ReplyCommand(
                req.message(), req.isInternalNote(), req.attachments(), req.cannedResponseId()));
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(data));
  }

  @PostMapping("/{id}/assign")
  @Operation(summary = "Assign ticket to agent")
  public ApiResponse<Map<String, Object>> assign(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody(required = false) AssignRequest body) {
    AssignRequest req = body == null ? new AssignRequest(null) : body;
    return ApiResponse.ok(tickets.assign(principal, id, req.agentId()));
  }

  @PostMapping("/{id}/resolve")
  @Operation(summary = "Resolve ticket")
  public ApiResponse<Map<String, Object>> resolve(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody(required = false) ResolveRequest body) {
    ResolveRequest req = body == null ? new ResolveRequest(null) : body;
    return ApiResponse.ok(tickets.resolve(principal, id, req.resolutionSummary()));
  }

  @PostMapping("/{id}/reopen")
  @Operation(summary = "Reopen ticket")
  public ApiResponse<Map<String, Object>> reopen(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody(required = false) ReopenRequest body) {
    ReopenRequest req = body == null ? new ReopenRequest(null) : body;
    return ApiResponse.ok(tickets.reopen(principal, id, req.reason()));
  }

  @PostMapping("/{id}/escalate")
  @Operation(summary = "Escalate ticket")
  public ApiResponse<Map<String, Object>> escalate(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody(required = false) EscalateRequest body) {
    EscalateRequest req = body == null ? new EscalateRequest(null, null) : body;
    return ApiResponse.ok(tickets.escalate(principal, id, req.escalationLevel(), req.reason()));
  }

  @PatchMapping("/{id}/priority")
  @Operation(summary = "Change ticket priority")
  public ApiResponse<Map<String, Object>> changePriority(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("id") UUID id,
      @RequestBody(required = false) PriorityRequest body) {
    PriorityRequest req = body == null ? new PriorityRequest(null) : body;
    return ApiResponse.ok(tickets.changePriority(principal, id, req.priority()));
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record CreateTicketRequest(
      String category,
      String subject,
      String description,
      String channel,
      UUID orderId,
      UUID pharmacyId,
      List<String> attachments,
      String priority) {
    public CreateTicketRequest {
      attachments = attachments == null ? null : List.copyOf(attachments);
    }
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ReplyRequest(
      String message, Boolean isInternalNote, List<String> attachments, UUID cannedResponseId) {
    public ReplyRequest {
      attachments = attachments == null ? null : List.copyOf(attachments);
    }
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record AssignRequest(UUID agentId) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ResolveRequest(String resolutionSummary) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record ReopenRequest(String reason) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record EscalateRequest(String escalationLevel, String reason) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record PriorityRequest(String priority) {}
}
