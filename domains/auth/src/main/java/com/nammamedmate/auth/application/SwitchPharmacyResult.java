package com.nammamedmate.auth.application;

import com.nammamedmate.auth.application.port.out.PharmacyRecord;

public record SwitchPharmacyResult(
    String accessToken, long accessTtlSeconds, PharmacyRecord pharmacy, String roleInPharmacy) {}
