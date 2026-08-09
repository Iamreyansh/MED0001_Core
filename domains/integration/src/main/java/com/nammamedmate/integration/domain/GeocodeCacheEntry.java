package com.nammamedmate.integration.domain;

import java.time.Instant;

public record GeocodeCacheEntry(
    String cacheKey,
    double lat,
    double lng,
    String formattedAddress,
    String placeId,
    Instant cachedAt,
    Instant expiresAt) {}
