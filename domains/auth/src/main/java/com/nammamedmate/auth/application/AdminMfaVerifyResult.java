package com.nammamedmate.auth.application;

import com.nammamedmate.auth.application.port.out.AdminStaffRecord;

public record AdminMfaVerifyResult(
    String accessToken,
    String refreshToken,
    long accessTtlSeconds,
    long refreshTtlSeconds,
    boolean usedBackupCode,
    AdminStaffRecord admin,
    int backupCodesRemaining) {}
