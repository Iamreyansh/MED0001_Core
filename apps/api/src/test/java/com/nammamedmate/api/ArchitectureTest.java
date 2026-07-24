package com.nammamedmate.api;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.nammamedmate", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  static final ArchRule platformDoesNotDependOnDomains =
      noClasses()
          .that()
          .resideInAPackage("com.nammamedmate.kernel..")
          .or()
          .resideInAPackage("com.nammamedmate.security..")
          .or()
          .resideInAPackage("com.nammamedmate.persistence..")
          .or()
          .resideInAPackage("com.nammamedmate.messaging..")
          .or()
          .resideInAPackage("com.nammamedmate.observability..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.nammamedmate.auth..",
              "com.nammamedmate.order..",
              "com.nammamedmate.payment..",
              "com.nammamedmate.customer..")
          .allowEmptyShould(true);

  @ArchTest
  static final ArchRule webAdaptersDoNotUseJpaRepos =
      noClasses()
          .that()
          .resideInAPackage("..adapter.in.web..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..adapter.out.persistence..")
          .allowEmptyShould(true);
}
