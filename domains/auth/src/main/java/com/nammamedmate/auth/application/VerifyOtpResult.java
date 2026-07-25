package com.nammamedmate.auth.application;

import com.nammamedmate.auth.application.port.out.CustomerRecord;

public record VerifyOtpResult(
    String accessToken,
    String refreshToken,
    String tokenType,
    long accessTokenExpiresIn,
    long refreshTokenExpiresIn,
    boolean newUser,
    CustomerRecord customer) {}
