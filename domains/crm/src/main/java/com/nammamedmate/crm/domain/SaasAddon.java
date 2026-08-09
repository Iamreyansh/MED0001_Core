package com.nammamedmate.crm.domain;

import java.util.UUID;

public record SaasAddon(
    UUID id, String name, long priceMonthlyPaise, String description, boolean active) {}
