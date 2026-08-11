package com.nammamedmate.medicine_schedule.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.medicine_schedule.application.RefillAlertService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ScheduleShareControllerTest {

  @Mock private RefillAlertService service;

  @Test
  void get_delegates() {
    ScheduleShareController controller = new ScheduleShareController(service);
    when(service.viewSharedSchedule("tok")).thenReturn(Map.of("member_name", "Priya"));
    ApiResponse<Map<String, Object>> response = controller.get("tok");
    assertThat(response.data()).containsEntry("member_name", "Priya");
  }
}
