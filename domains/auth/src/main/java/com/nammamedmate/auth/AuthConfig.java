package com.nammamedmate.auth;

import com.nammamedmate.auth.adapter.in.web.RequiresPermissionInterceptor;
import com.nammamedmate.auth.application.port.out.RiderAccountPort;
import com.nammamedmate.auth.application.port.out.RiderAccountPort.RiderAccount;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AuthConfig implements WebMvcConfigurer {

  private final RequiresPermissionInterceptor requiresPermissionInterceptor;

  public AuthConfig(RequiresPermissionInterceptor requiresPermissionInterceptor) {
    this.requiresPermissionInterceptor = requiresPermissionInterceptor;
  }

  @Bean
  @ConditionalOnMissingBean(RiderAccountPort.class)
  RiderAccountPort stubRiderAccountPort() {
    return new RiderAccountPort() {
      @Override
      public Optional<RiderAccount> findByPhone(String phone) {
        return Optional.empty();
      }

      @Override
      public Optional<RiderAccount> findById(UUID id) {
        return Optional.empty();
      }
    };
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(10);
  }

  @Bean
  @Qualifier("staffPasswordEncoder")
  PasswordEncoder staffPasswordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(requiresPermissionInterceptor);
  }
}
