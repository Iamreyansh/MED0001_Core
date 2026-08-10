package com.nammamedmate.support.application;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SupportSchedulersTest {

  @Mock TicketService tickets;
  @Mock SlaService sla;
  @Mock DisputeService disputes;

  @Test
  void csatAndSlaSchedulersDelegate() {
    when(tickets.dispatchDueCsatSurveys(100)).thenReturn(2);
    when(sla.processSlaBreaches(100)).thenReturn(1);
    new CsatSurveyScheduler(tickets).dispatchDueSurveys();
    new SlaBreachScheduler(sla).escalateBreaches();
    verify(tickets).dispatchDueCsatSurveys(100);
    verify(sla).processSlaBreaches(100);
  }

  @Test
  void disputeSlaSchedulerDelegates() {
    when(disputes.processSlaBreaches(100)).thenReturn(3);
    new DisputeSlaScheduler(disputes).escalateBreaches();
    verify(disputes).processSlaBreaches(100);
  }

  @Test
  void agentWeeklySchedulerDelegates() {
    AgentService agents = org.mockito.Mockito.mock(AgentService.class);
    when(agents.generateWeeklyPerformanceSnapshots()).thenReturn(4);
    new AgentWeeklyPerformanceScheduler(agents).snapshotPriorWeek();
    verify(agents).generateWeeklyPerformanceSnapshots();
  }
}
