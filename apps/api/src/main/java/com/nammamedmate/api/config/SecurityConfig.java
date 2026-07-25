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
                    .requestMatchers(
                        "/api/v1/auth/customer/send-otp",
                        "/api/v1/auth/customer/verify-otp",
                        "/api/v1/auth/pharmacy/login",
                        "/api/v1/auth/pharmacy/pos-pin",
                        "/api/v1/auth/admin/login",
                        "/api/v1/auth/refresh")
                    .permitAll()
                    .requestMatchers("/api/v1/webhooks/**")
                    .permitAll()
                    .requestMatchers("/actuator/health", "/v3/api-docs/**", "/swagger-ui/**")
                    .permitAll()
                    .requestMatchers("/api/v1/auth/pharmacy/switch-pharmacy")
                    .hasAnyRole("PHARMACY_OWNER", "PHARMACY_STAFF")
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
