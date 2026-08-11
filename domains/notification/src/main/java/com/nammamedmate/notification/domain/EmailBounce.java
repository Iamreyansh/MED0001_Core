package com.nammamedmate.notification.domain;

import java.time.Instant;
import java.util.UUID;

public record EmailBounce(
    UUID id,
    String email,
    EmailBounceType bounceType,
    String bounceReason,
    boolean unsubscribed,
    Instant recordedAt) {}
