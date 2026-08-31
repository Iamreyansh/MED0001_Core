package com.nammamedmate.auth.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nammamedmate.auth.adapter.in.web.dto.CompletePharmacyInviteRequest;
import com.nammamedmate.auth.adapter.in.web.dto.CompletePharmacyResetRequest;
import com.nammamedmate.auth.adapter.in.web.dto.ForgotPharmacyPasswordRequest;
import com.nammamedmate.auth.adapter.in.web.dto.PharmacyLoginRequest;
import com.nammamedmate.auth.adapter.in.web.dto.PosPinLoginRequest;
import com.nammamedmate.auth.adapter.in.web.dto.SwitchPharmacyRequest;
import com.nammamedmate.auth.application.PharmacyLoginResult;
import com.nammamedmate.auth.application.PharmacyLoginService;
import com.nammamedmate.auth.application.PharmacyStaffService;
import com.nammamedmate.auth.application.PosPinLoginResult;
import com.nammamedmate.auth.application.PosPinLoginService;
import com.nammamedmate.auth.application.SwitchPharmacyResult;
import com.nammamedmate.auth.application.SwitchPharmacyService;
import com.nammamedmate.auth.application.port.out.PharmacyAssignmentRecord;
import com.nammamedmate.auth.application.port.out.PharmacyRecord;
import com.nammamedmate.auth.application.port.out.PharmacyStaffRecord;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PharmacyAuthControllerTest {

  private static final Instant NOW = Instant.parse("2026-07-25T08:00:00Z");

  private final PharmacyLoginService loginService = mock(PharmacyLoginService.class);
  private final SwitchPharmacyService switchService = mock(SwitchPharmacyService.class);
  private final PosPinLoginService posPinService = mock(PosPinLoginService.class);
  private final PharmacyStaffService staffService = mock(PharmacyStaffService.class);
  private final PharmacyAuthController controller =
      new PharmacyAuthController(loginService, switchService, posPinService, staffService);

  @Test
  void loginMapsResultToResponse() {
    UUID pharmacyId = Ids.newId();
    UUID staffId = Ids.newId();
    PharmacyRecord pharmacy =
        new PharmacyRecord(pharmacyId, "Test Pharmacy", null, "Bengaluru", "GROWTH");
    PharmacyStaffRecord staff =
        new PharmacyStaffRecord(
            staffId, "Priya", "p@x.com", null, "hash", null, "ACTIVE", 0, null, null, NOW, null,
            NOW, NOW);
    PharmacyAssignmentRecord assignment =
        new PharmacyAssignmentRecord(
            Ids.newId(), staffId, pharmacyId, "owner", true, NOW, null, "Test Pharmacy");

    when(loginService.login(any(), any(), any(), any(), any()))
        .thenReturn(
            new PharmacyLoginResult(
                "access", "refresh", 900L, 604800L, pharmacy, "owner", staff, List.of(assignment)));

    HttpServletRequest http = mock(HttpServletRequest.class);
    when(http.getRemoteAddr()).thenReturn("1.1.1.1");

    var response = controller.login(new PharmacyLoginRequest("p@x.com", "Passw0rd!", null), http);

    assertThat(response.success()).isTrue();
    assertThat(response.data().accessToken()).isEqualTo("access");
    assertThat(response.data().tokenType()).isEqualTo("Bearer");
    assertThat(response.data().pharmacies()).hasSize(1);
    assertThat(response.data().staff().mfaEnabled()).isFalse();
  }

  @Test
  void loginUsesRemoteAddr() {
    UUID pharmacyId = Ids.newId();
    UUID staffId = Ids.newId();
    PharmacyRecord pharmacy = new PharmacyRecord(pharmacyId, "P", null, "C", "FREE");
    PharmacyStaffRecord staff =
        new PharmacyStaffRecord(
            staffId, "S", "a@b.com", null, "h", null, "ACTIVE", 0, null, null, null, null, NOW,
            NOW);
    when(loginService.login(any(), any(), any(), eq("10.0.0.1"), any()))
        .thenReturn(
            new PharmacyLoginResult(
                "tok", "ref", 900L, 604800L, pharmacy, "pharmacist", staff, List.of()));

    HttpServletRequest http = mock(HttpServletRequest.class);
    when(http.getHeader("X-Forwarded-For")).thenReturn("1.2.3.4, 5.6.7.8");
    when(http.getRemoteAddr()).thenReturn("10.0.0.1");

    controller.login(new PharmacyLoginRequest("a@b.com", "Passw0rd!", null), http);
  }

  @Test
  void clientIpFallbacksWhenRemoteIsNull() {
    UUID pharmacyId = Ids.newId();
    UUID staffId = Ids.newId();
    PharmacyRecord pharmacy = new PharmacyRecord(pharmacyId, "P", null, "C", "FREE");
    PharmacyStaffRecord staff =
        new PharmacyStaffRecord(
            staffId, "S", "a@b.com", null, "h", null, "ACTIVE", 0, null, null, null, null, NOW,
            NOW);
    when(loginService.login(any(), any(), any(), eq("0.0.0.0"), any()))
        .thenReturn(
            new PharmacyLoginResult(
                "tok", "ref", 900L, 604800L, pharmacy, "pharmacist", staff, List.of()));

    HttpServletRequest http = mock(HttpServletRequest.class);
    when(http.getRemoteAddr()).thenReturn(null);

    controller.login(new PharmacyLoginRequest("a@b.com", "Passw0rd!", null), http);
  }

  @Test
  void clientIpFallbacksWhenRemoteIsBlank() {
    UUID pharmacyId = Ids.newId();
    UUID staffId = Ids.newId();
    PharmacyRecord pharmacy = new PharmacyRecord(pharmacyId, "P", null, "C", "FREE");
    PharmacyStaffRecord staff =
        new PharmacyStaffRecord(
            staffId, "S", "a@b.com", null, "h", null, "ACTIVE", 0, null, null, null, null, NOW,
            NOW);
    when(loginService.login(any(), any(), any(), eq("0.0.0.0"), any()))
        .thenReturn(
            new PharmacyLoginResult(
                "tok", "ref", 900L, 604800L, pharmacy, "pharmacist", staff, List.of()));

    HttpServletRequest http = mock(HttpServletRequest.class);
    when(http.getRemoteAddr()).thenReturn("  ");

    controller.login(new PharmacyLoginRequest("a@b.com", "Passw0rd!", null), http);
  }

  @Test
  void switchPharmacyHappyPath() {
    UUID staffId = Ids.newId();
    UUID pharmacyId = Ids.newId();
    MedmatePrincipal principal =
        new MedmatePrincipal(staffId, AuthRole.PHARMACY_OWNER, Ids.newId(), TokenScope.FULL, "jti");
    PharmacyRecord pharmacy = new PharmacyRecord(pharmacyId, "P2", null, "Bengaluru", "STARTER");

    when(switchService.switchPharmacy(staffId, pharmacyId))
        .thenReturn(new SwitchPharmacyResult("new-access", 900L, pharmacy, "pharmacist"));

    var response = controller.switchPharmacy(new SwitchPharmacyRequest(pharmacyId), principal);

    assertThat(response.success()).isTrue();
    assertThat(response.data().accessToken()).isEqualTo("new-access");
    assertThat(response.data().roleInPharmacy()).isEqualTo("pharmacist");
  }

  @Test
  void switchPharmacyRejectsNullPrincipal() {
    assertThatThrownBy(
            () -> controller.switchPharmacy(new SwitchPharmacyRequest(Ids.newId()), null))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");
  }

  @Test
  void switchPharmacyRejectsPosToken() {
    UUID staffId = Ids.newId();
    MedmatePrincipal posP =
        new MedmatePrincipal(staffId, AuthRole.PHARMACY_STAFF, Ids.newId(), TokenScope.POS, "jti");
    assertThatThrownBy(
            () -> controller.switchPharmacy(new SwitchPharmacyRequest(Ids.newId()), posP))
        .isInstanceOf(AppException.class)
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("POS_TOKEN_RESTRICTED");
  }

  @Test
  void posPinLoginMapsResponse() {
    UUID pharmacyId = Ids.newId();
    UUID staffId = Ids.newId();
    PharmacyStaffRecord staff =
        new PharmacyStaffRecord(
            staffId,
            "Kavya",
            null,
            "+919876543210",
            "hash",
            "pinhash",
            "ACTIVE",
            0,
            null,
            null,
            null,
            null,
            NOW,
            NOW);
    PharmacyRecord pharmacy =
        new PharmacyRecord(pharmacyId, "Sri Rama", null, "Bengaluru", "GROWTH");

    when(posPinService.login(eq(pharmacyId), eq(staffId), eq("1234"), any(), any()))
        .thenReturn(new PosPinLoginResult("pos-access", 14400L, staff, "cashier", pharmacy));

    HttpServletRequest http = mock(HttpServletRequest.class);
    when(http.getRemoteAddr()).thenReturn("1.1.1.1");

    var response =
        controller.posPinLogin(new PosPinLoginRequest(pharmacyId, staffId, "1234"), http);

    assertThat(response.success()).isTrue();
    assertThat(response.data().accessToken()).isEqualTo("pos-access");
    assertThat(response.data().tokenScope()).isEqualTo("pos");
    assertThat(response.data().accessTokenExpiresIn()).isEqualTo(14400L);
    assertThat(response.data().staff().name()).isEqualTo("Kavya");
  }

  @Test
  void completeInviteDelegates() {
    when(staffService.completeInvite("tok", "Passw0rd!")).thenReturn(Map.of("status", "ACTIVE"));
    assertThat(
            controller.completeInvite(new CompletePharmacyInviteRequest("tok", "Passw0rd!")).data())
        .containsEntry("status", "ACTIVE");
    when(staffService.completeInvite(null, null)).thenReturn(Map.of("ok", false));
    assertThat(controller.completeInvite(null).data()).containsEntry("ok", false);
  }

  @Test
  void forgotAndCompleteResetDelegate() {
    when(staffService.requestPasswordReset("ada@x.com")).thenReturn(Map.of("requested", true));
    assertThat(controller.forgotPassword(new ForgotPharmacyPasswordRequest("ada@x.com")).data())
        .containsEntry("requested", true);
    when(staffService.requestPasswordReset(null)).thenReturn(Map.of("requested", true));
    assertThat(controller.forgotPassword(null).data()).containsEntry("requested", true);
    when(staffService.completePasswordReset("tok", "Passw0rd!"))
        .thenReturn(Map.of("status", "ACTIVE"));
    assertThat(
            controller.completeReset(new CompletePharmacyResetRequest("tok", "Passw0rd!")).data())
        .containsEntry("status", "ACTIVE");
    when(staffService.completePasswordReset(null, null)).thenReturn(Map.of("ok", false));
    assertThat(controller.completeReset(null).data()).containsEntry("ok", false);
  }
}
