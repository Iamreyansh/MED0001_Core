package com.nammamedmate.auth.application.port.out;

import java.util.UUID;

public record PharmacyRecord(
    UUID id, String name, String logoUrl, String city, String subscriptionPlan) {}
