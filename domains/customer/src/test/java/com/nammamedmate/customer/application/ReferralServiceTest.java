package com.nammamedmate.customer.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.customer.application.ReferralService.ApplyCommand;
import com.nammamedmate.customer.application.ReferralService.InviteCommand;
import com.nammamedmate.customer.application.ReferralService.PatchProgramCommand;
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
import java.util.List;
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
  private MedmatePrincipal adminSuper;
  private MedmatePrincipal adminOps;

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
            "https://nammamedmate.com/join");

    referrerId = Ids.newId();
    refereeId = Ids.newId();
    profiles.saveProfile(freshSignup(CustomerTestFixtures.customerWithName(referrerId, "Ramesh")));
    profiles.saveProfile(freshSignup(CustomerTestFixtures.customer(refereeId)));
    referrals.insert(new ReferralRecord(Ids.newId(), referrerId, "MEDRAM7", 0, 0, 0L, NOW));
    referrals.insert(new ReferralRecord(Ids.newId(), refereeId, "MEDREF1", 0, 0, 0L, NOW));
    wallets.insertWallet(new WalletRecord(Ids.newId(), referrerId, 0L, 0L, 0L, 0L, NOW, NOW));
    wallets.insertWallet(new WalletRecord(Ids.newId(), refereeId, 0L, 0L, 0L, 0L, NOW, NOW));

    refereePrincipal =
        new MedmatePrincipal(refereeId, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    adminSuper =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_SUPER, null, TokenScope.FULL, "j");
    adminOps =
        new MedmatePrincipal(Ids.newId(), AuthRole.ADMIN_OPERATIONS, null, TokenScope.FULL, "j");
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
  void applyWhenPaused_returnsProgramPaused() {
    referrals.setActive(false);
    assertThatThrownBy(() -> service.applyCode(refereePrincipal, new ApplyCommand("MEDRAM7")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("REFERRAL_PROGRAM_PAUSED");
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
    referrals.setRewards(15_000L, 10_000L);
    service.applyCode(refereePrincipal, new ApplyCommand("MEDRAM7"));
    UUID orderId = Ids.newId();

    ReferralEventRecord event = service.onRefereeOrderDelivered(refereeId, orderId).orElseThrow();

    assertThat(event.status()).isEqualTo(ReferralEventStatus.REWARDED);
    assertThat(event.firstOrderId()).isEqualTo(orderId);
    assertThat(wallets.findByCustomerId(refereeId).orElseThrow().balancePaise()).isEqualTo(10_000L);
    assertThat(wallets.findByCustomerId(referrerId).orElseThrow().balancePaise())
        .isEqualTo(15_000L);
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
    assertThat(data).containsKey("earnings_stats");
  }

  @Test
  void invite_logsShareAndReturnsPayload() {
    MedmatePrincipal refP =
        new MedmatePrincipal(referrerId, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    Map<String, Object> data = service.invite(refP, new InviteCommand("whatsapp"));
    assertThat(data)
        .containsEntry("channel", "WHATSAPP")
        .containsEntry("referral_code", "MEDRAM7")
        .containsEntry("share_count", 1L);
    assertThat(data.get("share_text").toString()).contains("MEDRAM7");
    assertThatThrownBy(() -> service.invite(refP, new InviteCommand("TELEGRAM")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(() -> service.invite(refP, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void adminOverviewAndProgram_settings() {
    service.applyCode(refereePrincipal, new ApplyCommand("MEDRAM7"));
    service.onRefereeOrderDelivered(refereeId, Ids.newId());

    ReferralService.AdminOverviewResult overview =
        service.adminOverview(adminOps, "CONVERTED", 1, 20);
    assertThat(overview.data().get("chips")).isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> chips = (Map<String, Object>) overview.data().get("chips");
    assertThat(chips.get("converted_referrals")).isEqualTo(1L);
    assertThat(chips.get("referral_cac_rs")).isEqualTo(200L);
    assertThat(overview.meta().total()).isEqualTo(1);

    assertThat(service.getProgram(adminOps))
        .containsEntry("is_active", true)
        .containsEntry("reward_for_referrer_rs", new BigDecimal("100.00"));

    Map<String, Object> patched =
        service.patchProgram(
            adminSuper, new PatchProgramCommand(150, 100, true, 365, "Updated conditions"));
    assertThat(patched).containsKeys("updated_at", "updated_by");
    assertThat(service.getProgram(adminOps))
        .containsEntry("reward_for_referrer_rs", new BigDecimal("150.00"))
        .containsEntry("conditions", "Updated conditions");

    assertThatThrownBy(
            () ->
                service.patchProgram(
                    adminOps, new PatchProgramCommand(null, null, false, null, null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    assertThatThrownBy(() -> service.adminOverview(refereePrincipal, null, null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");

    assertThatThrownBy(() -> service.adminOverview(adminOps, "NOPE", 1, 20))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    assertThatThrownBy(
            () ->
                service.patchProgram(adminSuper, new PatchProgramCommand(0, 100, null, null, null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThatThrownBy(
            () ->
                service.patchProgram(adminSuper, new PatchProgramCommand(100, 100, null, 0, null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
  }

  @Test
  void adminCoverage_masksAndBlankNamesAndFilters() {
    // CAC with zero conversions (converted==0 branch)
    assertThat(service.adminOverview(adminOps, null, 1, 20).data().get("chips"))
        .isInstanceOf(Map.class);

    service.applyCode(refereePrincipal, new ApplyCommand("MEDRAM7"));
    service.onRefereeOrderDelivered(refereeId, Ids.newId());

    referrals.topReferrerName = "  ";
    referrals.adminReferrerName = null;
    referrals.adminRefereeName = "   ";
    referrals.adminRefereePhone = null;
    ReferralService.AdminOverviewResult blankNames = service.adminOverview(adminOps, "  ", 1, 20);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> topBlank =
        (List<Map<String, Object>>) blankNames.data().get("top_referrers");
    assertThat(topBlank.getFirst().get("name")).isEqualTo("Customer");

    referrals.adminRefereePhone = "98";
    service.adminOverview(adminOps, null, 1, 20);
    referrals.adminRefereePhone = "9876543210";
    service.adminOverview(adminOps, null, 1, 20);
    referrals.adminRefereePhone = "+919876543210";
    service.adminOverview(adminOps, "CONVERTED", 1, 20);

    UUID pendingCancel = Ids.newId();
    profiles.saveProfile(freshSignup(CustomerTestFixtures.customer(pendingCancel)));
    referrals.insert(new ReferralRecord(Ids.newId(), pendingCancel, "MEDPEND2", 0, 0, 0L, NOW));
    service.applyCode(
        new MedmatePrincipal(pendingCancel, AuthRole.CUSTOMER, null, TokenScope.FULL, "j"),
        new ApplyCommand("MEDRAM7"));
    service.onRefereeFirstOrderCancelled(pendingCancel, Ids.newId());
    ReferralService.AdminOverviewResult expired = service.adminOverview(adminOps, "EXPIRED", 1, 20);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rows = (List<Map<String, Object>>) expired.data().get("referrals");
    assertThat(rows.getFirst().get("status")).isEqualTo("EXPIRED");

    assertThatThrownBy(() -> service.adminOverview(null, null, 1, 20))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.patchProgram(null, null))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("FORBIDDEN");
    assertThatThrownBy(() -> service.invite(refereePrincipal, new InviteCommand("  ")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");
    service.adminOverview(adminOps, "CONVERTED", 1, 20);

    service.patchProgram(adminSuper, new PatchProgramCommand(120, null, null, null, null));
    service.patchProgram(adminSuper, new PatchProgramCommand(null, 90, false, 180, "conds"));
    service.patchProgram(adminSuper, new PatchProgramCommand(null, null, true, null, null));
    service.patchProgram(adminSuper, null);
    assertThatThrownBy(
            () ->
                service.patchProgram(adminSuper, new PatchProgramCommand(100, 0, null, null, null)))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("VALIDATION_ERROR");

    referrals.adminReferrerName = "Priya";
    referrals.adminRefereeName = "Ankit";
    referrals.adminRefereePhone = "+91987";
    referrals.topReferrerName = "Priya";
    ReferralService.AdminOverviewResult named = service.adminOverview(adminOps, null, null, 150);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> topNamed =
        (List<Map<String, Object>>) named.data().get("top_referrers");
    assertThat(topNamed.getFirst().get("name")).isEqualTo("Priya");

    referrals.topReferrerName = null;
    ReferralService.AdminOverviewResult topNull = service.adminOverview(adminOps, null, 2, 20);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> topRows =
        (List<Map<String, Object>>) topNull.data().get("top_referrers");
    assertThat(topRows.getFirst().get("name")).isEqualTo("Customer");
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
            "https://nammamedmate.com/join/");
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
    profiles.saveProfile(freshSignup(CustomerTestFixtures.customer(blankNameReferee)));
    referrals.insert(new ReferralRecord(Ids.newId(), blankNameReferee, "MEDBLNK", 0, 0, 0L, NOW));
    profiles.saveProfile(CustomerTestFixtures.customerWithName(referrerId, "   "));
    Map<String, Object> blankApplied =
        service.applyCode(
            new MedmatePrincipal(blankNameReferee, AuthRole.CUSTOMER, null, TokenScope.FULL, "j"),
            new ApplyCommand("MEDRAM7"));
    assertThat(blankApplied.get("message").toString()).contains("your referrer");

    referrals.failNextInsertEvent = true;
    UUID other = Ids.newId();
    profiles.saveProfile(freshSignup(CustomerTestFixtures.customer(other)));
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
    profiles.saveProfile(freshSignup(CustomerTestFixtures.customer(pendingCancel)));
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

    referrals.failSettings = true;
    assertThat(
            service
                .getMyReferral(
                    new MedmatePrincipal(referrerId, AuthRole.CUSTOMER, null, TokenScope.FULL, "j"))
                .get("referral_code"))
        .isEqualTo("MEDRAM7");
    referrals.failSettings = false;

    service.adminOverview(adminOps, null, null, null);
    service.adminOverview(adminOps, null, 2, 150);
    service.adminOverview(adminOps, "PENDING", 0, 0);
    service.adminOverview(adminOps, "EXPIRED", 1, 5);
    service.adminOverview(adminOps, "REWARDED", 1, 5);
    service.patchProgram(adminSuper, new PatchProgramCommand(null, null, null, null, null));

    // 1 get already used above (failSettings); INFO_LIMIT=30 → 29 more then rate-limited
    MedmatePrincipal refP =
        new MedmatePrincipal(referrerId, AuthRole.CUSTOMER, null, TokenScope.FULL, "j");
    for (int i = 0; i < 29; i++) {
      service.getMyReferral(refP);
    }
    assertThatThrownBy(() -> service.getMyReferral(refP))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("RATE_LIMITED");
  }

  @Test
  void applyCode_rejectsAfterSignupWindow() {
    UUID stale = Ids.newId();
    profiles.saveProfile(CustomerTestFixtures.customer(stale));
    referrals.insert(new ReferralRecord(Ids.newId(), stale, "MEDSTALE", 0, 0, 0L, NOW));
    assertThatThrownBy(
            () ->
                service.applyCode(
                    new MedmatePrincipal(stale, AuthRole.CUSTOMER, null, TokenScope.FULL, "j"),
                    new ApplyCommand("MEDRAM7")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("REFERRAL_SIGNUP_ONLY");
  }

  @Test
  void applyCode_rejectsMissingCustomerAndNullCreatedAt() {
    UUID ghost = Ids.newId();
    assertThatThrownBy(
            () ->
                service.applyCode(
                    new MedmatePrincipal(ghost, AuthRole.CUSTOMER, null, TokenScope.FULL, "j"),
                    new ApplyCommand("MEDRAM7")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("CUSTOMER_NOT_FOUND");

    UUID noCreated = Ids.newId();
    var base = CustomerTestFixtures.customer(noCreated);
    profiles.saveProfile(
        new com.nammamedmate.customer.application.port.out.CustomerProfileStore
            .CustomerProfileRecord(
            base.id(),
            base.phone(),
            base.name(),
            base.avatarUrl(),
            base.dateOfBirth(),
            base.gender(),
            base.preferredLanguage(),
            base.segment(),
            base.city(),
            base.isFlagged(),
            base.flagReason(),
            base.flagNote(),
            base.flaggedBy(),
            base.flaggedAt(),
            base.walletBalancePaise(),
            base.loyaltyPoints(),
            base.totalOrders(),
            base.totalLtvPaise(),
            base.cancelRate(),
            base.disputeCount(),
            base.lastOrderAt(),
            base.deletionRequestedAt(),
            base.deletionReason(),
            null,
            base.updatedAt(),
            base.deletedAt()));
    assertThatThrownBy(
            () ->
                service.applyCode(
                    new MedmatePrincipal(noCreated, AuthRole.CUSTOMER, null, TokenScope.FULL, "j"),
                    new ApplyCommand("MEDRAM7")))
        .extracting(e -> ((AppException) e).code())
        .isEqualTo("REFERRAL_SIGNUP_ONLY");
  }

  private static com.nammamedmate.customer.application.port.out.CustomerProfileStore
          .CustomerProfileRecord
      freshSignup(
          com.nammamedmate.customer.application.port.out.CustomerProfileStore.CustomerProfileRecord
              base) {
    return new com.nammamedmate.customer.application.port.out.CustomerProfileStore
        .CustomerProfileRecord(
        base.id(),
        base.phone(),
        base.name(),
        base.avatarUrl(),
        base.dateOfBirth(),
        base.gender(),
        base.preferredLanguage(),
        base.segment(),
        base.city(),
        base.isFlagged(),
        base.flagReason(),
        base.flagNote(),
        base.flaggedBy(),
        base.flaggedAt(),
        base.walletBalancePaise(),
        base.loyaltyPoints(),
        base.totalOrders(),
        base.totalLtvPaise(),
        base.cancelRate(),
        base.disputeCount(),
        base.lastOrderAt(),
        base.deletionRequestedAt(),
        base.deletionReason(),
        NOW,
        base.updatedAt(),
        base.deletedAt());
  }
}
