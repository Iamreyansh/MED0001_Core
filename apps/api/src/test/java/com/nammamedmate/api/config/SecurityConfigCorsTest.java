package com.nammamedmate.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.observability.RequestIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

class SecurityConfigCorsTest {

  private final CorsConfiguration cors = cors(false);

  private static CorsConfiguration cors(boolean allowLocalhost) {
    return new SecurityConfig()
        .corsConfigurationSource(allowLocalhost)
        .getCorsConfiguration(new MockHttpServletRequest("GET", "/api/v1/health"));
  }

  @Test
  void allowsHttpsSubdomainsAndApex() {
    assertThat(cors.checkOrigin("https://app.nammamedmate.com"))
        .isEqualTo("https://app.nammamedmate.com");
    assertThat(cors.checkOrigin("https://admin.nammamedmate.com"))
        .isEqualTo("https://admin.nammamedmate.com");
    assertThat(cors.checkOrigin("https://app.staging.nammamedmate.com"))
        .isEqualTo("https://app.staging.nammamedmate.com");
    assertThat(cors.checkOrigin("https://nammamedmate.com")).isEqualTo("https://nammamedmate.com");
  }

  @Test
  void rejectsForeignAndInsecureOrigins() {
    assertThat(cors.checkOrigin("https://evil.example")).isNull();
    assertThat(cors.checkOrigin("http://app.nammamedmate.com")).isNull();
    assertThat(cors.checkOrigin("https://nammamedmate.com.evil.com")).isNull();
    assertThat(cors.checkOrigin("https://nammamedmate.com.attacker.net")).isNull();
    assertThat(cors.checkOrigin("http://localhost:3000")).isNull();
  }

  @Test
  void allowsLocalhostWhenEnabled() {
    CorsConfiguration local = cors(true);
    assertThat(local.checkOrigin("http://localhost:3000")).isEqualTo("http://localhost:3000");
    assertThat(local.checkOrigin("http://localhost:5173")).isEqualTo("http://localhost:5173");
    assertThat(local.checkOrigin("http://127.0.0.1:4173")).isEqualTo("http://127.0.0.1:4173");
    assertThat(local.checkOrigin("https://localhost:5173")).isEqualTo("https://localhost:5173");
    assertThat(local.checkOrigin("https://evil.example")).isNull();
  }

  @Test
  void allowsCredentialedMethodsAndRequestId() {
    CorsConfigurationSource source = new SecurityConfig().corsConfigurationSource(false);
    assertThat(source).isNotNull();
    assertThat(cors.getAllowCredentials()).isTrue();
    assertThat(cors.getAllowedMethods())
        .containsExactly("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    assertThat(cors.getExposedHeaders()).containsExactly(RequestIdFilter.HEADER);
  }
}
