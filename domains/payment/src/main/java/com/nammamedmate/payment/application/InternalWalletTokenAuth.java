package com.nammamedmate.payment.application;

import com.nammamedmate.kernel.error.AppException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Shared-secret gate for {@code /api/v1/wallet/**} internal endpoints (story: service token).
 * Staging/prod must inject a non-blank token; blank local token rejects calls (fail closed for
 * HTTP).
 */
@Component
public class InternalWalletTokenAuth {

  private final String expectedToken;

  public InternalWalletTokenAuth(
      @Value("${medmate.internal.service-token:}") String expectedToken) {
    this.expectedToken = expectedToken == null ? "" : expectedToken.trim();
  }

  public void require(String providedHeader) {
    if (expectedToken.isEmpty()) {
      throw new AppException("UNAUTHORIZED", "Internal wallet token is not configured", 401);
    }
    String provided = providedHeader == null ? "" : providedHeader.trim();
    if (provided.isEmpty()
        || !MessageDigest.isEqual(
            expectedToken.getBytes(StandardCharsets.UTF_8),
            provided.getBytes(StandardCharsets.UTF_8))) {
      throw new AppException("UNAUTHORIZED", "Invalid or missing X-Internal-Token", 401);
    }
  }
}
