package com.nammamedmate.auth.application;

import com.nammamedmate.auth.application.port.out.PharmacyRecord;
import com.nammamedmate.auth.application.port.out.PharmacyStaffRecord;

public record PosPinLoginResult(
    String accessToken,
    long accessTtlSeconds,
    PharmacyStaffRecord staff,
    String roleInPharmacy,
    PharmacyRecord pharmacy) {}
