package com.nammamedmate.crm.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nammamedmate.crm.application.port.out.CrmPlanLookupPort;
import com.nammamedmate.kernel.error.AppException;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class CrmModuleAccessFilterTest {

  @Mock CrmPlanLookupPort lookup;
  @Mock FilterChain chain;

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void resolveModule() {
    assertThat(CrmModuleAccessFilter.resolveModule(null)).isNull();
    assertThat(CrmModuleAccessFilter.resolveModule("/api/v1/pharmacy/profile")).isNull();
    assertThat(CrmModuleAccessFilter.resolveModule("/api/v1/pharmacy/offers/list"))
        .isEqualTo("mod_offers");
  }

  @Test
  void disabledSkipsLookup() throws Exception {
    CrmModuleAccessFilter filter = new CrmModuleAccessFilter(lookup, false);
    MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/pharmacy/offers");
    MockHttpServletResponse res = new MockHttpServletResponse();
    filter.doFilter(req, res, chain);
    verify(chain).doFilter(any(HttpServletRequest.class), any(HttpServletResponse.class));
    verifyNoInteractions(lookup);
  }

  @Test
  void enforceAllowsWhenIncluded() throws Exception {
    UUID pharmacyId = Ids.newId();
    MedmatePrincipal owner =
        new MedmatePrincipal(
            Ids.newId(), AuthRole.PHARMACY_OWNER, pharmacyId, TokenScope.FULL, "j");
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(owner, null));
    when(lookup.moduleAccessibleForPharmacy(pharmacyId, "mod_offers")).thenReturn(true);

    CrmModuleAccessFilter filter = new CrmModuleAccessFilter(lookup, true);
    filter.doFilter(
        new MockHttpServletRequest("GET", "/api/v1/pharmacy/offers"),
        new MockHttpServletResponse(),
        chain);
    verify(chain).doFilter(any(HttpServletRequest.class), any(HttpServletResponse.class));
  }

  @Test
  void enforceBlocksWhenMissing() throws Exception {
    UUID pharmacyId = Ids.newId();
    MedmatePrincipal owner =
        new MedmatePrincipal(
            Ids.newId(), AuthRole.PHARMACY_OWNER, pharmacyId, TokenScope.FULL, "j");
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(owner, null));
    when(lookup.moduleAccessibleForPharmacy(pharmacyId, "mod_offers")).thenReturn(false);

    CrmModuleAccessFilter filter = new CrmModuleAccessFilter(lookup, true);
    MockHttpServletResponse res = new MockHttpServletResponse();
    filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/pharmacy/offers"), res, chain);
    assertThat(res.getStatus()).isEqualTo(403);
    assertThat(res.getContentAsString()).contains("MODULE_NOT_IN_PLAN");
    verifyNoInteractions(chain);
  }

  @Test
  void enforceLookupFailureIsPlanLockNot500() throws Exception {
    UUID pharmacyId = Ids.newId();
    MedmatePrincipal owner =
        new MedmatePrincipal(
            Ids.newId(), AuthRole.PHARMACY_OWNER, pharmacyId, TokenScope.FULL, "j");
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(owner, null));
    when(lookup.moduleAccessibleForPharmacy(pharmacyId, "mod_offers"))
        .thenThrow(new AppException("DB", "down", 500));

    CrmModuleAccessFilter filter = new CrmModuleAccessFilter(lookup, true);
    MockHttpServletResponse res = new MockHttpServletResponse();
    filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/pharmacy/offers"), res, chain);
    assertThat(res.getStatus()).isEqualTo(403);
    assertThat(res.getContentAsString()).contains("MODULE_NOT_IN_PLAN");
  }

  @Test
  void enforceNoPharmacyPassesThrough() throws Exception {
    CrmModuleAccessFilter filter = new CrmModuleAccessFilter(lookup, true);
    filter.doFilter(
        new MockHttpServletRequest("GET", "/api/v1/pharmacy/offers"),
        new MockHttpServletResponse(),
        chain);
    verify(chain).doFilter(any(HttpServletRequest.class), any(HttpServletResponse.class));
    verifyNoInteractions(lookup);

    filter.doFilter(
        new MockHttpServletRequest("GET", "/api/v1/pharmacy/profile"),
        new MockHttpServletResponse(),
        chain);
  }

  @Test
  void enforcePrincipalWithoutPharmacyAndNonMedmateAuth() throws Exception {
    MedmatePrincipal noPharmacy =
        new MedmatePrincipal(Ids.newId(), AuthRole.PHARMACY_OWNER, null, TokenScope.FULL, "j");
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(noPharmacy, null));
    CrmModuleAccessFilter filter = new CrmModuleAccessFilter(lookup, true);
    filter.doFilter(
        new MockHttpServletRequest("GET", "/api/v1/pharmacy/offers"),
        new MockHttpServletResponse(),
        chain);
    verifyNoInteractions(lookup);

    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("anonymous", null));
    filter.doFilter(
        new MockHttpServletRequest("GET", "/api/v1/pharmacy/khata"),
        new MockHttpServletResponse(),
        chain);
  }
}
