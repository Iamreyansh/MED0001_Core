package com.nammamedmate.auth.application;

import java.util.UUID;

public record VerifyOtpCommand(
    UUID sessionId,
    String phone,
    String otp,
    String deviceToken,
    String clientIp,
    String userAgent) {}
