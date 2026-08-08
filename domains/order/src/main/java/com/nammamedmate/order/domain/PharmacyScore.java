package com.nammamedmate.order.domain;

import java.util.UUID;

/** Ephemeral composite score for smart pharmacy selection. */
public record PharmacyScore(
    UUID pharmacyId,
    double distanceKm,
    double distanceScore,
    double fillRate7d,
    double fillRateScore,
    double pharmacyRating,
    double ratingScore,
    int deliveryEtaMinutes,
    double etaScore,
    double totalScore) {}
