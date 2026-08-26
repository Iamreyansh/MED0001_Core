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
import com.nammamedmate.pharmacy.adapter.out.kyc.StubPennyDropClient;
import com.nammamedmate.pharmacy.adapter.out.persistence.JdbcPharmacyProfileOtpStore;
import com.nammamedmate.pharmacy.adapter.out.persistence.JdbcPharmacyProfileStore;
import com.nammamedmate.pharmacy.adapter.out.persistence.JdbcProfileChangeRequestStore;
import com.nammamedmate.pharmacy.application.PennyDropMaintenanceScheduler;
import com.nammamedmate.pharmacy.application.PharmacyProfileService;
import com.nammamedmate.pharmacy.application.port.out.PharmacyProfileOtpStore.OtpRecord;
import com.nammamedmate.pharmacy.application.port.out.PharmacyProfileStore.BankAccountRecord;
import com.nammamedmate.pharmacy.application.port.out.PharmacyProfileStore.OperatingHoursRecord;
import com.nammamedmate.pharmacy.application.port.out.ProfileChangeRequestStore.ChangeRequestRecord;
import java.sql.ResultSet;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class PharmacyProfileJdbcCoverageTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");

  @Test
  void jdbcProfileStoresAndStubs() throws Exception {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    ObjectMapper mapper = new ObjectMapper();
    ObjectMapper badMapper = mock(ObjectMapper.class);
    when(badMapper.writeValueAsString(any())).thenThrow(new RuntimeException("boom"));
    when(badMapper.readValue(anyString(), any(com.fasterxml.jackson.core.type.TypeReference.class)))
        .thenThrow(new RuntimeException("boom"));

    UUID id = Ids.newId();
    JdbcPharmacyProfileStore store = new JdbcPharmacyProfileStore(jdbc, mapper);
    JdbcPharmacyProfileStore badStore = new JdbcPharmacyProfileStore(jdbc, badMapper);

    store.updateProfileFields(id, "t", "https://cdn/x.png", null, NOW);
    store.updateProfileFields(id, null, null, Map.of("city", "Bengaluru"), NOW);
    store.setPendingPhone(id, "+919811100001", NOW);
    store.setPendingEmail(id, "a@t.com", NOW);
    store.applyPhone(id, "+919811100001", NOW);
    store.applyEmail(id, "a@t.com", NOW);
    store.updateTaxFields(id, "g", "p", "d", "f", true, false, false, true, "ph", true, NOW);
    store.updateBusinessName(id, "Biz", NOW);
    store.updateTagline(id, "tag", NOW);
    store.updateLogoUrl(id, "https://cdn/x.png", NOW);
    store.updateAddress(id, Map.of("pincode", "560034"), NOW);
    store.updatePhone(id, "+919811100001", NOW);
    store.updateEmail(id, "a@t.com", NOW);
    store.replaceOperatingHours(
        id,
        List.of(
            new OperatingHoursRecord(
                Ids.newId(), id, 0, LocalTime.of(9, 0), LocalTime.of(18, 0), false),
            new OperatingHoursRecord(Ids.newId(), id, 1, null, null, true)),
        NOW);
    store.updateAddress(id, null, NOW);
    UUID bankId = Ids.newId();
    store.insertBankAccount(
        new BankAccountRecord(
            bankId,
            id,
            "H",
            "B",
            "enc",
            "1234",
            "HDFC0001234",
            "CURRENT",
            "VERIFIED",
            "RZP",
            NOW,
            NOW,
            NOW));
    store.updateBankVerification(bankId, "VERIFIED", "RZP", NOW, NOW);

    UUID pendingBankId = Ids.newId();
    store.insertBankAccount(
        new BankAccountRecord(
            pendingBankId,
            id,
            "H",
            "B",
            "enc",
            "1234",
            "HDFC0001234",
            "CURRENT",
            "PENDING",
            "RZP",
            null,
            NOW,
            NOW));
    store.updateBankVerification(pendingBankId, "FAILED", "RZP", null, NOW);
    store.softDeleteBankAccount(pendingBankId, NOW);

    when(jdbc.query(anyString(), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              ResultSet rs = profileRs(id, null, null);
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            });
    assertThat(store.findById(id)).isPresent();

    when(jdbc.query(
            anyString(),
            any(RowMapper.class),
            eq(UUID.fromString("00000000-0000-0000-0000-000000000097"))))
        .thenAnswer(
            inv -> {
              ResultSet rs = profileRs(id, null, null);
              when(rs.getTimestamp("created_at")).thenReturn(null);
              when(rs.getTimestamp("updated_at")).thenReturn(null);
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            });
    assertThat(
            store
                .findById(UUID.fromString("00000000-0000-0000-0000-000000000097"))
                .orElseThrow()
                .createdAt())
        .isNull();

    when(jdbc.query(contains("pharmacy_operating_hours"), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(Ids.newId());
              when(rs.getObject("pharmacy_id")).thenReturn(id);
              when(rs.getInt("day_of_week")).thenReturn(0);
              when(rs.getTime("open_time")).thenReturn(Time.valueOf("09:00:00"));
              when(rs.getTime("close_time")).thenReturn(null);
              when(rs.getBoolean("is_closed")).thenReturn(false);
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            });
    assertThat(store.listOperatingHours(id)).hasSize(1);

    when(jdbc.query(contains("pharmacy_bank_accounts"), any(RowMapper.class), eq(id)))
        .thenAnswer(
            inv -> {
              ResultSet rs = bankRs(bankId, id);
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            });
    assertThat(store.findActiveBankAccount(id)).isPresent();
    when(jdbc.query(
            contains("verification_status = 'PENDING'"), any(RowMapper.class), any(), any()))
        .thenAnswer(
            inv -> {
              ResultSet rs = bankRs(bankId, id);
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            });
    assertThat(store.findStalePendingBankAccounts(NOW, 5)).hasSize(1);

    when(jdbc.query(
            anyString(),
            any(RowMapper.class),
            eq(UUID.fromString("00000000-0000-0000-0000-000000000099"))))
        .thenAnswer(
            inv -> {
              ResultSet rs = profileRs(id, "   ", null);
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            });
    assertThat(store.findById(UUID.fromString("00000000-0000-0000-0000-000000000099"))).isPresent();
    when(jdbc.query(
            anyString(),
            any(RowMapper.class),
            eq(UUID.fromString("00000000-0000-0000-0000-000000000098"))))
        .thenAnswer(
            inv -> {
              ResultSet rs = profileRs(id, null, "{bad");
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            });
    assertThatThrownBy(
            () -> store.findById(UUID.fromString("00000000-0000-0000-0000-000000000098")))
        .isInstanceOf(IllegalStateException.class);

    assertThatThrownBy(() -> badStore.updateAddress(id, Map.of("x", 1), NOW))
        .isInstanceOf(IllegalStateException.class);

    JdbcProfileChangeRequestStore changes = new JdbcProfileChangeRequestStore(jdbc);
    changes.insert(
        new ChangeRequestRecord(
            Ids.newId(), id, "business_name", "old", "new", "APPROVED", Ids.newId(), NOW, NOW));
    changes.insert(
        new ChangeRequestRecord(
            Ids.newId(), id, "business_name", "old", "new", "PENDING_APPROVAL", null, null, NOW));

    when(jdbc.query(
            anyString(),
            any(RowMapper.class),
            eq(UUID.fromString("00000000-0000-0000-0000-000000000096"))))
        .thenAnswer(
            inv -> {
              ResultSet rs = profileRs(id, "{\"city\":\"Bengaluru\"}", null);
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            });
    assertThat(
            store
                .findById(UUID.fromString("00000000-0000-0000-0000-000000000096"))
                .orElseThrow()
                .address())
        .containsEntry("city", "Bengaluru");

    JdbcPharmacyProfileOtpStore otps = new JdbcPharmacyProfileOtpStore(jdbc);
    OtpRecord otp =
        new OtpRecord(
            Ids.newId(), id, "PHONE", "+919811100001", "hash", NOW.plusSeconds(600), 0, NOW);
    otps.insert(otp);
    otps.update(otp);
    otps.deleteByPharmacyAndChannel(id, "PHONE");
    when(jdbc.query(contains("pharmacy_profile_otps"), any(RowMapper.class), eq(id), eq("PHONE")))
        .thenAnswer(
            inv -> {
              ResultSet rs = mock(ResultSet.class);
              when(rs.getObject("id")).thenReturn(otp.id());
              when(rs.getObject("pharmacy_id")).thenReturn(id);
              when(rs.getString("channel")).thenReturn("PHONE");
              when(rs.getString("target_value")).thenReturn("+919811100001");
              when(rs.getString("otp_hash")).thenReturn("hash");
              when(rs.getTimestamp("expires_at")).thenReturn(Timestamp.from(NOW.plusSeconds(600)));
              when(rs.getInt("attempts")).thenReturn(0);
              when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
              return List.of(((RowMapper<?>) inv.getArgument(1)).mapRow(rs, 0));
            });
    assertThat(otps.findLatest(id, "PHONE")).isPresent();

    assertThat(new StubPennyDropClient().initiate(id, "HDFC0001234", "1234").referenceId())
        .startsWith("RZP-PENNY-");

    PennyDropMaintenanceScheduler scheduler =
        new PennyDropMaintenanceScheduler(mock(PharmacyProfileService.class));
    scheduler.expireStalePennyDrops();
  }

  private static ResultSet profileRs(UUID id, String address, String badAddress) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(id);
    when(rs.getString("code")).thenReturn("PHM-0001");
    when(rs.getString("business_name")).thenReturn("Biz");
    when(rs.getString("tagline")).thenReturn(null);
    when(rs.getString("logo_url")).thenReturn(null);
    when(rs.getString("phone")).thenReturn(null);
    when(rs.getString("email")).thenReturn(null);
    when(rs.getString("pending_phone")).thenReturn(null);
    when(rs.getString("pending_email")).thenReturn(null);
    when(rs.getString("business_type")).thenReturn("PHARMACY");
    when(rs.getString("address")).thenReturn(badAddress != null ? badAddress : address);
    when(rs.getString("status")).thenReturn("ACTIVE");
    when(rs.getString("plan")).thenReturn("FREE");
    when(rs.getString("gstin")).thenReturn(null);
    when(rs.getString("pan_number")).thenReturn(null);
    when(rs.getString("drug_licence_number")).thenReturn(null);
    when(rs.getString("fssai_number")).thenReturn(null);
    when(rs.getBoolean("is_gst_registered")).thenReturn(false);
    // e_invoicing_enabled dropped
    when(rs.getBoolean("tds_applicable")).thenReturn(false);
    when(rs.getBoolean("tcs_applicable")).thenReturn(true);
    when(rs.getBoolean("gstin_reverification_pending")).thenReturn(false);
    when(rs.getString("registered_pharmacist_name")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(NOW));
    return rs;
  }

  private static ResultSet bankRs(UUID bankId, UUID pharmacyId) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getObject("id")).thenReturn(bankId);
    when(rs.getObject("pharmacy_id")).thenReturn(pharmacyId);
    when(rs.getString("account_holder")).thenReturn("H");
    when(rs.getString("bank_name")).thenReturn("B");
    when(rs.getString("account_number_encrypted")).thenReturn("enc");
    when(rs.getString("account_number_last4")).thenReturn("1234");
    when(rs.getString("ifsc_code")).thenReturn("HDFC0001234");
    when(rs.getString("account_type")).thenReturn("CURRENT");
    when(rs.getString("verification_status")).thenReturn("PENDING");
    when(rs.getString("penny_drop_reference")).thenReturn("RZP");
    when(rs.getTimestamp("verified_at")).thenReturn(null);
    when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(NOW));
    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(NOW));
    return rs;
  }

  private static String contains(String fragment) {
    return org.mockito.ArgumentMatchers.argThat(
        (String sql) -> sql != null && sql.contains(fragment));
  }
}
