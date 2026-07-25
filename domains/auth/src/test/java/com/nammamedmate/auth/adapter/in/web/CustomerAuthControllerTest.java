package com.nammamedmate.auth.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.auth.adapter.in.web.dto.DeviceInfoRequest;
import com.nammamedmate.auth.adapter.in.web.dto.SendOtpRequest;
import com.nammamedmate.auth.adapter.in.web.dto.SendOtpResponse;
import com.nammamedmate.auth.adapter.in.web.dto.VerifyOtpRequest;
import com.nammamedmate.auth.adapter.in.web.dto.VerifyOtpResponse;
import com.nammamedmate.auth.application.SendOtpCommand;
import com.nammamedmate.auth.application.SendOtpResult;
import com.nammamedmate.auth.application.SendOtpService;
import com.nammamedmate.auth.application.VerifyOtpResult;
import com.nammamedmate.auth.application.VerifyOtpService;
import com.nammamedmate.auth.application.port.out.CustomerRecord;
import com.nammamedmate.kernel.api.ApiResponse;
import com.nammamedmate.kernel.id.Ids;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CustomerAuthControllerTest {

  private final SendOtpService sendOtpService = mock(SendOtpService.class);
  private final VerifyOtpService verifyOtpService = mock(VerifyOtpService.class);
  private final CustomerAuthController controller =
      new CustomerAuthController(sendOtpService, verifyOtpService, new ObjectMapper());

  @Test
  void sendOtpMapsRequestAndUsesForwardedIp() {
    Instant now = Instant.parse("2026-07-25T08:00:00Z");
    when(sendOtpService.send(any()))
        .thenReturn(
            new SendOtpResult(
                Ids.newId(), "+919876543210", now.plusSeconds(600), now.plusSeconds(60), 3));
    HttpServletRequest http = mock(HttpServletRequest.class);
    when(http.getHeader("X-Forwarded-For")).thenReturn("10.0.0.1, 10.0.0.2");

    ApiResponse<SendOtpResponse> response =
        controller.sendOtp(
            new SendOtpRequest("+919876543210", new DeviceInfoRequest("android", "d1", "1.0.0")),
            http);

    assertThat(response.success()).isTrue();
    assertThat(response.data().phone()).isEqualTo("+919876543210");
    ArgumentCaptor<SendOtpCommand> captor = ArgumentCaptor.forClass(SendOtpCommand.class);
    verify(sendOtpService).send(captor.capture());
    assertThat(captor.getValue().clientIp()).isEqualTo("10.0.0.1");
    assertThat(captor.getValue().deviceInfoJson()).contains("android");
  }

  @Test
  void sendOtpWithoutDeviceInfoAndRemoteAddr() {
    Instant now = Instant.parse("2026-07-25T08:00:00Z");
    when(sendOtpService.send(any()))
        .thenReturn(
            new SendOtpResult(
                Ids.newId(), "+919876543210", now.plusSeconds(600), now.plusSeconds(60), 3));
    HttpServletRequest http = mock(HttpServletRequest.class);
    when(http.getHeader("X-Forwarded-For")).thenReturn(null);
    when(http.getRemoteAddr()).thenReturn("127.0.0.1");

    controller.sendOtp(new SendOtpRequest("+919876543210", null), http);

    ArgumentCaptor<SendOtpCommand> captor = ArgumentCaptor.forClass(SendOtpCommand.class);
    verify(sendOtpService).send(captor.capture());
    assertThat(captor.getValue().deviceInfoJson()).isNull();
    assertThat(captor.getValue().clientIp()).isEqualTo("127.0.0.1");
  }

  @Test
  void sendOtpDefaultsIpWhenForwardedBlankAndRemoteNull() {
    Instant now = Instant.parse("2026-07-25T08:00:00Z");
    when(sendOtpService.send(any()))
        .thenReturn(
            new SendOtpResult(
                Ids.newId(), "+919876543210", now.plusSeconds(600), now.plusSeconds(60), 3));
    HttpServletRequest http = mock(HttpServletRequest.class);
    when(http.getHeader("X-Forwarded-For")).thenReturn("   ");
    when(http.getRemoteAddr()).thenReturn(null);

    controller.sendOtp(new SendOtpRequest("+919876543210", null), http);

    ArgumentCaptor<SendOtpCommand> captor = ArgumentCaptor.forClass(SendOtpCommand.class);
    verify(sendOtpService).send(captor.capture());
    assertThat(captor.getValue().clientIp()).isEqualTo("0.0.0.0");
  }

  @Test
  void sendOtpDefaultsIpWhenRemoteBlank() {
    Instant now = Instant.parse("2026-07-25T08:00:00Z");
    when(sendOtpService.send(any()))
        .thenReturn(
            new SendOtpResult(
                Ids.newId(), "+919876543210", now.plusSeconds(600), now.plusSeconds(60), 3));
    HttpServletRequest http = mock(HttpServletRequest.class);
    when(http.getRemoteAddr()).thenReturn(" ");

    controller.sendOtp(new SendOtpRequest("+919876543210", null), http);

    ArgumentCaptor<SendOtpCommand> captor = ArgumentCaptor.forClass(SendOtpCommand.class);
    verify(sendOtpService).send(captor.capture());
    assertThat(captor.getValue().clientIp()).isEqualTo("0.0.0.0");
  }

  @Test
  void sendOtpIgnoresDeviceInfoWhenSerializationFails() throws Exception {
    Instant now = Instant.parse("2026-07-25T08:00:00Z");
    ObjectMapper failing = mock(ObjectMapper.class);
    when(failing.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") {});
    CustomerAuthController broken =
        new CustomerAuthController(sendOtpService, verifyOtpService, failing);
    when(sendOtpService.send(any()))
        .thenReturn(
            new SendOtpResult(
                Ids.newId(), "+919876543210", now.plusSeconds(600), now.plusSeconds(60), 3));
    HttpServletRequest http = mock(HttpServletRequest.class);
    when(http.getRemoteAddr()).thenReturn("1.1.1.1");

    broken.sendOtp(
        new SendOtpRequest("+919876543210", new DeviceInfoRequest("ios", "d", "1.0.0")), http);

    ArgumentCaptor<SendOtpCommand> captor = ArgumentCaptor.forClass(SendOtpCommand.class);
    verify(sendOtpService).send(captor.capture());
    assertThat(captor.getValue().deviceInfoJson()).isNull();
  }

  @Test
  void verifyOtpMapsResponse() {
    CustomerRecord customer =
        new CustomerRecord(
            Ids.newId(),
            "+919876543210",
            List.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            0L,
            0,
            Instant.parse("2026-07-25T08:00:00Z"));
    when(verifyOtpService.verify(any()))
        .thenReturn(
            new VerifyOtpResult("access", "refresh", "Bearer", 900, 2592000, true, customer));
    HttpServletRequest http = mock(HttpServletRequest.class);
    when(http.getRemoteAddr()).thenReturn("1.1.1.1");
    when(http.getHeader("User-Agent")).thenReturn("test-agent");

    ApiResponse<VerifyOtpResponse> response =
        controller.verifyOtp(
            new VerifyOtpRequest(Ids.newId(), "+919876543210", "123456", "tok"), http);

    assertThat(response.success()).isTrue();
    assertThat(response.data().accessToken()).isEqualTo("access");
    assertThat(response.data().isNewUser()).isTrue();
    assertThat(response.data().customer().walletBalance().intValue()).isZero();
  }
}
