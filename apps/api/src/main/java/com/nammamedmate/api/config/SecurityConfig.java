package com.nammamedmate.api.config;

import com.nammamedmate.kernel.webhook.WebhookRawBodyFilter;
import com.nammamedmate.observability.RequestIdFilter;
import com.nammamedmate.security.ApiAccessDeniedHandler;
import com.nammamedmate.security.ApiAuthenticationEntryPoint;
import com.nammamedmate.security.JwtAuthenticationFilter;
import com.nammamedmate.security.MfaChallengeRestrictionFilter;
import com.nammamedmate.security.PosTokenRestrictionFilter;
import com.nammamedmate.security.Rs256JwtService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      Rs256JwtService jwtService,
      @Value("${medmate.internal.service-token:}") String internalToken)
      throws Exception {
    JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(jwtService);
    PosTokenRestrictionFilter posFilter = new PosTokenRestrictionFilter();
    MfaChallengeRestrictionFilter mfaFilter = new MfaChallengeRestrictionFilter();
    http.csrf(AbstractHttpConfigurer::disable)
        .cors(Customizer.withDefaults())
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(
            ex ->
                ex.authenticationEntryPoint(new ApiAuthenticationEntryPoint())
                    .accessDeniedHandler(new ApiAccessDeniedHandler()))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(HttpMethod.OPTIONS, "/**")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/health")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/catalogue/**")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/catalogue/check-availability")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/delivery/fee-estimate")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/feature-flags/check")
                    .permitAll()
                    .requestMatchers(
                        "/api/v1/auth/customer/send-otp",
                        "/api/v1/auth/customer/verify-otp",
                        "/api/v1/auth/rider/send-otp",
                        "/api/v1/auth/rider/verify-otp",
                        "/api/v1/auth/pharmacy/login",
                        "/api/v1/auth/pharmacy/pos-pin",
                        "/api/v1/auth/admin/login",
                        "/api/v1/auth/admin/complete-invite",
                        "/api/v1/auth/admin/complete-reset",
                        "/api/v1/auth/refresh",
                        "/api/v1/pharmacy/register",
                        "/api/v1/pharmacy/register/verify-email",
                        "/api/v1/pharmacy/register/resend-otp",
                        "/api/v1/rider/register")
                    .permitAll()
                    // Accounting is pharmacy/admin JWT (not X-Internal-Token); match before
                    // integrations/** permitAll.
                    .requestMatchers(
                        HttpMethod.GET, "/api/v1/integrations/accounting/sync-status/**")
                    .hasAnyRole("PHARMACY_OWNER", "ADMIN_OPERATIONS", "ADMIN_SUPER")
                    .requestMatchers("/api/v1/integrations/accounting/**")
                    .hasRole("PHARMACY_OWNER")
                    .requestMatchers(
                        "/api/v1/webhooks/**",
                        "/api/v1/internal/kyc/**",
                        "/api/v1/wallet/**",
                        "/api/v1/integrations/**")
                    .permitAll()
                    // EPIC-017: internal push/SMS send (X-Internal-Token validated in service)
                    .requestMatchers(HttpMethod.POST, "/api/v1/notifications/push/send")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/notifications/sms/send")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/notifications/sms/webhook")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/payments/webhook/cashfree")
                    .permitAll()
                    .requestMatchers("/actuator/health", "/v3/api-docs/**", "/swagger-ui/**")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.POST, "/api/v1/payments/initiate", "/api/v1/payments/verify")
                    .hasRole("CUSTOMER")
                    .requestMatchers(HttpMethod.GET, "/api/v1/payments/**")
                    .hasAnyRole("CUSTOMER", "ADMIN_FINANCE", "ADMIN_SUPER")
                    .requestMatchers("/api/v1/payments/**")
                    .hasRole("CUSTOMER")
                    .requestMatchers("/api/v1/auth/pharmacy/switch-pharmacy")
                    .hasAnyRole("PHARMACY_OWNER", "PHARMACY_STAFF")
                    .requestMatchers("/api/v1/pharmacy/registration-status")
                    .hasAnyRole("PHARMACY_OWNER", "PHARMACY_STAFF")
                    .requestMatchers("/api/v1/pharmacy/roles", "/api/v1/pharmacy/roles/**")
                    .hasAnyRole("PHARMACY_OWNER", "PHARMACY_STAFF")
                    .requestMatchers(HttpMethod.GET, "/api/v1/pharmacy/kyc/documents")
                    .hasAnyRole("PHARMACY_OWNER", "PHARMACY_STAFF")
                    .requestMatchers("/api/v1/pharmacy/kyc/**")
                    .hasRole("PHARMACY_OWNER")
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/pharmacy/profile",
                        "/api/v1/pharmacy/profile/completeness")
                    .hasAnyRole("PHARMACY_OWNER", "PHARMACY_STAFF")
                    .requestMatchers("/api/v1/pharmacy/profile/**")
                    .hasRole("PHARMACY_OWNER")
                    .requestMatchers(HttpMethod.GET, "/api/v1/pharmacy/catalogue/search")
                    .hasAnyRole("PHARMACY_OWNER", "PHARMACY_STAFF")
                    .requestMatchers(HttpMethod.POST, "/api/v1/pharmacy/catalogue-mapping")
                    .hasRole("PHARMACY_OWNER")
                    .requestMatchers(HttpMethod.DELETE, "/api/v1/pharmacy/catalogue-mapping/**")
                    .hasRole("PHARMACY_OWNER")
                    .requestMatchers("/api/v1/pharmacy/catalogue-mapping/**")
                    .hasAnyRole("PHARMACY_OWNER", "PHARMACY_STAFF")
                    .requestMatchers("/api/v1/pharmacy/inventory", "/api/v1/pharmacy/inventory/**")
                    .hasAnyRole("PHARMACY_OWNER", "PHARMACY_STAFF")
                    // EPIC-016 STORY-004 pharmacy analytics (Growth+ gated in service)
                    .requestMatchers(HttpMethod.GET, "/api/v1/pharmacy/analytics/accounts-gst")
                    .hasRole("PHARMACY_OWNER")
                    .requestMatchers(
                        HttpMethod.PATCH, "/api/v1/pharmacy/analytics/reports/*/favorite")
                    .hasAnyRole("PHARMACY_OWNER", "PHARMACY_STAFF")
                    .requestMatchers("/api/v1/pharmacy/analytics", "/api/v1/pharmacy/analytics/**")
                    .hasAnyRole("PHARMACY_OWNER", "PHARMACY_STAFF")
                    .requestMatchers("/api/v1/pharmacy/pos", "/api/v1/pharmacy/pos/**")
                    .hasAnyRole("PHARMACY_OWNER", "PHARMACY_STAFF")
                    .requestMatchers(HttpMethod.GET, "/api/v1/pharmacy/offers")
                    .hasAnyRole("PHARMACY_OWNER", "PHARMACY_STAFF")
                    .requestMatchers(HttpMethod.POST, "/api/v1/pharmacy/offers/validate")
                    .hasAnyRole("PHARMACY_OWNER", "PHARMACY_STAFF")
                    .requestMatchers("/api/v1/pharmacy/offers", "/api/v1/pharmacy/offers/**")
                    .hasRole("PHARMACY_OWNER")
                    .requestMatchers(HttpMethod.PATCH, "/api/v1/pharmacy/invoice-settings")
                    .hasRole("PHARMACY_OWNER")
                    .requestMatchers("/api/v1/pharmacy/invoice-settings")
                    .hasAnyRole("PHARMACY_OWNER", "PHARMACY_STAFF")
                    // Admin finance/support: read-only invoice GET (not share / settings).
                    .requestMatchers(
                        HttpMethod.GET, "/api/v1/pharmacy/invoices", "/api/v1/pharmacy/invoices/**")
                    .hasAnyRole(
                        "PHARMACY_OWNER", "PHARMACY_STAFF", "ADMIN_FINANCE", "ADMIN_SUPPORT")
                    .requestMatchers("/api/v1/pharmacy/invoices", "/api/v1/pharmacy/invoices/**")
                    .hasAnyRole("PHARMACY_OWNER", "PHARMACY_STAFF")
                    .requestMatchers(HttpMethod.POST, "/api/v1/pharmacy/sales/*/mark-paid")
                    .hasAnyRole("PHARMACY_OWNER", "PHARMACY_STAFF")
                    .requestMatchers(
                        HttpMethod.GET, "/api/v1/pharmacy/sales", "/api/v1/pharmacy/sales/**")
                    .hasAnyRole(
                        "PHARMACY_OWNER", "PHARMACY_STAFF", "ADMIN_FINANCE", "ADMIN_COMPLIANCE")
                    .requestMatchers("/api/v1/pharmacy/sales", "/api/v1/pharmacy/sales/**")
                    .hasAnyRole("PHARMACY_OWNER", "PHARMACY_STAFF")
                    // Khata: remind allows staff at matcher so service returns STAFF_CANNOT_REMIND.
                    .requestMatchers(HttpMethod.POST, "/api/v1/pharmacy/khata/*/remind")
                    .hasAnyRole("PHARMACY_OWNER", "PHARMACY_STAFF")
                    .requestMatchers(HttpMethod.POST, "/api/v1/pharmacy/khata/*/repayment")
                    .hasAnyRole("PHARMACY_OWNER", "PHARMACY_STAFF")
                    .requestMatchers(
                        HttpMethod.GET, "/api/v1/pharmacy/khata", "/api/v1/pharmacy/khata/**")
                    .hasAnyRole(
                        "PHARMACY_OWNER", "PHARMACY_STAFF", "ADMIN_FINANCE", "ADMIN_SUPPORT")
                    .requestMatchers("/api/v1/pharmacy/khata", "/api/v1/pharmacy/khata/**")
                    .hasAnyRole("PHARMACY_OWNER", "PHARMACY_STAFF")
                    .requestMatchers(HttpMethod.POST, "/api/v1/pharmacy/rack-locations")
                    .hasRole("PHARMACY_OWNER")
                    .requestMatchers(HttpMethod.DELETE, "/api/v1/pharmacy/rack-locations/**")
                    .hasRole("PHARMACY_OWNER")
                    .requestMatchers(
                        "/api/v1/pharmacy/rack-locations", "/api/v1/pharmacy/rack-locations/**")
                    .hasAnyRole("PHARMACY_OWNER", "PHARMACY_STAFF")
                    .requestMatchers("/api/v1/pharmacy/purchases", "/api/v1/pharmacy/purchases/**")
                    .hasAnyRole("PHARMACY_OWNER", "PHARMACY_STAFF")
                    .requestMatchers(HttpMethod.POST, "/api/v1/pharmacy/distributors")
                    .hasRole("PHARMACY_OWNER")
                    .requestMatchers(HttpMethod.PATCH, "/api/v1/pharmacy/distributors/**")
                    .hasRole("PHARMACY_OWNER")
                    .requestMatchers(HttpMethod.DELETE, "/api/v1/pharmacy/distributors/**")
                    .hasRole("PHARMACY_OWNER")
                    .requestMatchers(HttpMethod.GET, "/api/v1/pharmacy/distributors/price-compare")
                    .hasRole("PHARMACY_OWNER")
                    .requestMatchers(
                        "/api/v1/pharmacy/distributors", "/api/v1/pharmacy/distributors/**")
                    .hasAnyRole("PHARMACY_OWNER", "PHARMACY_STAFF")
                    .requestMatchers(HttpMethod.POST, "/api/v1/pharmacy/reorder/refresh")
                    .hasRole("PHARMACY_OWNER")
                    .requestMatchers(
                        HttpMethod.POST, "/api/v1/pharmacy/reorder/purchase-orders/*/send")
                    .hasRole("PHARMACY_OWNER")
                    .requestMatchers("/api/v1/pharmacy/reorder", "/api/v1/pharmacy/reorder/**")
                    .hasAnyRole("PHARMACY_OWNER", "PHARMACY_STAFF")
                    .requestMatchers("/api/v1/customers/**")
                    .hasRole("CUSTOMER")
                    // EPIC-017 STORY-005 pharmacy notification preferences (before generic
                    // pharmacy)
                    .requestMatchers(HttpMethod.GET, "/api/v1/pharmacy/notification-preferences")
                    .hasAnyRole("PHARMACY_OWNER", "PHARMACY_STAFF")
                    .requestMatchers(HttpMethod.PATCH, "/api/v1/pharmacy/notification-preferences")
                    .hasRole("PHARMACY_OWNER")
                    // EPIC-017 STORY-001 device tokens + push open tracking
                    .requestMatchers("/api/v1/pharmacy/me/device-token")
                    .hasAnyRole("PHARMACY_STAFF", "PHARMACY_OWNER")
                    .requestMatchers("/api/v1/rider/me/device-token")
                    .hasRole("RIDER")
                    .requestMatchers(HttpMethod.POST, "/api/v1/notifications/push/opened")
                    .hasAnyRole("CUSTOMER", "PHARMACY_STAFF", "PHARMACY_OWNER", "RIDER")
                    .requestMatchers(HttpMethod.GET, "/api/v1/admin/notifications/push/logs")
                    .hasRole("ADMIN_SUPER")
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/notifications/broadcast")
                    .hasRole("ADMIN_SUPER")
                    .requestMatchers(HttpMethod.GET, "/api/v1/admin/notifications/sms/templates")
                    .hasRole("ADMIN_SUPER")
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/notifications/sms/templates")
                    .hasRole("ADMIN_SUPER")
                    .requestMatchers(HttpMethod.GET, "/api/v1/admin/notifications/sms/logs")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS")
                    .requestMatchers(HttpMethod.GET, "/api/v1/admin/notifications/history")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS")
                    .requestMatchers(
                        HttpMethod.GET, "/api/v1/prescriptions/eprescriptions/*/download")
                    .hasAnyRole("CUSTOMER", "ADMIN_COMPLIANCE")
                    .requestMatchers(
                        HttpMethod.POST, "/api/v1/prescriptions/eprescriptions/*/link-to-cart")
                    .hasRole("CUSTOMER")
                    .requestMatchers(HttpMethod.GET, "/api/v1/prescriptions/eprescriptions/*")
                    .hasAnyRole("CUSTOMER", "ADMIN_COMPLIANCE", "ADMIN_SUPER")
                    .requestMatchers("/api/v1/prescriptions/**")
                    .hasRole("CUSTOMER")
                    .requestMatchers("/api/v1/consults/**")
                    .hasRole("CUSTOMER")
                    .requestMatchers("/api/v1/admin/consults", "/api/v1/admin/consults/**")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS")
                    // EPIC-018 medicine schedule (public share before CUSTOMER catch-all)
                    .requestMatchers(HttpMethod.GET, "/api/v1/schedule/share/**")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/schedule/reminders/bulk-schedule")
                    .permitAll()
                    // EPIC-019: internal rules evaluate (X-Internal-Token validated in service)
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/automation/rules/evaluate")
                    .permitAll()
                    .requestMatchers("/api/v1/schedule/**")
                    .hasRole("CUSTOMER")
                    // EPIC-015 STORY-001..005 support + knowledge base
                    .requestMatchers(HttpMethod.GET, "/api/v1/support/help")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/support/help/articles/**")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/support/help/deflection")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/admin/support/canned-responses")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS", "ADMIN_SUPPORT")
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/support/canned-responses")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS")
                    .requestMatchers(HttpMethod.PATCH, "/api/v1/admin/support/canned-responses/**")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS")
                    .requestMatchers(HttpMethod.DELETE, "/api/v1/admin/support/canned-responses/**")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS")
                    .requestMatchers(
                        "/api/v1/admin/support/help-articles",
                        "/api/v1/admin/support/help-articles/**")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS")
                    .requestMatchers(HttpMethod.PATCH, "/api/v1/admin/support/sla-policies/**")
                    .hasRole("ADMIN_SUPER")
                    .requestMatchers(HttpMethod.PATCH, "/api/v1/admin/support/escalation-matrix")
                    .hasRole("ADMIN_SUPER")
                    .requestMatchers(HttpMethod.GET, "/api/v1/admin/support/escalation-matrix")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS")
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/admin/support/sla-policies",
                        "/api/v1/admin/support/sla-policies/**",
                        "/api/v1/admin/support/sla-breaches",
                        "/api/v1/admin/support/sla-breaches/**")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS", "ADMIN_SUPPORT", "ADMIN_FINANCE")
                    .requestMatchers(HttpMethod.GET, "/api/v1/admin/support/agents")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS")
                    .requestMatchers(HttpMethod.GET, "/api/v1/admin/support/agents/*/workload")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS", "ADMIN_SUPPORT")
                    .requestMatchers(
                        HttpMethod.GET, "/api/v1/admin/support/agents/suggest-assignment")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS", "ADMIN_SUPPORT")
                    .requestMatchers(HttpMethod.PATCH, "/api/v1/admin/support/agents/*/status")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS", "ADMIN_SUPPORT")
                    .requestMatchers(HttpMethod.GET, "/api/v1/admin/support/agents/*")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS")
                    .requestMatchers(
                        "/api/v1/admin/support/tickets",
                        "/api/v1/admin/support/tickets/**",
                        "/api/v1/admin/support/disputes",
                        "/api/v1/admin/support/disputes/**")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS", "ADMIN_SUPPORT")
                    .requestMatchers(
                        "/api/v1/support/tickets",
                        "/api/v1/support/tickets/**",
                        "/api/v1/support/disputes",
                        "/api/v1/support/disputes/**")
                    .hasAnyRole(
                        "CUSTOMER",
                        "PHARMACY_OWNER",
                        "PHARMACY_STAFF",
                        "ADMIN_SUPER",
                        "ADMIN_OPERATIONS",
                        "ADMIN_SUPPORT")
                    .requestMatchers("/api/v1/cart/**", "/api/v1/pharmacies/**")
                    .hasRole("CUSTOMER")
                    .requestMatchers("/api/v1/rider/kyc/**")
                    .hasRole("RIDER")
                    .requestMatchers("/api/v1/rider/status", "/api/v1/rider/status/**")
                    .hasRole("RIDER")
                    .requestMatchers("/api/v1/rider/orders", "/api/v1/rider/orders/**")
                    .hasRole("RIDER")
                    .requestMatchers("/api/v1/rider/location", "/api/v1/rider/location/**")
                    .hasRole("RIDER")
                    .requestMatchers("/api/v1/rider/cod", "/api/v1/rider/cod/**")
                    .hasRole("RIDER")
                    .requestMatchers(
                        "/api/v1/rider/earnings",
                        "/api/v1/rider/performance",
                        "/api/v1/rider/trips",
                        "/api/v1/rider/payouts",
                        "/api/v1/rider/payouts/**")
                    .hasRole("RIDER")
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/finance/cod/*/mark-deposited")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_FINANCE")
                    .requestMatchers("/api/v1/admin/finance/cod", "/api/v1/admin/finance/cod/**")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS", "ADMIN_FINANCE")
                    .requestMatchers(
                        HttpMethod.POST, "/api/v1/admin/finance/cod-float/auto-reconcile")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_FINANCE")
                    .requestMatchers(
                        "/api/v1/admin/finance/cod-float/reconciliation-report",
                        "/api/v1/admin/finance/cod-float/reconciliation-report/**")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_FINANCE")
                    .requestMatchers("/api/v1/admin/finance/cod-float")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS", "ADMIN_FINANCE")
                    .requestMatchers(
                        "/api/v1/admin/finance/taxes", "/api/v1/admin/finance/taxes/**")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_FINANCE", "ADMIN_COMPLIANCE")
                    .requestMatchers(
                        "/api/v1/admin/finance/ledger", "/api/v1/admin/finance/ledger/**")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_FINANCE")
                    .requestMatchers(
                        "/api/v1/admin/finance/kpi",
                        "/api/v1/admin/finance/pnl",
                        "/api/v1/admin/finance/cash-position",
                        "/api/v1/admin/finance/ratios")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_FINANCE")
                    .requestMatchers(
                        "/api/v1/admin/finance/settlements", "/api/v1/admin/finance/settlements/**")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_FINANCE")
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/finance/refunds/*/process")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_FINANCE")
                    .requestMatchers(
                        "/api/v1/admin/finance/refunds", "/api/v1/admin/finance/refunds/**")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_FINANCE", "ADMIN_SUPPORT")
                    .requestMatchers(
                        "/api/v1/admin/finance/rider-payouts",
                        "/api/v1/admin/finance/rider-payouts/**")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_FINANCE")
                    .requestMatchers(
                        "/api/v1/pharmacy/finance/settlements",
                        "/api/v1/pharmacy/finance/settlements/**")
                    .hasRole("PHARMACY_OWNER")
                    // EPIC-014 CRM SaaS — pharmacy subscription/billing + admin CRM.
                    .requestMatchers(HttpMethod.GET, "/api/v1/pharmacy/subscription")
                    .hasAnyRole("PHARMACY_OWNER", "PHARMACY_STAFF")
                    .requestMatchers(
                        "/api/v1/pharmacy/subscription", "/api/v1/pharmacy/subscription/**")
                    .hasRole("PHARMACY_OWNER")
                    .requestMatchers("/api/v1/pharmacy/billing", "/api/v1/pharmacy/billing/**")
                    .hasRole("PHARMACY_OWNER")
                    .requestMatchers(
                        "/api/v1/admin/crm/analytics", "/api/v1/admin/crm/analytics/**")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_FINANCE")
                    // EPIC-016 STORY-006 report library
                    .requestMatchers(HttpMethod.GET, "/api/v1/admin/reports/history")
                    .hasAnyRole(
                        "ADMIN_SUPER",
                        "ADMIN_FINANCE",
                        "ADMIN_OPERATIONS",
                        "ADMIN_COMPLIANCE",
                        "ADMIN_SUPPORT")
                    .requestMatchers(HttpMethod.GET, "/api/v1/admin/reports/jobs/**")
                    .hasAnyRole(
                        "ADMIN_SUPER",
                        "ADMIN_FINANCE",
                        "ADMIN_OPERATIONS",
                        "ADMIN_COMPLIANCE",
                        "ADMIN_SUPPORT")
                    .requestMatchers("/api/v1/admin/reports", "/api/v1/admin/reports/**")
                    .hasAnyRole(
                        "ADMIN_SUPER", "ADMIN_FINANCE", "ADMIN_OPERATIONS", "ADMIN_COMPLIANCE")
                    .requestMatchers("/api/v1/admin/analytics", "/api/v1/admin/analytics/**")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS", "ADMIN_FINANCE")
                    // EPIC-020 monitoring: support alerts list; finance metrics subset
                    // (service-enforced)
                    .requestMatchers(HttpMethod.GET, "/api/v1/admin/monitoring/alerts")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS", "ADMIN_SUPPORT")
                    .requestMatchers(HttpMethod.GET, "/api/v1/admin/monitoring/metrics")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS", "ADMIN_FINANCE")
                    .requestMatchers(HttpMethod.GET, "/api/v1/admin/monitoring/incidents")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS", "ADMIN_SUPPORT", "ADMIN_FINANCE")
                    .requestMatchers(
                        HttpMethod.PATCH, "/api/v1/admin/monitoring/remediation-playbooks/**")
                    .hasRole("ADMIN_SUPER")
                    .requestMatchers("/api/v1/admin/monitoring", "/api/v1/admin/monitoring/**")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS")
                    // EPIC-019 STORY-005 activity: finance read-only on financial action types
                    .requestMatchers(HttpMethod.GET, "/api/v1/admin/automation/activity/stats")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS")
                    .requestMatchers(
                        HttpMethod.POST, "/api/v1/admin/automation/activity/*/rollback")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS")
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/admin/automation/activity",
                        "/api/v1/admin/automation/activity/*")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS", "ADMIN_FINANCE")
                    // EPIC-019 STORY-006 approvals: finance can list/get/resolve FINANCE only
                    .requestMatchers(HttpMethod.GET, "/api/v1/admin/automation/approvals/stats")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS")
                    .requestMatchers(
                        "/api/v1/admin/automation/approvals",
                        "/api/v1/admin/automation/approvals/**")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS", "ADMIN_FINANCE")
                    // EPIC-019 STORY-007 kill switch: admin_super only (before catch-all)
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/automation/kill-switch")
                    .hasRole("ADMIN_SUPER")
                    // EPIC-019 STORY-008 seed initialize: admin_super only
                    .requestMatchers(
                        HttpMethod.POST, "/api/v1/admin/automation/seed-rules/initialize")
                    .hasRole("ADMIN_SUPER")
                    // EPIC-019 STORY-001 automation registries (evaluate is permitAll above)
                    .requestMatchers("/api/v1/admin/automation", "/api/v1/admin/automation/**")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS")
                    .requestMatchers("/api/v1/admin/crm", "/api/v1/admin/crm/**")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS", "ADMIN_FINANCE")
                    // EPIC-013 STORY-004 customer segments
                    .requestMatchers(HttpMethod.DELETE, "/api/v1/admin/segments/*")
                    .hasRole("ADMIN_SUPER")
                    .requestMatchers(HttpMethod.GET, "/api/v1/admin/segments/*/customers")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS")
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/segments/*/compute")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS")
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/segments")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS")
                    .requestMatchers("/api/v1/admin/segments", "/api/v1/admin/segments/**")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS", "ADMIN_FINANCE")
                    // EPIC-013 STORY-001 coupons
                    .requestMatchers(HttpMethod.DELETE, "/api/v1/admin/coupons/*")
                    .hasRole("ADMIN_SUPER")
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/coupons")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS")
                    .requestMatchers(HttpMethod.PATCH, "/api/v1/admin/coupons/**")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS")
                    .requestMatchers("/api/v1/admin/coupons", "/api/v1/admin/coupons/**")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS", "ADMIN_FINANCE")
                    .requestMatchers("/api/v1/coupons", "/api/v1/coupons/**")
                    .hasRole("CUSTOMER")
                    // EPIC-013 STORY-002 banners
                    .requestMatchers(HttpMethod.DELETE, "/api/v1/admin/banners/*")
                    .hasRole("ADMIN_SUPER")
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/banners")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS")
                    .requestMatchers(HttpMethod.PATCH, "/api/v1/admin/banners/**")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS")
                    .requestMatchers("/api/v1/admin/banners", "/api/v1/admin/banners/**")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS", "ADMIN_FINANCE")
                    .requestMatchers("/api/v1/banners", "/api/v1/banners/**")
                    .hasRole("CUSTOMER")
                    // EPIC-013 STORY-003 campaigns (super|ops write; finance read)
                    .requestMatchers(HttpMethod.GET, "/api/v1/admin/campaigns/cost-estimate")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS")
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/campaigns")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS")
                    .requestMatchers(HttpMethod.PATCH, "/api/v1/admin/campaigns/*")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS")
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/campaigns/*/launch")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS")
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/campaigns/*/pause")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS")
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/campaigns/*/resume")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS")
                    .requestMatchers("/api/v1/admin/campaigns", "/api/v1/admin/campaigns/**")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS", "ADMIN_FINANCE")
                    // EPIC-013 STORY-005 referral program (admin)
                    .requestMatchers(HttpMethod.PATCH, "/api/v1/admin/referrals/program")
                    .hasRole("ADMIN_SUPER")
                    .requestMatchers("/api/v1/admin/referrals", "/api/v1/admin/referrals/**")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS")
                    // EPIC-013 STORY-006 loyalty program (admin)
                    .requestMatchers(HttpMethod.PATCH, "/api/v1/admin/loyalty/program")
                    .hasRole("ADMIN_SUPER")
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/loyalty/customers/*/adjust")
                    .hasRole("ADMIN_SUPER")
                    .requestMatchers("/api/v1/admin/loyalty", "/api/v1/admin/loyalty/**")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS", "ADMIN_FINANCE")
                    .requestMatchers(HttpMethod.GET, "/api/v1/admin/riders/*/earnings-ledger")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_FINANCE")
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/riders/*/payout/release")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_FINANCE")
                    .requestMatchers(HttpMethod.GET, "/api/v1/admin/riders/*/performance")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS", "ADMIN_FINANCE")
                    .requestMatchers("/api/v1/admin/dispatch", "/api/v1/admin/dispatch/**")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS")
                    .requestMatchers("/api/v1/admin/geofences", "/api/v1/admin/geofences/**")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS")
                    .requestMatchers(HttpMethod.POST, "/api/v1/orders/*/payment/cod-collect")
                    .hasRole("RIDER")
                    .requestMatchers("/api/v1/orders/rx-quote/**")
                    .hasRole("CUSTOMER")
                    .requestMatchers("/api/v1/orders", "/api/v1/orders/**")
                    .hasRole("CUSTOMER")
                    .requestMatchers("/api/v1/pharmacy/orders/**")
                    .hasAnyRole("PHARMACY_OWNER", "PHARMACY_STAFF")
                    .requestMatchers("/api/v1/pharmacy/rx-quotes/**")
                    .hasAnyRole("PHARMACY_OWNER", "PHARMACY_STAFF")
                    .requestMatchers(
                        "/api/v1/pharmacy/prescriptions", "/api/v1/pharmacy/prescriptions/**")
                    .hasAnyRole("PHARMACY_OWNER", "PHARMACY_STAFF")
                    .requestMatchers(
                        "/api/v1/pharmacy/compliance/drug-register",
                        "/api/v1/pharmacy/compliance/drug-register/**")
                    .hasAnyRole("PHARMACY_OWNER", "PHARMACY_STAFF")
                    .requestMatchers(
                        HttpMethod.POST, "/api/v1/admin/compliance/drug-register/export")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_COMPLIANCE")
                    .requestMatchers(
                        HttpMethod.GET, "/api/v1/admin/compliance/drug-register/export/**")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_COMPLIANCE")
                    .requestMatchers(
                        HttpMethod.GET, "/api/v1/admin/compliance/drug-register/retention-rules")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_COMPLIANCE", "PHARMACY_OWNER")
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/admin/compliance/drug-register",
                        "/api/v1/admin/compliance/drug-register/**")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_COMPLIANCE", "ADMIN_OPERATIONS")
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/compliance/filings/*/generate")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_COMPLIANCE")
                    .requestMatchers(
                        HttpMethod.GET, "/api/v1/admin/compliance/filings/*/generate/**")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_COMPLIANCE")
                    .requestMatchers(
                        HttpMethod.POST, "/api/v1/admin/compliance/filings/*/mark-filed")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_COMPLIANCE")
                    .requestMatchers(HttpMethod.GET, "/api/v1/admin/compliance/filings")
                    .hasAnyRole(
                        "ADMIN_SUPER", "ADMIN_COMPLIANCE", "ADMIN_OPERATIONS", "ADMIN_FINANCE")
                    .requestMatchers(HttpMethod.GET, "/api/v1/admin/compliance/activity-log")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_COMPLIANCE", "ADMIN_OPERATIONS")
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/compliance/drug-recalls")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_COMPLIANCE")
                    .requestMatchers(HttpMethod.GET, "/api/v1/admin/prescriptions/statistics")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_COMPLIANCE")
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/prescriptions/*/verify")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_COMPLIANCE")
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/prescriptions/*/flag")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_COMPLIANCE")
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/admin/prescriptions",
                        "/api/v1/admin/prescriptions/**")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_COMPLIANCE", "ADMIN_OPERATIONS")
                    .requestMatchers(HttpMethod.GET, "/api/v1/admin/doctors/unverified")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_COMPLIANCE")
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/doctors/*/verify")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_COMPLIANCE")
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/doctors/*/blacklist")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_COMPLIANCE")
                    .requestMatchers(
                        HttpMethod.GET, "/api/v1/admin/doctors", "/api/v1/admin/doctors/**")
                    .hasAnyRole(
                        "ADMIN_SUPER", "ADMIN_COMPLIANCE", "ADMIN_OPERATIONS", "ADMIN_SUPPORT")
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/teleconsult/doctors")
                    .hasRole("ADMIN_SUPER")
                    .requestMatchers(
                        HttpMethod.PATCH, "/api/v1/admin/teleconsult/doctors/*/availability")
                    .hasRole("ADMIN_SUPER")
                    .requestMatchers(HttpMethod.PATCH, "/api/v1/admin/teleconsult/doctors/*")
                    .hasRole("ADMIN_SUPER")
                    .requestMatchers(HttpMethod.GET, "/api/v1/admin/teleconsult/doctors/*/stats")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS")
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/admin/teleconsult/doctors",
                        "/api/v1/admin/teleconsult/doctors/**")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_COMPLIANCE", "ADMIN_OPERATIONS")
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/orders/*/cancel")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS")
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/orders/*/refund")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS", "ADMIN_FINANCE")
                    .requestMatchers(HttpMethod.GET, "/api/v1/admin/orders/*/refund-eligibility")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS", "ADMIN_FINANCE")
                    .requestMatchers(HttpMethod.PATCH, "/api/v1/admin/orders/*/status")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS")
                    .requestMatchers(HttpMethod.PATCH, "/api/v1/admin/orders/*/rider")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS")
                    .requestMatchers(HttpMethod.GET, "/api/v1/admin/orders/live-feed")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS")
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/orders/*/dispute")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS", "ADMIN_SUPPORT")
                    .requestMatchers(HttpMethod.POST, "/api/v1/admin/orders/*/note")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS", "ADMIN_SUPPORT", "ADMIN_FINANCE")
                    .requestMatchers("/api/v1/admin/orders/**")
                    .hasAnyRole(
                        "ADMIN_SUPER",
                        "ADMIN_OPERATIONS",
                        "ADMIN_FINANCE",
                        "ADMIN_SUPPORT",
                        "ADMIN_COMPLIANCE")
                    .requestMatchers(HttpMethod.GET, "/api/v1/admin/catalogue/schedule-rules")
                    .hasAnyRole(
                        "ADMIN_SUPER",
                        "ADMIN_OPERATIONS",
                        "ADMIN_COMPLIANCE",
                        "PHARMACY_OWNER",
                        "PHARMACY_STAFF")
                    .requestMatchers("/api/v1/admin/catalogue/**")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS", "ADMIN_COMPLIANCE")
                    .requestMatchers(
                        "/api/v1/admin/roles",
                        "/api/v1/admin/roles/**",
                        "/api/v1/admin/permissions")
                    .hasAnyRole(
                        "ADMIN_SUPER",
                        "ADMIN_OPERATIONS",
                        "ADMIN_FINANCE",
                        "ADMIN_SUPPORT",
                        "ADMIN_COMPLIANCE")
                    .requestMatchers("/api/v1/admin/customers/**")
                    .hasAnyRole(
                        "ADMIN_SUPER",
                        "ADMIN_OPERATIONS",
                        "ADMIN_FINANCE",
                        "ADMIN_SUPPORT",
                        "ADMIN_COMPLIANCE")
                    // EPIC-016 STORY-004 admin pharmacy analytics impersonation (before
                    // pharmacies/**)
                    .requestMatchers(
                        "/api/v1/admin/pharmacies/*/analytics",
                        "/api/v1/admin/pharmacies/*/analytics/**")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS")
                    .requestMatchers("/api/v1/admin/pharmacies/**")
                    .hasAnyRole(
                        "ADMIN_SUPER",
                        "ADMIN_OPERATIONS",
                        "ADMIN_FINANCE",
                        "ADMIN_SUPPORT",
                        "ADMIN_COMPLIANCE")
                    .requestMatchers("/api/v1/admin/riders/**")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS")
                    .requestMatchers("/api/v1/admin/zones/**")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS")
                    .requestMatchers("/api/v1/admin/staff", "/api/v1/admin/staff/**")
                    .hasAnyRole(
                        "ADMIN_SUPER",
                        "ADMIN_OPERATIONS",
                        "ADMIN_FINANCE",
                        "ADMIN_SUPPORT",
                        "ADMIN_COMPLIANCE")
                    .requestMatchers(
                        "/api/v1/admin/feature-flags", "/api/v1/admin/feature-flags/**")
                    .hasAnyRole(
                        "ADMIN_SUPER",
                        "ADMIN_OPERATIONS",
                        "ADMIN_FINANCE",
                        "ADMIN_SUPPORT",
                        "ADMIN_COMPLIANCE")
                    .requestMatchers("/api/v1/admin/config", "/api/v1/admin/config/**")
                    .hasAnyRole(
                        "ADMIN_SUPER",
                        "ADMIN_OPERATIONS",
                        "ADMIN_FINANCE",
                        "ADMIN_SUPPORT",
                        "ADMIN_COMPLIANCE")
                    .requestMatchers(
                        HttpMethod.PATCH, "/api/v1/admin/integrations/communications/config/**")
                    .hasRole("ADMIN_SUPER")
                    .requestMatchers("/api/v1/admin/integrations/communications/**")
                    .hasAnyRole("ADMIN_SUPER", "ADMIN_OPERATIONS")
                    .requestMatchers("/api/v1/admin/audit-log", "/api/v1/admin/audit-log/**")
                    .hasAnyRole(
                        "ADMIN_SUPER",
                        "ADMIN_OPERATIONS",
                        "ADMIN_FINANCE",
                        "ADMIN_SUPPORT",
                        "ADMIN_COMPLIANCE")
                    .requestMatchers(
                        "/api/v1/auth/admin/verify-mfa", "/api/v1/auth/admin/setup-mfa")
                    .hasAnyRole(
                        "ADMIN_SUPER",
                        "ADMIN_OPERATIONS",
                        "ADMIN_FINANCE",
                        "ADMIN_SUPPORT",
                        "ADMIN_COMPLIANCE")
                    .anyRequest()
                    .authenticated())
        .addFilterBefore(new RequestIdFilter(), UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(new WebhookRawBodyFilter(), UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(
            new InternalServiceTokenFilter(internalToken),
            UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterAfter(posFilter, JwtAuthenticationFilter.class)
        .addFilterAfter(mfaFilter, PosTokenRestrictionFilter.class);
    return http.build();
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource(
      @Value("${medmate.cors.allow-localhost:false}") boolean allowLocalhost) {
    CorsConfiguration config = new CorsConfiguration();
    List<String> origins =
        new ArrayList<>(List.of("https://*.nammamedmate.com", "https://nammamedmate.com"));
    if (allowLocalhost) {
      origins.addAll(
          List.of(
              "http://localhost:[*]",
              "http://127.0.0.1:[*]",
              "http://[::1]:[*]",
              "https://localhost:[*]",
              "https://127.0.0.1:[*]"));
    }
    config.setAllowedOriginPatterns(origins);
    config.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("*"));
    config.setExposedHeaders(List.of(RequestIdFilter.HEADER));
    config.setAllowCredentials(true);
    config.setMaxAge(3600L);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
  }
}
