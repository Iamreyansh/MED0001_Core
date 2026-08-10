package com.nammamedmate.support.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    name = "medmate.support.jobs.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class CsatSurveyScheduler {

  private final TicketService tickets;

  public CsatSurveyScheduler(TicketService tickets) {
    this.tickets = tickets;
  }

  @Scheduled(fixedDelayString = "${medmate.support.csat.poll-ms:60000}")
  public void dispatchDueSurveys() {
    tickets.dispatchDueCsatSurveys(100);
  }
}
