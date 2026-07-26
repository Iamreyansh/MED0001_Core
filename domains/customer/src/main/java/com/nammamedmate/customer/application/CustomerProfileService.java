package com.nammamedmate.customer.application;

import com.nammamedmate.customer.application.port.out.ActiveOrdersPort;
import com.nammamedmate.customer.application.port.out.CustomerProfileStore;
import com.nammamedmate.customer.application.port.out.CustomerProfileStore.CustomerProfileRecord;
import com.nammamedmate.customer.application.port.out.LoyaltyStore;
import com.nammamedmate.customer.application.port.out.LoyaltyStore.LoyaltyRecord;
import com.nammamedmate.customer.domain.CustomerGender;
import com.nammamedmate.customer.domain.LoyaltyTiers;
import com.nammamedmate.customer.domain.PreferredLanguages;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.kernel.storage.StorageObjectKeys;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerProfileService {

  private static final int GET_LIMIT = 60;
  private static final int PATCH_LIMIT = 20;
  private static final int DELETE_LIMIT = 3;
  private static final int DAY = 86_400;
  private static final int MINUTE = 60;
  private static final int DELETION_GRACE_DAYS = 30;
  private static final int MIN_AGE_YEARS = 13;

  private final CustomerProfileStore store;
  private final ActiveOrdersPort activeOrders;
  private final LoyaltyStore loyalty;
  private final RateLimiter rateLimiter;
  private final Clock clock;
  private final String avatarUrlPrefix;

  public CustomerProfileService(
      CustomerProfileStore store,
      ActiveOrdersPort activeOrders,
      LoyaltyStore loyalty,
      RateLimiter rateLimiter,
      Clock clock,
      @Value("${medmate.cdn.base-url:https://cdn.nammamedmate.com}") String cdnBaseUrl) {
    this.store = store;
    this.activeOrders = activeOrders;
    this.loyalty = loyalty;
    this.rateLimiter = rateLimiter;
    this.clock = clock;
    String base = cdnBaseUrl.endsWith("/") ? cdnBaseUrl.substring(0, cdnBaseUrl.length() - 1) : cdnBaseUrl;
    this.avatarUrlPrefix = base + "/" + StorageObjectKeys.AVATARS + "/";
  }

  @Transactional(readOnly = true)
  public Map<String, Object> getMe(MedmatePrincipal principal) {
    CustomerProfileRecord c = requireCustomer(principal);
    rateLimit("customer:me:get:" + c.id(), GET_LIMIT, MINUTE);
    return toMeView(c, loyalty.findByCustomerId(c.id()).orElse(null));
  }

  @Transactional
  public Map<String, Object> updateMe(MedmatePrincipal principal, UpdateProfileCommand cmd) {
    CustomerProfileRecord c = requireCustomer(principal);
    rateLimit("customer:me:patch:" + c.id(), PATCH_LIMIT, MINUTE);
    if (cmd == null) {
      throw new AppException("VALIDATION_ERROR", "Request body is required", 400);
    }

    String name = c.name();
    if (cmd.name() != null) {
      String trimmed = cmd.name().trim();
      if (trimmed.isEmpty() || trimmed.length() > 100) {
        throw new AppException("VALIDATION_ERROR", "name must be 1-100 characters", 400);
      }
      name = trimmed;
    }

    String avatarUrl = c.avatarUrl();
    if (cmd.avatarUrl() != null) {
      String url = cmd.avatarUrl().trim();
      if (url.isEmpty()) {
        avatarUrl = null;
      } else {
        if (url.length() > 512 || !isAllowedAvatarUrl(url)) {
          throw new AppException(
              "VALIDATION_ERROR",
              "avatar_url must be an HTTPS URL under cdn.nammamedmate.com/avatars/ (max 512)",
              400);
        }
        avatarUrl = url;
      }
    }

    LocalDate dob = c.dateOfBirth();
    if (cmd.dateOfBirth() != null) {
      dob = parseDob(cmd.dateOfBirth());
    }

    String gender = c.gender();
    if (cmd.gender() != null) {
      try {
        CustomerGender parsed = CustomerGender.parse(cmd.gender());
        if (parsed == null) {
          throw new AppException("VALIDATION_ERROR", "gender is required when provided", 400);
        }
        gender = parsed.name();
      } catch (IllegalArgumentException ex) {
        throw new AppException("VALIDATION_ERROR", ex.getMessage(), 400);
      }
    }

    String language = c.preferredLanguage();
    if (cmd.preferredLanguage() != null) {
      if (!PreferredLanguages.isAllowed(cmd.preferredLanguage())) {
        throw new AppException(
            "VALIDATION_ERROR",
            "preferred_language must be one of: en, kn, hi, ta, te, ml, mr",
            400);
      }
      language = PreferredLanguages.normalize(cmd.preferredLanguage());
    }

    Instant now = clock.instant();
    CustomerProfileRecord updated =
        new CustomerProfileRecord(
            c.id(),
            c.phone(),
            name,
            avatarUrl,
            dob,
            gender,
            language,
            c.segment(),
            c.city(),
            c.isFlagged(),
            c.flagReason(),
            c.flagNote(),
            c.flaggedBy(),
            c.flaggedAt(),
            c.walletBalancePaise(),
            c.loyaltyPoints(),
            c.totalOrders(),
            c.totalLtvPaise(),
            c.cancelRate(),
            c.disputeCount(),
            c.lastOrderAt(),
            c.deletionRequestedAt(),
            c.deletionReason(),
            c.createdAt(),
            now,
            c.deletedAt());
    CustomerProfileRecord saved = store.saveProfile(updated);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", saved.id());
    data.put("phone", saved.phone());
    data.put("name", saved.name());
    data.put("avatar_url", saved.avatarUrl());
    data.put("date_of_birth", saved.dateOfBirth());
    data.put("gender", saved.gender());
    data.put("preferred_language", saved.preferredLanguage());
    data.put("updated_at", saved.updatedAt());
    return data;
  }

  @Transactional
  public Map<String, Object> requestDeletion(MedmatePrincipal principal, String reason) {
    CustomerProfileRecord c = requireCustomer(principal);
    rateLimit("customer:me:delete:" + c.id(), DELETE_LIMIT, DAY);
    if (c.deletionRequestedAt() != null) {
      throw new AppException(
          "DELETION_ALREADY_REQUESTED", "Account deletion is already pending", 409);
    }
    if (activeOrders.hasActiveOrders(c.id())) {
      throw new AppException(
          "ACTIVE_ORDERS_EXIST",
          "Cannot delete account while orders are PENDING, CONFIRMED, or OUT_FOR_DELIVERY",
          409);
    }
    if (reason != null && reason.length() > 500) {
      throw new AppException("VALIDATION_ERROR", "reason max length is 500", 400);
    }
    Instant now = clock.instant();
    Instant scheduled = now.plus(DELETION_GRACE_DAYS, ChronoUnit.DAYS);
    store.requestDeletion(c.id(), now, reason == null || reason.isBlank() ? null : reason.trim());
    Map<String, Object> data = new LinkedHashMap<>();
    data.put(
        "message",
        "Account deletion requested. Your account will be permanently deleted on "
            + scheduled
            + " unless you cancel this request.");
    data.put("deletion_scheduled_at", scheduled);
    data.put("cancel_before", scheduled);
    return data;
  }

  @Transactional
  public Map<String, Object> cancelDeletion(MedmatePrincipal principal) {
    CustomerProfileRecord c = requireCustomer(principal);
    if (c.deletionRequestedAt() == null) {
      throw new AppException("VALIDATION_ERROR", "No deletion request to cancel", 400);
    }
    store.cancelDeletion(c.id());
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", c.id());
    data.put("deletion_requested_at", null);
    data.put("message", "Account deletion request cancelled");
    return data;
  }

  private CustomerProfileRecord requireCustomer(MedmatePrincipal principal) {
    if (principal == null || principal.role() != AuthRole.CUSTOMER) {
      throw new AppException("UNAUTHORIZED", "Customer authentication required", 401);
    }
    return store
        .findById(principal.subject())
        .filter(c -> c.deletedAt() == null)
        .orElseThrow(() -> new AppException("CUSTOMER_NOT_FOUND", "Customer not found", 404));
  }

  private void rateLimit(String key, int limit, int windowSeconds) {
    if (!rateLimiter.tryAcquire(key, limit, windowSeconds)) {
      int retry = rateLimiter.secondsUntilAvailable(key, limit, windowSeconds);
      throw new AppException("RATE_LIMITED", "Too many requests", 429, Math.max(retry, 1));
    }
  }

  private LocalDate parseDob(String raw) {
    LocalDate dob;
    try {
      dob = LocalDate.parse(raw);
    } catch (Exception ex) {
      throw new AppException("VALIDATION_ERROR", "date_of_birth must be YYYY-MM-DD", 400);
    }
    LocalDate today = LocalDate.ofInstant(clock.instant(), clock.getZone());
    if (!dob.isBefore(today)) {
      throw new AppException("VALIDATION_ERROR", "date_of_birth must be in the past", 400);
    }
    if (Period.between(dob, today).getYears() < MIN_AGE_YEARS) {
      throw new AppException("VALIDATION_ERROR", "Customer must be at least 13 years old", 400);
    }
    return dob;
  }

  static Map<String, Object> toMeView(CustomerProfileRecord c) {
    return toMeView(c, null);
  }

  static Map<String, Object> toMeView(CustomerProfileRecord c, LoyaltyRecord loyalty) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("id", c.id());
    data.put("phone", c.phone());
    data.put("name", c.name());
    data.put("avatar_url", c.avatarUrl());
    data.put("date_of_birth", c.dateOfBirth());
    data.put("gender", c.gender());
    data.put("preferred_language", c.preferredLanguage());
    data.put("segment", c.segment());
    data.put("is_flagged", c.isFlagged());
    data.put("wallet_balance", paiseToRupees(c.walletBalancePaise()));
    int points = loyalty != null ? loyalty.pointsBalance() : c.loyaltyPoints();
    String tier =
        loyalty != null ? loyalty.tier() : LoyaltyTiers.fromLifetimePoints(c.loyaltyPoints());
    data.put("loyalty_points", points);
    data.put("loyalty_tier", tier);
    data.put("total_orders", c.totalOrders());
    data.put("created_at", c.createdAt());
    return data;
  }

  static BigDecimal paiseToRupees(long paise) {
    return BigDecimal.valueOf(paise).movePointLeft(2);
  }

  boolean isAllowedAvatarUrl(String url) {
    return url.startsWith(avatarUrlPrefix);
  }

  public record UpdateProfileCommand(
      String name, String avatarUrl, String dateOfBirth, String gender, String preferredLanguage) {}
}
