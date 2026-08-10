package com.nammamedmate.support.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** AC-010: weekly performance snapshot + ops email every Monday 08:00 Asia/Kolkata. */
@Component
@ConditionalOnProperty(
    name = "medmate.support.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class AgentWeeklyPerformanceScheduler {

  private final AgentService agents;

  public AgentWeeklyPerformanceScheduler(AgentService agents) {
    this.agents = agents;
  }

  @Scheduled(cron = "0 0 8 * * MON", zone = "Asia/Kolkata")
  public void snapshotPriorWeek() {
    agents.generateWeeklyPerformanceSnapshots();
  }
}
