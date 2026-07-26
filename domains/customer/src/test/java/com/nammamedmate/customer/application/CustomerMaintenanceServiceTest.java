package com.nammamedmate.customer.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.nammamedmate.customer.support.CustomerTestFixtures;
import com.nammamedmate.customer.support.FakeCustomerProfileStore;
import com.nammamedmate.kernel.id.Ids;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;

class CustomerMaintenanceServiceTest {

  private static final Instant NOW = CustomerTestFixtures.NOW;
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private FakeCustomerProfileStore store;
  private CustomerMaintenanceService service;

  @BeforeEach
  void setUp() {
    store = new FakeCustomerProfileStore();
    service =
        new CustomerMaintenanceService(
            store,
            CLOCK,
            new org.springframework.transaction.PlatformTransactionManager() {
              @Override
              public org.springframework.transaction.TransactionStatus getTransaction(
                  org.springframework.transaction.TransactionDefinition definition) {
                return new SimpleTransactionStatus();
              }

              @Override
              public void commit(org.springframework.transaction.TransactionStatus status) {}

              @Override
              public void rollback(org.springframework.transaction.TransactionStatus status) {}
            });
  }

  @Test
  void recomputeSegments_totalOrders12MovesRegularToLoyal() {
    UUID id = Ids.newId();
    store.saveProfile(CustomerTestFixtures.customerWith(id, "REGULAR", 12, 0L, false));

    int changed = service.recomputeSegments();

    assertThat(changed).isEqualTo(1);
    assertThat(store.findById(id).orElseThrow().segment()).isEqualTo("LOYAL");
    assertThat(store.segmentChanges()).hasSize(1);
    assertThat(store.segmentChanges().getFirst().from()).isEqualTo("REGULAR");
    assertThat(store.segmentChanges().getFirst().to()).isEqualTo("LOYAL");
  }

  @Test
  void recomputeSegments_nullSegmentTreatedAsNew() {
    UUID id = Ids.newId();
    store.saveProfile(CustomerTestFixtures.customerWith(id, null, 1, 0L, false));

    int changed = service.recomputeSegments();

    assertThat(changed).isEqualTo(1);
    assertThat(store.findById(id).orElseThrow().segment()).isEqualTo("REGULAR");
  }

  @Test
  void recomputeSegments_noChangeWhenSegmentMatches() {
    UUID id = Ids.newId();
    store.saveProfile(CustomerTestFixtures.customerWith(id, "VIP", 60, 0L, false));

    assertThat(service.recomputeSegments()).isZero();
    assertThat(store.segmentChanges()).isEmpty();
  }

  @Test
  void anonymiseDueAccounts_hashesPhoneAndSoftDeletes() {
    UUID id = Ids.newId();
    store.saveProfile(CustomerTestFixtures.customer(id));
    store.requestDeletion(id, NOW.minus(31, ChronoUnit.DAYS), "done");

    int count = service.anonymiseDueAccounts();

    assertThat(count).isEqualTo(1);
    var saved = store.findById(id).orElseThrow();
    assertThat(saved.name()).isEqualTo("Deleted User");
    assertThat(saved.phone()).startsWith("del_").hasSize(64);
    assertThat(saved.city()).isNull();
    assertThat(saved.deletedAt()).isEqualTo(NOW);
    assertThat(saved.deletionRequestedAt()).isNull();
  }

  @Test
  void hashPhone_isDeterministic() {
    String hash = CustomerMaintenanceService.hashPhone("id-1", "+911234567890");
    assertThat(hash).startsWith("del_").hasSize(64);
    assertThat(CustomerMaintenanceService.hashPhone("id-1", "+911234567890")).isEqualTo(hash);
  }

  @Test
  void hashPhone_noSuchAlgorithm_wrapsIllegalStateException() throws Exception {
    try (var mocked = org.mockito.Mockito.mockStatic(java.security.MessageDigest.class)) {
      mocked
          .when(() -> java.security.MessageDigest.getInstance("SHA-256"))
          .thenThrow(new java.security.NoSuchAlgorithmException("boom"));

      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> CustomerMaintenanceService.hashPhone("id", "+91111"))
          .isInstanceOf(IllegalStateException.class);
    }
  }
}
