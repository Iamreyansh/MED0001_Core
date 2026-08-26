package com.nammamedmate.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nammamedmate.kernel.ratelimit.InMemoryRateLimiter;
import com.nammamedmate.kernel.ratelimit.RateLimiter;
import com.nammamedmate.security.AesGcmCipher;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class WorkerPlatformConfigTest {

  private static final String LOCAL_ONLY_MFA_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
  private static final String REAL_KEY = "QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI=";

  @Test
  void providesClockAndInMemoryRateLimiter() {
    WorkerPlatformConfig config = new WorkerPlatformConfig();
    Clock clock = config.clock();
    RateLimiter limiter = config.rateLimiter(clock);
    assertThat(clock).isNotNull();
    assertThat(limiter).isInstanceOf(InMemoryRateLimiter.class);
  }

  @Test
  void localCiphersUseFallbackKeys() {
    WorkerPlatformConfig config = new WorkerPlatformConfig();
    assertThat(config.localAesGcmCipher(LOCAL_ONLY_MFA_KEY)).isNotNull();
    assertThat(config.localPaymentMethodCipher("", LOCAL_ONLY_MFA_KEY)).isNotNull();
    assertThat(config.localBankAccountCipher("", "", LOCAL_ONLY_MFA_KEY)).isNotNull();
    assertThat(config.localPaymentMethodCipher(REAL_KEY, LOCAL_ONLY_MFA_KEY)).isNotNull();
    assertThat(config.localBankAccountCipher(REAL_KEY, "", LOCAL_ONLY_MFA_KEY)).isNotNull();
    assertThat(config.localBankAccountCipher("", REAL_KEY, LOCAL_ONLY_MFA_KEY)).isNotNull();
  }

  @Test
  void deployedCiphersRejectBlankOrLocalDefault() {
    WorkerPlatformConfig config = new WorkerPlatformConfig();
    assertThatThrownBy(() -> config.deployedAesGcmCipher(" "))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> config.deployedAesGcmCipher(LOCAL_ONLY_MFA_KEY))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> config.deployedPaymentMethodCipher("", LOCAL_ONLY_MFA_KEY))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> config.deployedBankAccountCipher("", "", LOCAL_ONLY_MFA_KEY))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void deployedCiphersAcceptRealKey() {
    WorkerPlatformConfig config = new WorkerPlatformConfig();
    AesGcmCipher primary = config.deployedAesGcmCipher(REAL_KEY);
    assertThat(primary).isNotNull();
    assertThat(config.deployedPaymentMethodCipher("", REAL_KEY)).isNotNull();
    assertThat(config.deployedPaymentMethodCipher(REAL_KEY, LOCAL_ONLY_MFA_KEY)).isNotNull();
    assertThat(config.deployedBankAccountCipher("", "", REAL_KEY)).isNotNull();
    assertThat(config.deployedBankAccountCipher(REAL_KEY, "", LOCAL_ONLY_MFA_KEY)).isNotNull();
    assertThat(config.deployedBankAccountCipher("", REAL_KEY, LOCAL_ONLY_MFA_KEY)).isNotNull();
  }
}
