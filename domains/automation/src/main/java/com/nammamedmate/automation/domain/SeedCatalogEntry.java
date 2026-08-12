package com.nammamedmate.automation.domain;

import java.time.Instant;
import java.util.UUID;

public record SeedCatalogEntry(
    String seedRuleKey,
    UUID ruleId,
    UUID workflowId,
    int displayOrder,
    String expectedImpact,
    String edgeCases,
    Instant initializedAt) {}
