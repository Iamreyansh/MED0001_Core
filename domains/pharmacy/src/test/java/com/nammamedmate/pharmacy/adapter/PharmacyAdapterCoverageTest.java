package com.nammamedmate.pharmacy.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.pharmacy.adapter.in.web.PharmacyRegistrationController;
import com.nammamedmate.pharmacy.adapter.in.web.PharmacyRegistrationController.AddressRequest;
import com.nammamedmate.pharmacy.adapter.in.web.PharmacyRegistrationController.RegisterRequest;
import com.nammamedmate.pharmacy.adapter.in.web.PharmacyRegistrationController.ResendOtpRequest;
import com.nammamedmate.pharmacy.adapter.in.web.PharmacyRegistrationController.VerifyEmailRequest;
import com.nammamedmate.pharmacy.adapter.out.email.LoggingRegistrationEmailSender;
import com.nammamedmate.pharmacy.adapter.out.persistence.JdbcPharmacyEmailOtpStore;
import com.nammamedmate.pharmacy.adapter.out.persistence.JdbcPharmacyOwnerAccountStore;
import com.nammamedmate.pharmacy.adapter.out.persistence.JdbcPharmacyRegistrationStore;
import com.nammamedmate.pharmacy.adapter.out.persistence.JdbcPharmacySessionStore;
import com.nammamedmate.pharmacy.adapter.out.persistence.JdbcPincodeReferenceStore;
import com.nammamedmate.pharmacy.adapter.out.persistence.JdbcRegistrationAuditStore;
import com.nammamedmate.pharmacy.application.PharmacyRegistrationService;
import com.nammamedmate.pharmacy.application.port.out.PharmacyEmailOtpStore.OtpRecord;
import com.nammamedmate.pharmacy.application.port.out.PharmacyOwnerAccountStore.OwnerCreate;
import com.nammamedmate.pharmacy.application.port.out.PharmacyRegistrationStore.PharmacyRecord;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpServletRequest;

class PharmacyAdapterCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-26T12:00:00Z");

  @Test
  void controllerDelegates() {
    PharmacyRegistrationService service = mock(PharmacyRegistrationService.class);
    when(service.register(any(), anyString())).thenReturn(Map.of("status", "PENDING_KYC"));
    when(service.verifyEmail(any(), any(), anyString(), any())).thenReturn(Map.of("ok", true));
    when(service.resendOtp(any(), anyString())).thenReturn(Map.of("resends_remaining", 4));
    when(service.registrationStatus(any(), anyString()))
        .thenReturn(Map.of("status", "PENDING_KYC"));

    PharmacyRegistrationController controller = new PharmacyRegistrationController(service);
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.setRemoteAddr("1.1.1.1");

    RegisterRequest body =
        new RegisterRequest(
            "Owner",
            "Shop",
            "+919876543210",
            "a@nammamedmate.test",
            "Passw0rd!",
            "PHARMACY",
            new AddressRequest("1", "A", "Bengaluru", "Karnataka", "560001", null, null),
            "29AABPP1234F1ZZ",
            "DL",
            null,
            "AABPP1234F");
    assertThat(controller.register(body, req).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(controller.register(null, req).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(controller.verifyEmail(new VerifyEmailRequest("a@x", "123456"), req).success())
        .isTrue();
    assertThat(controller.verifyEmail(null, req).success()).isTrue();
    assertThat(controller.resendOtp(new ResendOtpRequest("a@x"), req).success()).isTrue();
    assertThat(controller.resendOtp(null, req).success()).isTrue();
    MedmatePrincipal p =
        new MedmatePrincipal(
            Ids.newId(), AuthRole.PHARMACY_OWNER, Ids.newId(), TokenScope.FULL, "j");
    assertThat(controller.registrationStatus(p, req).success()).isTrue();
  }

  @Test
  void loggingEmailSenderAndJdbcStores() throws Exception {
    new LoggingRegistrationEmailSender().sendOtp("a@test", "123456");

    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ObjectMapper mapper = new ObjectMapper();
    UUID id = Ids.newId();

    JdbcPharmacyRegistrationStore pharmacies = new JdbcPharmacyRegistrationStore(jdbc, mapper);
    PharmacyRecord record = samplePharmacy(id);
    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(inv -> List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(mockRs(record), 0)));
    when(jdbc.query(anyString(), any(RowMapper.class), eq("a@test"))).thenReturn(List.of());
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(0);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(1);

    pharmacies.insert(record);
    assertThat(pharmacies.findById(id)).isPresent();
    assertThat(pharmacies.findByEmail("a@test")).isEmpty();
    assertThat(pharmacies.existsGstin("x")).isFalse();
    assertThat(pharmacies.existsPan("x")).isFalse();
    assertThat(pharmacies.existsPhone("x")).isFalse();
    assertThat(pharmacies.existsEmail("x")).isFalse();
    assertThat(pharmacies.existsDrugLicence("d", "29")).isTrue();
    pharmacies.markEmailVerified(id, NOW);
    pharmacies.updateStatus(id, "KYC_SUBMITTED", NOW, NOW);
    pharmacies.updateStatus(id, "PENDING_KYC", null, NOW); // null kycSubmittedAt branch

    JdbcPharmacyEmailOtpStore otps = new JdbcPharmacyEmailOtpStore(jdbc);
    OtpRecord otp = new OtpRecord(id, id, "a@test", "hash", 0, 0, NOW, null, null, NOW, NOW);
    otps.insert(otp);
    otps.update(otp);
    when(jdbc.query(anyString(), any(RowMapper.class), eq("a@test")))
        .thenAnswer(
            inv -> {
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getObject("pharmacy_id")).thenReturn(id);
              when(rs.getString("email")).thenReturn("a@test");
              when(rs.getString("otp_hash")).thenReturn("hash");
              when(rs.getInt("attempts")).thenReturn(0);
              when(rs.getInt("resend_count")).thenReturn(0);
              when(rs.getTimestamp("expires_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("verified_at")).thenReturn(null);
              when(rs.getTimestamp("locked_at")).thenReturn(null);
              when(rs.getTimestamp("last_sent_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            });
    assertThat(otps.findLatestByEmail("a@test")).isPresent();

    JdbcPharmacyOwnerAccountStore owners = new JdbcPharmacyOwnerAccountStore(jdbc);
    owners.createOwner(new OwnerCreate(id, "N", "e@t", "+9198", "hash", id, null, NOW));
    when(jdbc.query(anyString(), any(RowMapper.class), anyString())).thenReturn(List.of(id));
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(0);
    assertThat(owners.findStaffIdByEmail("e@t")).contains(id);
    assertThat(owners.emailTakenPlatformWide("e")).isFalse();
    assertThat(owners.phoneTakenPlatformWide("p")).isFalse();

    new JdbcPharmacySessionStore(jdbc).save(id, id, "hash", "1.1.1.1", "ua", NOW, NOW, id);
    new JdbcPharmacySessionStore(jdbc).save(id, id, "hash", null, "ua", NOW, NOW, id);

    JdbcPincodeReferenceStore pins = new JdbcPincodeReferenceStore(jdbc);
    when(jdbc.query(anyString(), any(RowMapper.class), eq("560001")))
        .thenAnswer(
            inv -> {
              ResultSet rs = mock(ResultSet.class);
              when(rs.getString("pincode")).thenReturn("560001");
              when(rs.getString("state_code")).thenReturn("29");
              when(rs.getString("state_name")).thenReturn("Karnataka");
              when(rs.getBoolean("serviceable")).thenReturn(true);
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            });
    assertThat(pins.findServiceable("560001")).isPresent();

    new JdbcRegistrationAuditStore(jdbc).save(id, id, "e", "p", "1.1.1.1", "SUCCESS", null, NOW);
    new JdbcRegistrationAuditStore(jdbc).save(id, null, "e", "p", null, "FAILURE", "X", NOW);
  }

  private static PharmacyRecord samplePharmacy(UUID id) {
    return new PharmacyRecord(
        id,
        "Shop",
        "Shop",
        "Owner",
        "+9198",
        "a@test",
        "hash",
        "PHARMACY",
        Map.of("city", "Bengaluru"),
        "PENDING_KYC",
        "FREE",
        null,
        "29AABPP1234F1ZZ",
        "DL",
        "29",
        null,
        "AABPP1234F",
        new BigDecimal("8.00"),
        null,
        false,
        false,
        true,
        "Bengaluru",
        "FREE",
        NOW,
        NOW,
        null);
  }

  private static ResultSet mockRs(PharmacyRecord r) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(r.id());
    when(rs.getString("name")).thenReturn(r.name());
    when(rs.getString("business_name")).thenReturn(r.businessName());
    when(rs.getString("owner_name")).thenReturn(r.ownerName());
    when(rs.getString("phone")).thenReturn(r.phone());
    when(rs.getString("email")).thenReturn(r.email());
    when(rs.getString("password_hash")).thenReturn(r.passwordHash());
    when(rs.getString("business_type")).thenReturn(r.businessType());
    when(rs.getString("address")).thenReturn("{\"city\":\"Bengaluru\"}");
    when(rs.getString("status")).thenReturn(r.status());
    when(rs.getString("plan")).thenReturn(r.plan());
    when(rs.getTimestamp("plan_expires_at")).thenReturn(null);
    when(rs.getString("gstin")).thenReturn(r.gstin());
    when(rs.getString("drug_licence_number")).thenReturn(r.drugLicenceNumber());
    when(rs.getString("licence_state_code")).thenReturn(r.licenceStateCode());
    when(rs.getString("fssai_number")).thenReturn(null);
    when(rs.getString("pan_number")).thenReturn(r.panNumber());
    when(rs.getBigDecimal("commission_pct")).thenReturn(r.commissionPct());
    when(rs.getObject("zone_id")).thenReturn(null);
    when(rs.getBoolean("is_online")).thenReturn(false);
    when(rs.getBoolean("email_verified")).thenReturn(false);
    when(rs.getBoolean("can_reapply")).thenReturn(true);
    when(rs.getString("city")).thenReturn(r.city());
    when(rs.getString("subscription_plan")).thenReturn(r.subscriptionPlan());
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(NOW));
    when(rs.getTimestamp("kyc_submitted_at")).thenReturn(null);
    return rs;
  }
}
