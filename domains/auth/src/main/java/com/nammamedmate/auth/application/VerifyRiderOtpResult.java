package com.nammamedmate.auth.application;

import com.nammamedmate.auth.application.port.out.RiderAccountPort.RiderAccount;

public record VerifyRiderOtpResult(
    String accessToken,
    String refreshToken,
    String tokenType,
    long accessTokenExpiresIn,
    long refreshTokenExpiresIn,
    RiderAccount rider) {}
