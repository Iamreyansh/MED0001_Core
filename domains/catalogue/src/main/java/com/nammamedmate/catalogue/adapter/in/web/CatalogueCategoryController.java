package com.nammamedmate.catalogue.adapter.in.web;

import com.nammamedmate.catalogue.application.CategoryService;
import com.nammamedmate.catalogue.application.CategoryService.CategoryListResult;
import com.nammamedmate.security.MedmatePrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/catalogue/categories")
@Tag(name = "Catalogue categories")
public class CatalogueCategoryController {

  private final CategoryService service;

  public CatalogueCategoryController(CategoryService service) {
    this.service = service;
  }

  @GetMapping
  @Operation(summary = "List visible medicine categories (public, Redis-cached 5m)")
  public Map<String, Object> list(
      @AuthenticationPrincipal MedmatePrincipal principal,
      @RequestParam(name = "include_hidden", defaultValue = "false") boolean includeHidden,
      @RequestParam(name = "include_deleted", defaultValue = "false") boolean includeDeleted,
      HttpServletRequest request) {
    CategoryListResult result =
        service.listPublic(principal, includeHidden, includeDeleted, clientIp(request));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("success", true);
    body.put("data", result.data());
    body.put("meta", result.meta());
    return body;
  }

  private static String clientIp(HttpServletRequest request) {
    if (request == null) {
      return null;
    }
    String remote = request.getRemoteAddr();
    return remote == null || remote.isBlank() ? null : remote.trim();
  }
}
