package org.example.traveljavaserver.auth;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.example.traveljavaserver.persistence.StoreMapper;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Session principals are snapshots; credential edits revoke every older snapshot. */
public final class CredentialVersionFilter extends OncePerRequestFilter {
  private final StoreMapper store;

  public CredentialVersionFilter(StoreMapper store) {
    this.store = store;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal actor) {
      Long version = store.credentialVersion(actor.id());
      if (version == null || version.longValue() != actor.credentialVersion()) {
        SecurityContextHolder.clearContext();
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        response
            .getWriter()
            .write(
                "{\"success\":false,\"code\":401,\"errorCode\":\"AUTH_REQUIRED\",\"message\":\"账号信息已变更，请重新登录\"}");
        return;
      }
    }
    chain.doFilter(request, response);
  }
}
