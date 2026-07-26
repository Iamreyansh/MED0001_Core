package com.nammamedmate.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.pharmacy.adapter.in.web.PharmacyRegistrationController;
import com.nammamedmate.pharmacy.adapter.out.email.LoggingRegistrationEmailSender;
import com.nammamedmate.pharmacy.adapter.out.persistence.JdbcPharmacyEmailOtpStore;
import com.nammamedmate.pharmacy.adapter.out.persistence.JdbcPharmacyOwnerAccountStore;
import com.nammamedmate.pharmacy.adapter.out.persistence.JdbcPharmacyRegistrationStore;
import com.nammamedmate.pharmacy.adapter.out.persistence.JdbcPharmacySessionStore;
import com.nammamedmate.pharmacy.application.PharmacyRegistrationService;
import com.nammamedmate.pharmacy.application.port.out.PharmacyOwnerAccountStore.OwnerCreate;
import com.nammamedmate.pharmacy.application.port.out.PharmacyRegistrationStore.PharmacyRecord;
import com.nammamedmate.pharmacy.domain.BusinessTypes;
import com.nammamedmate.pharmacy.domain.Gstin;
import com.nammamedmate.pharmacy.domain.IndianPhone;
import com.nammamedmate.pharmacy.domain.IndianStates;
import com.nammamedmate.pharmacy.domain.MagicRegistrationOtp;
import com.nammamedmate.pharmacy.domain.Pan;
import com.nammamedmate.pharmacy.domain.PharmacyPassword;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpServletRequest;

class DomainAndJdbcExtraCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-26T12:00:00Z");

  @Test
  void domainNullAndEdgeBranches() {
    assertThatThrownBy(() -> IndianStates.requireValid(null)).hasMessage("MISSING_REQUIRED_FIELD");
    assertThatThrownBy(() -> IndianStates.requireValid("  ")).hasMessage("MISSING_REQUIRED_FIELD");
    assertThatThrownBy(() -> Pan.requireValid(null)).hasMessage("INVALID_PAN");
    assertThatThrownBy(() -> BusinessTypes.requireValid(null)).hasMessage("MISSING_REQUIRED_FIELD");
    assertThatThrownBy(() -> BusinessTypes.requireValid("")).hasMessage("MISSING_REQUIRED_FIELD");
    assertThatThrownBy(() -> IndianPhone.requireValid(null)).hasMessage("INVALID_PHONE");
    assertThatThrownBy(() -> PharmacyPassword.requireValid(null))
        .hasMessage("INVALID_PASSWORD_STRENGTH");
    assertThatThrownBy(() -> PharmacyPassword.requireValid("Passw0rd"))
        .hasMessage("INVALID_PASSWORD_STRENGTH");
    assertThatThrownBy(() -> PharmacyPassword.requireValid("password!"))
        .hasMessage("INVALID_PASSWORD_STRENGTH");
    assertThatThrownBy(() -> PharmacyPassword.requireValid("PASSWORD!"))
        .hasMessage("INVALID_PASSWORD_STRENGTH");
    assertThat(MagicRegistrationOtp.isMagicEmail(null)).isFalse();
    assertThatThrownBy(() -> Gstin.requireValid("29AABPP1234FXZZ")).hasMessage("INVALID_GSTIN");
    assertThatThrownBy(() -> Gstin.requireValid("29AAAAA0000A1ZA")).hasMessage("INVALID_GSTIN");
    assertThatThrownBy(() -> Gstin.requireValid("29AAAXP1234F1ZZ")).hasMessage("INVALID_GSTIN");
    assertThatThrownBy(() -> Gstin.requireValid("29AABPP1234F1Z@")).hasMessage("INVALID_GSTIN");
    assertThatThrownBy(() -> Pan.requireValid("AAAPA123")).hasMessage("INVALID_PAN");
  }

  @Test
  void jdbcMapperNullBranchesAndControllerRemoteAddr() throws Exception {
    new LoggingRegistrationEmailSender().sendOtp("a@test", null);

    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ObjectMapper mapper = new ObjectMapper();
    ObjectMapper badMapper = mock(ObjectMapper.class);
    when(badMapper.writeValueAsString(any())).thenThrow(new RuntimeException("boom"));
    when(badMapper.readValue(anyString(), any(com.fasterxml.jackson.core.type.TypeReference.class)))
        .thenThrow(new RuntimeException("boom"));

    UUID id = Ids.newId();
    JdbcPharmacyRegistrationStore store = new JdbcPharmacyRegistrationStore(jdbc, mapper);
    JdbcPharmacyRegistrationStore badStore = new JdbcPharmacyRegistrationStore(jdbc, badMapper);
    assertThatThrownBy(
            () ->
                badStore.insert(
                    new PharmacyRecord(
                        id,
                        "N",
                        "N",
                        "O",
                        "p",
                        "e",
                        "h",
                        "PHARMACY",
                        Map.of(),
                        "PENDING_KYC",
                        "FREE",
                        null,
                        "g",
                        "d",
                        "29",
                        null,
                        "pan",
                        new BigDecimal("8.00"),
                        null,
                        false,
                        false,
                        true,
                        "C",
                        "FREE",
                        NOW,
                        NOW)))
        .isInstanceOf(IllegalStateException.class);
    // writeJson null-address branch + insert non-null planExpiresAt
    store.insert(
        new PharmacyRecord(
            id,
            "N",
            "N",
            "O",
            "p",
            "e",
            "h",
            "PHARMACY",
            null,
            "PENDING_KYC",
            "FREE",
            NOW.plusSeconds(3600),
            "g",
            "d",
            "29",
            null,
            "pan",
            new BigDecimal("8.00"),
            null,
            false,
            false,
            true,
            "C",
            "FREE",
            NOW,
            NOW));

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getString("name")).thenReturn("N");
              when(rs.getString("business_name")).thenReturn(null);
              when(rs.getString("owner_name")).thenReturn(null);
              when(rs.getString("phone")).thenReturn(null);
              when(rs.getString("email")).thenReturn(null);
              when(rs.getString("password_hash")).thenReturn(null);
              when(rs.getString("business_type")).thenReturn(null);
              when(rs.getString("address")).thenReturn(null);
              when(rs.getString("status")).thenReturn("ACTIVE");
              when(rs.getString("plan")).thenReturn("FREE");
              when(rs.getTimestamp("plan_expires_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getString("gstin")).thenReturn(null);
              when(rs.getString("drug_licence_number")).thenReturn(null);
              when(rs.getString("licence_state_code")).thenReturn(null);
              when(rs.getString("fssai_number")).thenReturn(null);
              when(rs.getString("pan_number")).thenReturn(null);
              when(rs.getBigDecimal("commission_pct")).thenReturn(null);
              when(rs.getObject("zone_id")).thenReturn(id);
              when(rs.getBoolean("is_online")).thenReturn(true);
              when(rs.getBoolean("email_verified")).thenReturn(true);
              when(rs.getBoolean("can_reapply")).thenReturn(false);
              when(rs.getString("city")).thenReturn(null);
              when(rs.getString("subscription_plan")).thenReturn("FREE");
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(NOW));
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            });
    assertThat(store.findById(id)).isPresent();
    // blank address json
    when(jdbc.query(
            anyString(),
            any(RowMapper.class),
            eq(UUID.fromString("00000000-0000-0000-0000-000000000098"))))
        .thenAnswer(
            inv -> {
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getString("name")).thenReturn("N");
              when(rs.getString("business_name")).thenReturn("N");
              when(rs.getString("owner_name")).thenReturn("O");
              when(rs.getString("phone")).thenReturn("p");
              when(rs.getString("email")).thenReturn("e");
              when(rs.getString("password_hash")).thenReturn("h");
              when(rs.getString("business_type")).thenReturn("PHARMACY");
              when(rs.getString("address")).thenReturn("   ");
              when(rs.getString("status")).thenReturn("ACTIVE");
              when(rs.getString("plan")).thenReturn("FREE");
              when(rs.getTimestamp("plan_expires_at")).thenReturn(null);
              when(rs.getString("gstin")).thenReturn("g");
              when(rs.getString("drug_licence_number")).thenReturn("d");
              when(rs.getString("licence_state_code")).thenReturn("29");
              when(rs.getString("fssai_number")).thenReturn(null);
              when(rs.getString("pan_number")).thenReturn("pan");
              when(rs.getBigDecimal("commission_pct")).thenReturn(new BigDecimal("8.00"));
              when(rs.getObject("zone_id")).thenReturn(null);
              when(rs.getBoolean("is_online")).thenReturn(false);
              when(rs.getBoolean("email_verified")).thenReturn(false);
              when(rs.getBoolean("can_reapply")).thenReturn(true);
              when(rs.getString("city")).thenReturn("C");
              when(rs.getString("subscription_plan")).thenReturn("FREE");
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(NOW));
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            });
    assertThat(store.findById(UUID.fromString("00000000-0000-0000-0000-000000000098"))).isPresent();

    when(jdbc.query(
            anyString(),
            any(RowMapper.class),
            eq(UUID.fromString("00000000-0000-0000-0000-000000000099"))))
        .thenAnswer(
            inv -> {
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getString("name")).thenReturn("N");
              when(rs.getString("business_name")).thenReturn("N");
              when(rs.getString("owner_name")).thenReturn("O");
              when(rs.getString("phone")).thenReturn("p");
              when(rs.getString("email")).thenReturn("e");
              when(rs.getString("password_hash")).thenReturn("h");
              when(rs.getString("business_type")).thenReturn("PHARMACY");
              when(rs.getString("address")).thenReturn("{bad");
              when(rs.getString("status")).thenReturn("ACTIVE");
              when(rs.getString("plan")).thenReturn("FREE");
              when(rs.getTimestamp("plan_expires_at")).thenReturn(null);
              when(rs.getString("gstin")).thenReturn("g");
              when(rs.getString("drug_licence_number")).thenReturn("d");
              when(rs.getString("licence_state_code")).thenReturn("29");
              when(rs.getString("fssai_number")).thenReturn(null);
              when(rs.getString("pan_number")).thenReturn("pan");
              when(rs.getBigDecimal("commission_pct")).thenReturn(new BigDecimal("8.00"));
              when(rs.getObject("zone_id")).thenReturn(null);
              when(rs.getBoolean("is_online")).thenReturn(false);
              when(rs.getBoolean("email_verified")).thenReturn(false);
              when(rs.getBoolean("can_reapply")).thenReturn(true);
              when(rs.getString("city")).thenReturn("C");
              when(rs.getString("subscription_plan")).thenReturn("FREE");
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(NOW));
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            });
    assertThatThrownBy(
            () -> store.findById(UUID.fromString("00000000-0000-0000-0000-000000000099")))
        .isInstanceOf(IllegalStateException.class);

    // force badMapper read via blank skip then non-blank through find — already covered write

    JdbcPharmacyEmailOtpStore otps = new JdbcPharmacyEmailOtpStore(jdbc);
    var otp =
        new com.nammamedmate.pharmacy.application.port.out.PharmacyEmailOtpStore.OtpRecord(
            id, id, "e@t", "h", 0, 0, NOW, NOW, NOW, NOW, NOW);
    otps.insert(otp);
    otps.update(otp);
    when(jdbc.query(anyString(), any(RowMapper.class), eq("e@t")))
        .thenAnswer(
            inv -> {
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              when(rs.getObject("pharmacy_id")).thenReturn(id);
              when(rs.getString("email")).thenReturn("e@t");
              when(rs.getString("otp_hash")).thenReturn("h");
              when(rs.getInt("attempts")).thenReturn(0);
              when(rs.getInt("resend_count")).thenReturn(0);
              when(rs.getTimestamp("expires_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("verified_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("locked_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("last_sent_at")).thenReturn(Timestamp.from(NOW));
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            });
    assertThat(otps.findLatestByEmail("e@t").orElseThrow().lockedAt()).isEqualTo(NOW);

    JdbcPharmacyOwnerAccountStore owners = new JdbcPharmacyOwnerAccountStore(jdbc);
    owners.createOwner(new OwnerCreate(id, "N", "e", "p", "h", id, id, NOW));
    owners.activateOwner(id, NOW);
    when(jdbc.query(anyString(), any(RowMapper.class), anyString()))
        .thenAnswer(
            inv -> {
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(id);
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            });
    assertThat(owners.findStaffIdByEmail("e@t")).contains(id);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(null);
    assertThat(owners.emailTakenPlatformWide("e")).isFalse();
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(0);
    assertThat(owners.phoneTakenPlatformWide("p")).isFalse();
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(2);
    assertThat(owners.emailTakenPlatformWide("e")).isTrue();
    owners.createOwner(new OwnerCreate(id, "N", "e", "p", "h", id, null, NOW));

    // count null for registration store exists*
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(null);
    assertThat(store.existsGstin("x")).isFalse();
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(0);
    assertThat(store.existsPan("x")).isFalse();
    assertThat(store.existsPhone("x")).isFalse();
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(1);
    assertThat(store.existsGstin("x")).isTrue();
    assertThat(store.existsPan("x")).isTrue();
    assertThat(store.existsPhone("x")).isTrue();
    assertThat(store.existsEmail("x")).isTrue();
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(0);
    assertThat(store.existsDrugLicence("d", "29")).isFalse();
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(2);
    assertThat(store.existsDrugLicence("d", "29")).isTrue();
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any())).thenReturn(2);
    assertThat(owners.phoneTakenPlatformWide("p")).isTrue();

    new JdbcPharmacySessionStore(jdbc).save(id, id, "h", null, "ua", NOW, NOW, id);
    new JdbcPharmacySessionStore(jdbc).save(id, id, "h", "  ", "ua", NOW, NOW, id);
    new JdbcPharmacySessionStore(jdbc).save(id, id, "h", "10.0.0.1", "ua", NOW, NOW, id);
    new com.nammamedmate.pharmacy.adapter.out.persistence.JdbcRegistrationAuditStore(jdbc)
        .save(id, id, "e", "p", "   ", "SUCCESS", null, NOW);
    new com.nammamedmate.pharmacy.adapter.out.persistence.JdbcRegistrationAuditStore(jdbc)
        .save(id, id, "e", "p", "   ", "SUCCESS", null, NOW);

    PharmacyRegistrationService service = mock(PharmacyRegistrationService.class);
    when(service.register(any(), anyString())).thenReturn(Map.of("ok", true));
    PharmacyRegistrationController controller = new PharmacyRegistrationController(service);
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.setRemoteAddr("10.0.0.1");
    assertThat(
            controller
                .register(
                    new PharmacyRegistrationController.RegisterRequest(
                        "O", "B", "p", "e", "pw", "PHARMACY", null, "g", "d", null, "pan"),
                    req)
                .getStatusCode()
                .is2xxSuccessful())
        .isTrue();
    MockHttpServletRequest req2 = new MockHttpServletRequest();
    req2.setRemoteAddr(null);
    assertThat(controller.register(null, req2).getStatusCode().is2xxSuccessful()).isTrue();
    MockHttpServletRequest req3 = new MockHttpServletRequest();
    req3.setRemoteAddr("  ");
    assertThat(controller.register(null, req3).getStatusCode().is2xxSuccessful()).isTrue();
  }
}
