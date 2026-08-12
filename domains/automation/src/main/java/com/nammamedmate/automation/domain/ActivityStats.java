package com.nammamedmate.automation.domain;

import java.time.Instant;

public record ActivityStats(
    long actionsLast24h,
    long actionsThisWeek,
    long manualActionsSavedEstimate,
    long exceptionsRaisedToday,
    long pendingApprovalsCount,
    Instant lastActionAt) {}
