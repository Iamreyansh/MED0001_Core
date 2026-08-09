package com.nammamedmate.crm.domain;

import java.time.Instant;
import java.util.UUID;

public record PlanSubscriber(UUID accountId, String pharmacyName, Instant since) {}
