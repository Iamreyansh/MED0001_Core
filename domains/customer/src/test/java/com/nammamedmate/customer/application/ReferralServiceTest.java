package com.nammamedmate.customer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.customer.application.ReferralService.ApplyCommand;
import com.nammamedmate.customer.application.port.out.ReferralStore.ReferralEventRecord;
import com.nammamedmate.customer.application.port.out.ReferralStore.ReferralRecord;
import com.nammamedmate.customer.application.port.out.WalletStore.WalletRecord;
import com.nammamedmate.customer.domain.ReferralEventStatus;
import com.nammamedmate.customer.domain.WalletCreditReason;
import com.nammamedmate.customer.support.CustomerTestFixtures;
import com.nammamedmate.customer.support.FakeCustomerProfileStore;
import com.nammamedmate.customer.support.FakeReferralStore;
import com.nammamedmate.customer.support.FakeWalletStore;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReferralServiceTest {

  private static final Instant NOW = CustomerTestFixtures.NOW;
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private FakeReferralStore referrals;
  private FakeCustomerProfileStore profiles;
  private FakeWalletStore wallets;
  private AtomicBoolean hasOrdered;
  private ReferralService service;
  private UUID referrerId;
  private UUID refereeId;
  private MedmatePrincipal refereePrincipal;

  @BeforeEach
  void setUp() {
    referrals = new FakeReferralStore();
    profiles = new FakeCustomerProfileStore();
    wallets = new FakeWalletStore();
    hasOrdered = new AtomicBoolean(false);
    WalletService walletService =
        new WalletService(wallets, profiles, new InMemoryRateLimiter(CLOCK), CLOCK, 100_000L);
    service =
        new ReferralService(
            referrals,
            profiles,
            id -> hasOrdered.get(),
            walletService,
            new InMemoryRateLimiter(CLOCK),
            CLOCK,
            "https://nammamedmate.com/join",
            10_000L);

    referrerId = Ids.newId();
    refereeId = Ids.newId();
    profiles.saveProfile(CustomerTestFixtures.customerWithName(referrerId, "Ramesh"));
    profiles.saveProfile(CustomerTestFixtures.customer(refereeId));
    referrals.insert(new ReferralRecord(Ids.newId(), referrerId, "MEDRAM7", 0, 0, 0L, NOW));
    referrals.insert(new ReferralRecord(Ids.newId(), refereeId, "MEDREF1", 0, 0, 0L, NOW));
    wallets.insertWallet(new WalletRecord(Ids.newId(), referrerId, 0L, 0L, 0L, 0L, NOW, NOW));
    wallets.insertWallet(new WalletRecord(Ids.newId(), refereeId, 0L, 0L, 0L, 0L, NOW, NOW));

    refereePrincipal =
        new MedmatePrincipal(refereeId, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
  }

  @Test
  void applyCode_createsPendingEvent() {
    Map<String, Object> data = service.applyCode(refereePrincipal, new ApplyCommand("medram7"));

    assertThat(data)
        .containsEntry("referrer_code", "MEDRAM7")
        .containsEntry("status", "PENDING")
        .containsEntry("reward_amount", new BigDecimal("100.00"));
    assertThat(data.get("message").toString()).contains("Ramesh");
    assertThat(referrals.findEventByReferee(refereeId))
        .get()
        .extracting(ReferralEventRecord::status)
        .isEqualTo(ReferralEventStatus.PENDING);
    assertThat(referrals.findByCustomerId(referrerId).orElseThrow().totalReferrals()).isEqualTo(1);
  }

  @Test
  void applyCode_twice_returnsAlreadyUsed() {
    service.applyCode(refereePrincipal, new ApplyCommand("MEDRAM7"));
    assertThatThrownBy(() -> service.applyCode(refereePrincipal, new ApplyCommand("MEDRAM7")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("REFERRAL_ALREADY_USED");
  }

  @Test
  void applyOwnCode_returnsSelfReferralNotAllowed() {
    MedmatePrincipal self =
        new MedmatePrincipal(referrerId, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    assertThatThrownBy(() -> service.applyCode(self, new ApplyCommand("MEDRAM7")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("SELF_REFERRAL_NOT_ALLOWED");
  }

  @Test
  void applyUnknownCode_returnsNotFound() {
    assertThatThrownBy(() -> service.applyCode(refereePrincipal, new ApplyCommand("MEDZZZZ")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("REFERRAL_CODE_NOT_FOUND");
  }

  @Test
  void applyAfterFirstOrder_returnsConflict() {
    hasOrdered.set(true);
    assertThatThrownBy(() -> service.applyCode(refereePrincipal, new ApplyCommand("MEDRAM7")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FIRST_ORDER_ALREADY_PLACED");
  }

  @Test
  void applyInvalidFormat_returnsValidationError() {
    assertThatThrownBy(() -> service.applyCode(refereePrincipal, new ApplyCommand("SHORT")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.applyCode(refereePrincipal, new ApplyCommand(null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void onDelivered_rewardsBothWalletsAndMarksRewarded() {
    service.applyCode(refereePrincipal, new ApplyCommand("MEDRAM7"));
    UUID orderId = Ids.newId();

    ReferralEventRecord event = service.onRefereeOrderDelivered(refereeId, orderId).orElseThrow();

    assertThat(event.status()).isEqualTo(ReferralEventStatus.REWARDED);
    assertThat(event.firstOrderId()).isEqualTo(orderId);
    assertThat(wallets.findByCustomerId(refereeId).orElseThrow().balancePaise()).isEqualTo(10_000L);
    assertThat(wallets.findByCustomerId(referrerId).orElseThrow().balancePaise())
        .isEqualTo(10_000L);
    assertThat(
            wallets.listTransactions(
                wallets.findByCustomerId(refereeId).orElseThrow().id(),
                null,
                "created_at",
                "desc",
                10,
                0))
        .anyMatch(tx -> WalletCreditReason.REFERRAL.name().equals(tx.reason()));
    assertThat(referrals.findByCustomerId(referrerId).orElseThrow().convertedReferrals())
        .isEqualTo(1);
  }

  @Test
  void onCancelled_marksCancelledWithoutWalletCredit() {
    service.applyCode(refereePrincipal, new ApplyCommand("MEDRAM7"));
    UUID orderId = Ids.newId();

    ReferralEventRecord event =
        service.onRefereeFirstOrderCancelled(refereeId, orderId).orElseThrow();

    assertThat(event.status()).isEqualTo(ReferralEventStatus.CANCELLED);
    assertThat(wallets.findByCustomerId(refereeId).orElseThrow().balancePaise()).isZero();
    assertThat(wallets.findByCustomerId(referrerId).orElseThrow().balancePaise()).isZero();
  }

  @Test
  void getMyReferral_includesLinkAndShareMessage() {
    Map<String, Object> data =
        service.getMyReferral(
            new MedmatePrincipal(referrerId, AuthRole.CUSTOMER, null, TokenScope.FULL, "j"));

    assertThat(data)
        .containsEntry("referral_code", "MEDRAM7")
        .containsEntry("referral_link", "https://nammamedmate.com/join?ref=MEDRAM7")
        .containsEntry("total_referrals", 0)
        .containsEntry("pending_referrals", 0);
    assertThat(data.get("share_message").toString()).contains("MEDRAM7");
  }

  @Test
  void coverage_edgeBranches() {
    ReferralService trailing =
        new ReferralService(
            referrals,
            profiles,
            id -> false,
            new WalletService(wallets, profiles, new InMemoryRateLimiter(CLOCK), CLOCK, 100_000L),
            new InMemoryRateLimiter(CLOCK),
            CLOCK,
            "https://nammamedmate.com/join/",
            0L);
    assertThat(
            trailing
                .getMyReferral(
                    new MedmatePrincipal(referrerId, AuthRole.CUSTOMER, null, TokenScope.FULL, "j"))
                .get("referral_link")
                .toString())
        .startsWith("https://nammamedmate.com/join?ref=");

    assertThatThrownBy(() -> service.applyCode(refereePrincipal, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.applyCode(refereePrincipal, new ApplyCommand(null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.applyCode(refereePrincipal, new ApplyCommand("  ")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    profiles.saveProfile(CustomerTestFixtures.customerWithName(referrerId, null));
    Map<String, Object> applied = service.applyCode(refereePrincipal, new ApplyCommand("MEDRAM7"));
    assertThat(applied.get("message").toString()).contains("your referrer");

    UUID blankNameReferee = Ids.newId();
    profiles.saveProfile(CustomerTestFixtures.customer(blankNameReferee));
    referrals.insert(new ReferralRecord(Ids.newId(), blankNameReferee, "MEDBLNK", 0, 0, 0L, NOW));
    profiles.saveProfile(CustomerTestFixtures.customerWithName(referrerId, "   "));
    Map<String, Object> blankApplied =
        service.applyCode(
            new MedmatePrincipal(blankNameReferee, AuthRole.CUSTOMER, null, TokenScope.FULL, "j"),
            new ApplyCommand("MEDRAM7"));
    assertThat(blankApplied.get("message").toString()).contains("your referrer");

    referrals.failNextInsertEvent = true;
    UUID other = Ids.newId();
    profiles.saveProfile(CustomerTestFixtures.customer(other));
    referrals.insert(new ReferralRecord(Ids.newId(), other, "MEDOTH1", 0, 0, 0L, NOW));
    assertThatThrownBy(
            () ->
                service.applyCode(
                    new MedmatePrincipal(other, AuthRole.CUSTOMER, null, TokenScope.FULL, "j"),
                    new ApplyCommand("MEDRAM7")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("REFERRAL_ALREADY_USED");

    assertThatThrownBy(() -> service.onRefereeOrderDelivered(null, Ids.newId()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.onRefereeOrderDelivered(refereeId, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThat(service.onRefereeOrderDelivered(Ids.newId(), Ids.newId())).isEmpty();

    // Reward with referrer lock missing → requireReferral path
    referrals.clearLocks = true;
    service.onRefereeOrderDelivered(refereeId, Ids.newId());
    referrals.clearLocks = false;
    assertThat(service.onRefereeOrderDelivered(refereeId, Ids.newId()))
        .get()
        .extracting(ReferralEventRecord::status)
        .isEqualTo(ReferralEventStatus.REWARDED);

    assertThatThrownBy(() -> service.onRefereeFirstOrderCancelled(null, Ids.newId()))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThat(service.onRefereeFirstOrderCancelled(Ids.newId(), Ids.newId())).isEmpty();
    assertThat(service.onRefereeFirstOrderCancelled(refereeId, Ids.newId()))
        .get()
        .extracting(ReferralEventRecord::status)
        .isEqualTo(ReferralEventStatus.REWARDED);

    UUID pendingCancel = Ids.newId();
    profiles.saveProfile(CustomerTestFixtures.customer(pendingCancel));
    referrals.insert(new ReferralRecord(Ids.newId(), pendingCancel, "MEDPEND", 0, 0, 0L, NOW));
    service.applyCode(
        new MedmatePrincipal(pendingCancel, AuthRole.CUSTOMER, null, TokenScope.FULL, "j"),
        new ApplyCommand("MEDRAM7"));
    assertThat(service.onRefereeFirstOrderCancelled(pendingCancel, Ids.newId()))
        .get()
        .extracting(ReferralEventRecord::status)
        .isEqualTo(ReferralEventStatus.CANCELLED);

    assertThatThrownBy(
            () ->
                service.getMyReferral(
                    new MedmatePrincipal(
                        referrerId, AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");

    UUID fresh = Ids.newId();
    profiles.saveProfile(CustomerTestFixtures.customer(fresh));
    wallets.insertWallet(new WalletRecord(Ids.newId(), fresh, 0L, 0L, 0L, 0L, NOW, NOW));
    assertThat(
            service
                .getMyReferral(
                    new MedmatePrincipal(fresh, AuthRole.CUSTOMER, null, TokenScope.FULL, "j"))
                .get("referral_code")
                .toString())
        .startsWith("MED");

    referrals.failNextInsertReferral = true;
    UUID race = Ids.newId();
    profiles.saveProfile(CustomerTestFixtures.customer(race));
    referrals.revealAfterFailedInsert =
        new ReferralRecord(Ids.newId(), race, "MEDRACE", 0, 0, 0L, NOW);
    assertThat(
            service
                .getMyReferral(
                    new MedmatePrincipal(race, AuthRole.CUSTOMER, null, TokenScope.FULL, "j"))
                .get("referral_code"))
        .isEqualTo("MEDRACE");

    referrals.failNextInsertReferral = true;
    UUID gone = Ids.newId();
    assertThatThrownBy(
            () ->
                service.getMyReferral(
                    new MedmatePrincipal(gone, AuthRole.CUSTOMER, null, TokenScope.FULL, "j")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CUSTOMER_NOT_FOUND");

    assertThatThrownBy(() -> service.getMyReferral(null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("UNAUTHORIZED");

    MedmatePrincipal refP =
        new MedmatePrincipal(referrerId, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    for (int i = 0; i < 30; i++) {
      service.getMyReferral(refP);
    }
    assertThatThrownBy(() -> service.getMyReferral(refP))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RATE_LIMITED");
  }
}
