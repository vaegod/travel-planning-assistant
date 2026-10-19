package org.example.traveljavaserver.controller;

import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.example.traveljavaserver.auth.*;
import org.example.traveljavaserver.vo.Result;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
  private final AuthService authService;
  private final SecurityContextRepository contextRepository;
  private final CsrfTokenRepository csrfRepository;
  private final ActiveRequestCleanup activities;

  public record Credentials(
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_]{3,32}", message = "用户名为3至32位字母、数字或下划线")
          String username,
      @NotBlank @Size(min = 8, max = 64, message = "密码长度为8至64个字符") String password) {}

  // Login accepts existing credentials; registration and new-password rules remain stricter.
  public record LoginCredentials(
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_]{1,32}", message = "用户名为1至32位字母、数字或下划线")
          String username,
      @NotBlank @Size(max = 64, message = "密码最多64个字符") String password) {}

  public record Rename(
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_]{3,32}", message = "用户名为3至32位字母、数字或下划线")
          String username,
      @NotBlank @Size(max = 64, message = "当前密码最多64个字符") String currentPassword) {}

  public record PasswordChange(
      @NotBlank @Size(max = 64, message = "当前密码最多64个字符") String currentPassword,
      @NotBlank @Size(min = 8, max = 64, message = "新密码长度为8至64个字符") String newPassword,
      @NotBlank @Size(min = 8, max = 64, message = "确认密码长度为8至64个字符") String confirmPassword) {}

  @PatchMapping("/username")
  public Result<Void> rename(
      Authentication authentication,
      @Valid @RequestBody Rename value,
      HttpServletRequest request,
      HttpServletResponse response) {
    var actor = CurrentUser.require(authentication);
    authService.rename(actor, value.currentPassword(), value.username());
    signedOut(actor, request, response);
    return Result.ok();
  }

  @PatchMapping("/password")
  public Result<Void> password(
      Authentication authentication,
      @Valid @RequestBody PasswordChange value,
      HttpServletRequest request,
      HttpServletResponse response) {
    var actor = CurrentUser.require(authentication);
    authService.changePassword(
        actor, value.currentPassword(), value.newPassword(), value.confirmPassword());
    signedOut(actor, request, response);
    return Result.ok();
  }

  private void signedOut(
      UserPrincipal actor, HttpServletRequest request, HttpServletResponse response) {
    try {
      activities.cancelUser(actor.id());
    } finally {
      new org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler()
          .logout(request, response, SecurityContextHolder.getContext().getAuthentication());
    }
  }

  @GetMapping("/csrf")
  public Result<Map<String, String>> csrf(CsrfToken token) {
    return Result.ok(Map.of("token", token.getToken(), "headerName", token.getHeaderName()));
  }

  @PostMapping("/register")
  public Result<UserPrincipal> register(@Valid @RequestBody Credentials credentials) {
    return Result.ok(authService.register(credentials.username(), credentials.password()));
  }

  @PostMapping("/login")
  public Result<UserPrincipal> login(
      @Valid @RequestBody LoginCredentials credentials,
      HttpServletRequest request,
      HttpServletResponse response) {
    UserPrincipal user = authService.authenticate(credentials.username(), credentials.password());
    Authentication authentication =
        UsernamePasswordAuthenticationToken.authenticated(
            user, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    new ChangeSessionIdAuthenticationStrategy().onAuthentication(authentication, request, response);
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(authentication);
    SecurityContextHolder.setContext(context);
    contextRepository.saveContext(context, request, response);
    csrfRepository.saveToken(null, request, response);
    return Result.ok(user);
  }

  @GetMapping("/me")
  public Result<UserPrincipal> me(Authentication authentication) {
    return Result.ok(CurrentUser.require(authentication));
  }
}
