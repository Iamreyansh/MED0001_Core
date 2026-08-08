package com.nammamedmate.settings;

import com.nammamedmate.settings.adapter.in.web.AdminAuditInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AdminAuditWebConfig implements WebMvcConfigurer {

  private final AdminAuditInterceptor adminAuditInterceptor;

  public AdminAuditWebConfig(AdminAuditInterceptor adminAuditInterceptor) {
    this.adminAuditInterceptor = adminAuditInterceptor;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(adminAuditInterceptor).addPathPatterns("/api/v1/admin/**");
  }
}
