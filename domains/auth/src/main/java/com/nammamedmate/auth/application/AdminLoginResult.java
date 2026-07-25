package com.nammamedmate.auth.application;

import com.nammamedmate.auth.application.port.out.AdminStaffRecord;
import java.util.UUID;

/** Login outcome: either full tokens or an MFA challenge. */
public record AdminLoginResult(
    boolean mfaRequired,
    String accessToken,
    String refreshToken,
    long accessTtlSeconds,
    long refreshTtlSeconds,
    String mfaChallengeToken,
    long mfaChallengeExpiresIn,
    UUID adminId,
    AdminStaffRecord admin) {

  public static AdminLoginResult tokens(
      String accessToken,
      String refreshToken,
      long accessTtl,
      long refreshTtl,
      AdminStaffRecord admin) {
    return new AdminLoginResult(
        false, accessToken, refreshToken, accessTtl, refreshTtl, null, 0, admin.id(), admin);
  }

  public static AdminLoginResult challenge(String challengeToken, long expiresIn, UUID adminId) {
    return new AdminLoginResult(true, null, null, 0, 0, challengeToken, expiresIn, adminId, null);
  }
}
