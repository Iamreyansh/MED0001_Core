package com.nammamedmate.teleconsult.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class ConsultsTodayResetSchedulerTest {

  @Test
  void resetsWhenRowsUpdated() {
    TeleconsultDoctorService service = mock(TeleconsultDoctorService.class);
    when(service.resetConsultsToday()).thenReturn(3);
    new ConsultsTodayResetScheduler(service).resetConsultsToday();
    verify(service).resetConsultsToday();
  }

  @Test
  void silentWhenZero() {
    TeleconsultDoctorService service = mock(TeleconsultDoctorService.class);
    when(service.resetConsultsToday()).thenReturn(0);
    new ConsultsTodayResetScheduler(service).resetConsultsToday();
    verify(service).resetConsultsToday();
  }
}
