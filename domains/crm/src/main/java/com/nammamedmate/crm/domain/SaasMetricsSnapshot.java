package com.nammamedmate.crm.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Cached monthly SaaS metrics (paise / percentages). */
public record SaasMetricsSnapshot(
    LocalDate metricMonth,
    long mrrPaise,
    long arrPaise,
    long arpaPaise,
    BigDecimal nrrPct,
    BigDecimal grrPct,
    BigDecimal quickRatio,
    BigDecimal magicNumber,
    long ltvPaise,
    long cacPaise,
    BigDecimal logoChurnPct,
    long startMrrPaise,
    long newMrrPaise,
    long expansionMrrPaise,
    long contractionMrrPaise,
    long churnMrrPaise,
    long netNewMrrPaise,
    int newLogos,
    int churnedLogos,
    int expansionAccounts,
    int contractionAccounts,
    Instant computedAt) {}
