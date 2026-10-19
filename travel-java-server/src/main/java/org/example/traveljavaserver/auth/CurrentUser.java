package org.example.traveljavaserver.auth;

import org.example.traveljavaserver.common.AppException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;

public final class CurrentUser {
  private CurrentUser() {}

  public static UserPrincipal require(Authentication authentication) {
    if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal user))
      throw new AppException(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "请先登录");
    return user;
  }
}
