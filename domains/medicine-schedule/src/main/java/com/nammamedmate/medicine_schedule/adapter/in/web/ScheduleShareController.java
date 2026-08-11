package com.nammamedmate.medicine_schedule.adapter.in.web;

import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.medicine_schedule.application.RefillAlertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/schedule/share")
@Tag(name = "Shared schedule")
public class ScheduleShareController {

  private final RefillAlertService service;

  public ScheduleShareController(RefillAlertService service) {
    this.service = service;
  }

  @GetMapping("/{token}")
  @Operation(summary = "View shared schedule (public, no auth)")
  public ApiResponse<Map<String, Object>> get(@PathVariable("token") String token) {
    return ApiResponse.ok(service.viewSharedSchedule(token));
  }
}
