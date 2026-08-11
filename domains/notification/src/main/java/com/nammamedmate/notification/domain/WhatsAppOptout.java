package com.nammamedmate.notification.domain;

import java.time.Instant;
import java.util.UUID;

public record WhatsAppOptout(
    UUID id, String phone, WhatsAppOptoutSource source, Instant optedOutAt, boolean active) {}
