package com.nammamedmate.auth.adapter.in.web;

import com.nammamedmate.auth.application.RbacPermissionService;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.RequiresPermission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class RequiresPermissionInterceptor implements HandlerInterceptor {

  private final RbacPermissionService rbacPermissionService;

  public RequiresPermissionInterceptor(RbacPermissionService rbacPermissionService) {
    this.rbacPermissionService = rbacPermissionService;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod method)) {
      return true;
    }
    RequiresPermission annotation = method.getMethodAnnotation(RequiresPermission.class);
    if (annotation == null) {
      annotation = method.getBeanType().getAnnotation(RequiresPermission.class);
    }
    if (annotation == null) {
      return true;
    }
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    MedmatePrincipal principal =
        auth != null && auth.getPrincipal() instanceof MedmatePrincipal p ? p : null;
    if (principal == null) {
      throw new AppException("UNAUTHORIZED", "Authentication required", 401);
    }
    rbacPermissionService.requirePermission(principal, annotation.value());
    return true;
  }
}
