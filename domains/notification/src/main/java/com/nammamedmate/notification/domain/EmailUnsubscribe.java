package com.nammamedmate.notification.domain;

import java.time.Instant;
import java.util.UUID;

public record EmailUnsubscribe(
    UUID id, String email, EmailUnsubscribeSource source, Instant unsubscribedAt, boolean active) {}
