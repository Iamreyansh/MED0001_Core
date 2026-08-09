package com.nammamedmate.integration.adapter.in.web;

import com.nammamedmate.integration.application.CommunicationService;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/integrations/communications")
@Tag(name = "Admin Communication Integrations")
public class AdminCommunicationsController {

  private final CommunicationService service;

  public AdminCommunicationsController(CommunicationService service) {
    this.service = service;
  }

  @GetMapping("/status")
  @Operation(summary = "Communication channel health status")
  public ApiResponse<Map<String, Object>> status(
      @AuthenticationPrincipal MedmatePrincipal principal) {
    requireAuth(principal);
    return ApiResponse.ok(service.status(principal));
  }

  @PostMapping("/test")
  @Operation(summary = "Send a test message on a channel")
  public ApiResponse<Map<String, Object>> test(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) Map<String, Object> body) {
    requireAuth(principal);
    Map<String, Object> req = body == null ? Map.of() : body;
    return ApiResponse.ok(
        service.testSend(
            principal,
            str(req.get("channel")),
            str(req.get("recipient")),
            str(req.get("test_template"))));
  }

  @GetMapping("/usage")
  @Operation(summary = "Channel usage and cost statistics")
  public ApiResponse<Map<String, Object>> usage(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(required = false) String channel) {
    requireAuth(principal);
    return ApiResponse.ok(service.usage(principal, channel));
  }

  @PatchMapping("/config/{channel}")
  @Operation(summary = "Update channel configuration (admin_super)")
  public ApiResponse<Map<String, Object>> patchConfig(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @PathVariable("channel") String channel,
      @RequestBody(required = false) Map<String, Object> body) {
    requireAuth(principal);
    return ApiResponse.ok(service.patchConfig(principal, channel, body));
  }

  private static void requireAuth(MedmatePrincipal principal) {
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
  }

  private static String str(Object value) {
    return value == null ? null : String.valueOf(value);
  }
}
