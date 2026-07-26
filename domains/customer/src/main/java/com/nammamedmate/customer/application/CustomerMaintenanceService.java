package com.nammamedmate.customer.application;

import com.nammamedmate.customer.application.port.out.CustomerProfileStore;
import com.nammamedmate.customer.application.port.out.CustomerProfileStore.CustomerProfileRecord;
import com.nammamedmate.customer.domain.CustomerSegment;
import com.nammamedmate.kernel.id.Ids;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CustomerMaintenanceService {

  private static final int DELETION_GRACE_DAYS = 30;

  /** del_ + 60 hex = 64 chars (customers.phone VARCHAR(64)). */
  private static final int HASH_HEX_CHARS = 60;

  private final CustomerProfileStore store;
  private final Clock clock;
  private final TransactionTemplate tx;

  public CustomerMaintenanceService(
      CustomerProfileStore store, Clock clock, PlatformTransactionManager transactionManager) {
    this.store = store;
    this.clock = clock;
    this.tx = new TransactionTemplate(transactionManager);
  }

  /** Nightly segment recomputation — call from scheduler/worker. */
  public int recomputeSegments() {
    Instant now = clock.instant();
    int changed = 0;
    List<CustomerProfileRecord> all = store.findAllActiveForSegmentRecompute();
    for (CustomerProfileRecord c : all) {
      CustomerSegment next = CustomerSegment.compute(c.totalOrders(), c.totalLtvPaise());
      String current = c.segment() == null ? CustomerSegment.NEW.name() : c.segment();
      if (!next.name().equals(current)) {
        inTx(
            status -> {
              store.updateSegment(c.id(), next.name());
              store.insertSegmentChange(
                  Ids.newId(),
                  c.id(),
                  current,
                  next.name(),
                  c.totalOrders(),
                  c.totalLtvPaise(),
                  now);
            });
        changed++;
      }
    }
    return changed;
  }

  /** Nightly anonymisation after 30-day grace. */
  public int anonymiseDueAccounts() {
    Instant cutoff = clock.instant().minus(DELETION_GRACE_DAYS, ChronoUnit.DAYS);
    List<CustomerProfileRecord> due = store.findDueForAnonymisation(cutoff);
    Instant now = clock.instant();
    for (CustomerProfileRecord c : due) {
      inTx(status -> store.anonymise(c.id(), hashPhone(c.id().toString(), c.phone()), now));
    }
    return due.size();
  }

  private void inTx(Consumer<Object> work) {
    tx.executeWithoutResult(status -> work.accept(status));
  }

  static String hashPhone(String id, String phone) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest((id + ":" + phone).getBytes(StandardCharsets.UTF_8));
      return "del_" + HexFormat.of().formatHex(hash).substring(0, HASH_HEX_CHARS);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
