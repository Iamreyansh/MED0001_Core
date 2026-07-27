package com.nammamedmate.pharmacy;

import com.nammamedmate.pharmacy.adapter.out.kyc.StubDrugLicenceVerificationClient;
import com.nammamedmate.pharmacy.adapter.out.kyc.StubFssaiVerificationClient;
import com.nammamedmate.pharmacy.adapter.out.kyc.StubGstinVerificationClient;
import com.nammamedmate.pharmacy.application.port.out.KycGovernmentApiPort.DrugLicenceVerificationPort;
import com.nammamedmate.pharmacy.application.port.out.KycGovernmentApiPort.FssaiVerificationPort;
import com.nammamedmate.pharmacy.application.port.out.KycGovernmentApiPort.GstinVerificationPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PharmacyConfig {

  @Bean
  @ConditionalOnMissingBean(GstinVerificationPort.class)
  GstinVerificationPort gstinVerificationPort() {
    return new StubGstinVerificationClient();
  }

  @Bean
  @ConditionalOnMissingBean(DrugLicenceVerificationPort.class)
  DrugLicenceVerificationPort drugLicenceVerificationPort() {
    return new StubDrugLicenceVerificationClient();
  }

  @Bean
  @ConditionalOnMissingBean(FssaiVerificationPort.class)
  FssaiVerificationPort fssaiVerificationPort() {
    return new StubFssaiVerificationClient();
  }
}
