package com.nammamedmate.auth.application;

import java.util.UUID;

public record TokenPairResult(
    String accessToken,
    String refreshToken,
    String tokenType,
    long accessTokenExpiresIn,
    long refreshTokenExpiresIn,
    UUID sessionId) {}
