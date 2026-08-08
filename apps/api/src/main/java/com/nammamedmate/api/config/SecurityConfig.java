package com.nammamedmate.api.config;

import com.nammamedmate.kernel.webhook.WebhookRawBodyFilter;
import com.nammamedmate.observability.RequestIdFilter;
import com.nammamedmate.security.ApiAccessDeniedHandler;
import com.nammamedmate.security.ApiAuthenticationEntryPoint;
import com.nammamedmate.security.JwtAuthenticationFilter;
import com.nammamedmate.security.MfaChallengeRestrictionFilter;
import com.nammamedmate.security.PosTokenRestrictionFilter;
import com.nammamedmate.security.Rs256JwtService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(HttpSecurity http, Rs256JwtService jwtService)
      throws Exception {
    JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(jwtService);
    PosTokenRestrictionFilter posFilter = new PosTokenRestrictionFilter();
    MfaChallengeRestrictionFilter mfaFilter = new MfaChallengeRestrictionFilter();
    http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(
            ex ->
                ex.authenticationEntryPoint(new ApiAuthenticationEntryPoint())
                    .accessDeniedHandler(new ApiAccessDeniedHandler()))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(HttpMethod.GET, "/api/v1/health")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/catalogue/**")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/catalogue/check-availability")
                    .permitAll()
                    .requestMatchers(
                        "/api/v1/auth/customer/send-otp",
                        "/api/v1/auth/customer/verify-otp",
                        "/api/v1/auth/pharmacy/login",
                        "/api/v1/auth/pharmacy/pos-pin",
                        "/api/v1/auth/admin/login",
                        "/api/v1/auth/refresh",
                        "/api/v1/pharmacy/register",
                        "/api/v1/pharmacy/register/verify-email",
                        "/api/v1/pharmacy/register/resend-otp")
                    .permitAll()
                    .requestMatchers("/api/v1/webhooks/**", "/api/v1/internal/kyc/**")
                    .permitAll()
                    .requestMatchers("/actuator/health", "/v3/api-docs/**", "/swagger-ui/**")
                    .permitAll()
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
                    .requestMatchers("/api/v1/customers/**")
                    .hasRole("CUSTOMER")
                    .requestMatchers("/api/v1/cart/**", "/api/v1/pharmacies/**")
                    .hasRole("CUSTOMER")
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
                    .requestMatchers("/api/v1/admin/roles", "/api/v1/admin/permissions")
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
                    .requestMatchers("/api/v1/admin/pharmacies/**")
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
        .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
        .addFilterAfter(posFilter, JwtAuthenticationFilter.class)
        .addFilterAfter(mfaFilter, PosTokenRestrictionFilter.class);
    return http.build();
  }
}
