package com.nammamedmate.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.kernel.api.ApiResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HealthControllerTest {

  @Test
  void returnsUp() {
    ApiResponse<Map<String, String>> response = new HealthController().health();
    assertThat(response.success()).isTrue();
    assertThat(response.data()).containsEntry("status", "UP");
  }
}
