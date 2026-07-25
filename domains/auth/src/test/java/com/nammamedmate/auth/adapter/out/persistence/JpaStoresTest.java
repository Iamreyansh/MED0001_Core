package com.nammamedmate.auth.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nammamedmate.auth.application.port.out.AuthSessionRecord;
import com.nammamedmate.auth.application.port.out.CustomerRecord;
import com.nammamedmate.auth.application.port.out.OtpSessionRecord;
import com.nammamedmate.kernel.id.Ids;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JpaStoresTest {

  @Test
  void otpSessionStoreRoundTrip() {
    OtpSessionJpaRepository repo = mock(OtpSessionJpaRepository.class);
    JpaOtpSessionStore store = new JpaOtpSessionStore(repo);
    UUID id = Ids.newId();
    Instant now = Instant.parse("2026-07-25T08:00:00Z");
    OtpSessionRecord record =
        new OtpSessionRecord(
            id, "+919876543210", "hash", 1, "{}", now.plusSeconds(600), null, null, now);

    store.save(record);
    verify(repo).save(any(OtpSessionEntity.class));

    OtpSessionEntity entity =
        new OtpSessionEntity(
            id, "+919876543210", "hash", (short) 1, "{}", now.plusSeconds(600), now, now, now);
    when(repo.findById(id)).thenReturn(Optional.of(entity));

    Optional<OtpSessionRecord> found = store.findById(id);
    assertThat(found).isPresent();
    assertThat(found.get().attempts()).isEqualTo(1);
    assertThat(found.get().verifiedAt()).isEqualTo(now);
    assertThat(entity.getPhone()).isEqualTo("+919876543210");
    assertThat(entity.getOtpHash()).isEqualTo("hash");
    assertThat(entity.getDeviceInfoJson()).isEqualTo("{}");
    assertThat(entity.getExpiresAt()).isEqualTo(now.plusSeconds(600));
    assertThat(entity.getLockedAt()).isEqualTo(now);
    assertThat(entity.getCreatedAt()).isEqualTo(now);
    assertThat(entity.getId()).isEqualTo(id);
  }

  @Test
  void customerStoreRoundTrip() {
    CustomerJpaRepository repo = mock(CustomerJpaRepository.class);
    JpaCustomerStore store = new JpaCustomerStore(repo);
    UUID id = Ids.newId();
    Instant now = Instant.parse("2026-07-25T08:00:00Z");
    CustomerRecord record =
        new CustomerRecord(
            id,
            "+919876543210",
            List.of("t1"),
            "Ada",
            "http://a",
            LocalDate.of(1990, 1, 2),
            "FEMALE",
            "en",
            "NEW",
            250L,
            3,
            now);

    store.save(record);
    verify(repo).save(any(CustomerEntity.class));

    CustomerEntity entity =
        new CustomerEntity(
            id,
            "+919876543210",
            new String[] {"t1"},
            "Ada",
            "http://a",
            LocalDate.of(1990, 1, 2),
            "FEMALE",
            "en",
            "NEW",
            250L,
            3,
            now,
            now,
            null);
    when(repo.findByPhoneAndDeletedAtIsNull("+919876543210")).thenReturn(Optional.of(entity));

    Optional<CustomerRecord> found = store.findByPhone("+919876543210");
    assertThat(found).isPresent();
    assertThat(found.get().name()).isEqualTo("Ada");
    assertThat(found.get().walletBalancePaise()).isEqualTo(250L);
    assertThat(entity.getDeletedAt()).isNull();
    assertThat(entity.getUpdatedAt()).isEqualTo(now);
    assertThat(entity.getAvatarUrl()).isEqualTo("http://a");
    assertThat(entity.getDateOfBirth()).isEqualTo(LocalDate.of(1990, 1, 2));
    assertThat(entity.getGender()).isEqualTo("FEMALE");
    assertThat(entity.getPreferredLanguage()).isEqualTo("en");
    assertThat(entity.getSegment()).isEqualTo("NEW");
    assertThat(entity.getLoyaltyPoints()).isEqualTo(3);
    assertThat(entity.getDeviceTokens()).containsExactly("t1");
    assertThat(entity.getId()).isEqualTo(id);
    assertThat(entity.getPhone()).isEqualTo("+919876543210");
    assertThat(entity.getCreatedAt()).isEqualTo(now);

    CustomerRecord noTokens =
        new CustomerRecord(
            id, "+919811111111", null, null, null, null, null, null, null, 0, 0, now);
    store.save(noTokens);
    CustomerEntity emptyTokens =
        new CustomerEntity(id, "p", null, null, null, null, null, null, null, 0, 0, now, now, null);
    assertThat(JpaCustomerStore.toRecord(emptyTokens).deviceTokens()).isEmpty();
  }

  @Test
  void authSessionStoreSaves() {
    AuthSessionJpaRepository repo = mock(AuthSessionJpaRepository.class);
    JpaAuthSessionStore store = new JpaAuthSessionStore(repo);
    UUID id = Ids.newId();
    UUID pharmacyId = Ids.newId();
    Instant now = Instant.parse("2026-07-25T08:00:00Z");
    AuthSessionRecord record =
        new AuthSessionRecord(
            id,
            Ids.newId(),
            "pharmacy_staff",
            "hash",
            "full",
            "{}",
            "1.1.1.1",
            "ua",
            now,
            now,
            now.plusSeconds(10),
            pharmacyId);

    store.save(record);
    verify(repo).save(any(AuthSessionEntity.class));

    AuthSessionEntity entity =
        new AuthSessionEntity(
            id,
            record.userId(),
            "pharmacy_staff",
            "hash",
            "full",
            "{}",
            "1.1.1.1",
            "ua",
            now,
            now,
            now.plusSeconds(10),
            pharmacyId);
    assertThat(entity.getId()).isEqualTo(id);
    assertThat(entity.getUserId()).isEqualTo(record.userId());
    assertThat(entity.getUserType()).isEqualTo("pharmacy_staff");
    assertThat(entity.getRefreshTokenHash()).isEqualTo("hash");
    assertThat(entity.getTokenScope()).isEqualTo("full");
    assertThat(entity.getDeviceInfoJson()).isEqualTo("{}");
    assertThat(entity.getIpAddress()).isEqualTo("1.1.1.1");
    assertThat(entity.getUserAgent()).isEqualTo("ua");
    assertThat(entity.getCreatedAt()).isEqualTo(now);
    assertThat(entity.getLastActiveAt()).isEqualTo(now);
    assertThat(entity.getExpiresAt()).isEqualTo(now.plusSeconds(10));
    assertThat(entity.getPharmacyId()).isEqualTo(pharmacyId);

    // customer session with null pharmacyId
    AuthSessionRecord customerRecord =
        new AuthSessionRecord(
            id,
            record.userId(),
            "customer",
            "hash",
            "full",
            "{}",
            "1.1.1.1",
            "ua",
            now,
            now,
            now.plusSeconds(10),
            null);
    store.save(customerRecord);
    AuthSessionEntity noPharmacy =
        new AuthSessionEntity(
            id,
            record.userId(),
            "customer",
            "hash",
            "full",
            "{}",
            "1.1.1.1",
            "ua",
            now,
            now,
            now.plusSeconds(10),
            null);
    assertThat(noPharmacy.getPharmacyId()).isNull();
  }
}
