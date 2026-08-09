package com.nammamedmate.payment.application.port.out;

import java.util.Optional;

/** KPI chip cache — TTL ≤ 60s (EPIC-012 STORY-009 AC-008). */
public interface FinanceOverviewCachePort {

  String KPI_CACHE_KEY = "finance:overview:kpi";

  Optional<String> getKpiJson();

  void putKpiJson(String json);
}
