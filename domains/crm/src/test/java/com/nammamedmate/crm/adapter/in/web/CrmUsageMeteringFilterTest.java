package com.nammamedmate.crm.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.nammamedmate.crm.application.port.out.ModuleUsageMeterPort;
import com.nammamedmate.kernel.id.Ids;
import com.nammamedmate.security.AuthRole;
import com.nammamedmate.security.MedmatePrincipal;
import com.nammamedmate.security.TokenScope;
import jakarta.servlet.FilterChain;
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
class CrmUsageMeteringFilterTest {

  @Mock ModuleUsageMeterPort meter;
  @Mock FilterChain chain;

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void recordsAfterRequestAndSwallowsErrors() throws Exception {
    UUID pharmacyId = Ids.newId();
    MedmatePrincipal owner =
        new MedmatePrincipal(
            Ids.newId(), AuthRole.PHARMACY_OWNER, pharmacyId, TokenScope.FULL, "j");
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(owner, null));

    CrmUsageMeteringFilter filter = new CrmUsageMeteringFilter(meter);
    filter.doFilter(
        new MockHttpServletRequest("GET", "/api/v1/pharmacy/offers"),
        new MockHttpServletResponse(),
        chain);
    verify(chain).doFilter(any(), any());
    verify(meter).recordUsage(pharmacyId, "mod_offers");

    doThrow(new RuntimeException("x")).when(meter).recordUsage(any(), any());
    filter.doFilter(
        new MockHttpServletRequest("GET", "/api/v1/pharmacy/khata"),
        new MockHttpServletResponse(),
        chain);
  }

  @Test
  void skipsWhenNoModuleOrPrincipal() throws Exception {
    CrmUsageMeteringFilter filter = new CrmUsageMeteringFilter(meter);
    filter.doFilter(
        new MockHttpServletRequest("GET", "/api/v1/pharmacy/profile"),
        new MockHttpServletResponse(),
        chain);
    verifyNoInteractions(meter);

    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("anon", null));
    filter.doFilter(
        new MockHttpServletRequest("GET", "/api/v1/pharmacy/offers"),
        new MockHttpServletResponse(),
        chain);
    verify(meter, never()).recordUsage(any(), eq("mod_offers"));

    MedmatePrincipal noPharmacy =
        new MedmatePrincipal(Ids.newId(), AuthRole.PHARMACY_OWNER, null, TokenScope.FULL, "j");
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(noPharmacy, null));
    filter.doFilter(
        new MockHttpServletRequest("GET", "/api/v1/pharmacy/offers"),
        new MockHttpServletResponse(),
        chain);
    verify(meter, never()).recordUsage(any(), eq("mod_offers"));

    SecurityContextHolder.clearContext();
    filter.doFilter(
        new MockHttpServletRequest("GET", "/api/v1/pharmacy/pos"),
        new MockHttpServletResponse(),
        chain);
    verify(meter, never()).recordUsage(any(), eq("mod_billing"));
  }
}
