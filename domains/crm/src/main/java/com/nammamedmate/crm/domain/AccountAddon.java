package com.nammamedmate.crm.domain;

import java.time.Instant;
import java.util.UUID;

public record AccountAddon(
    UUID accountId, UUID addonId, Instant effectiveFrom, Instant detachedAt) {}
