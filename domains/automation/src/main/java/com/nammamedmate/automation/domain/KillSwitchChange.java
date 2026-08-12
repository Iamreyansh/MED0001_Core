package com.nammamedmate.automation.domain;

import java.time.Instant;
import java.util.UUID;

public record KillSwitchChange(
    KillSwitchAction action,
    UUID changedBy,
    String changedByLabel,
    Instant changedAt,
    String reason) {}
