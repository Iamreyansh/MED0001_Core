package com.nammamedmate.support.adapter.in.web;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.support.application.KnowledgeBaseService;
import com.nammamedmate.support.application.KnowledgeBaseService.ListResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/support/help")
@Tag(name = "Public help center")
public class PublicHelpController {

  private final KnowledgeBaseService kb;

  public PublicHelpController(KnowledgeBaseService kb) {
    this.kb = kb;
  }

  @GetMapping
  @Operation(summary = "Public help center — published articles only")
  public ApiResponse<Map<String, Object>> list(
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String q,
      HttpServletRequest request) {
    ListResult result = kb.publicHelp(category, q, clientIp(request));
    return ApiResponse.ok(result.data(), result.meta());
  }

  @GetMapping("/articles/{id}")
  @Operation(summary = "Read published help article (increments view_count)")
  public ApiResponse<Map<String, Object>> get(@PathVariable UUID id, HttpServletRequest request) {
    return ApiResponse.ok(kb.readPublicArticle(id, clientIp(request)));
  }

  @PostMapping("/deflection")
  @Operation(summary = "Log deflection event (auth optional)")
  public ApiResponse<Map<String, Object>> deflection(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestBody(required = false) DeflectionRequest body,
      HttpServletRequest request) {
    DeflectionRequest req = body == null ? new DeflectionRequest(null, null) : body;
    return ApiResponse.ok(
        kb.logDeflection(principal, req.articleId(), req.issueResolved(), clientIp(request)));
  }

  private static String clientIp(HttpServletRequest request) {
    if (request == null) {
      return null;
    }
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      return forwarded.split(",")[0].trim();
    }
    String remote = request.getRemoteAddr();
    return remote == null || remote.isBlank() ? null : remote.trim();
  }

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record DeflectionRequest(UUID articleId, Boolean issueResolved) {}
}
