package com.nammamedmate.security;

import java.util.UUID;

public record JwtClaims(
    UUID subject, AuthRole role, UUID pharmacyId, TokenScope tokenScope, String jti) {}
