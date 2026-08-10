package com.nammamedmate.marketing.domain;

import java.time.Instant;
import java.util.UUID;

public record CampaignTimelineEvent(
    UUID id, UUID campaignId, String event, Instant at, String actor) {}
