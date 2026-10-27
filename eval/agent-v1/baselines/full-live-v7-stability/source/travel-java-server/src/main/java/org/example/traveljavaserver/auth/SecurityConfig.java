package org.example.traveljavaserver.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.example.traveljavaserver.vo.Result;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.*;

@Configuration
public class SecurityConfig {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(10);
  }

  @Bean
  org.springframework.security.core.userdetails.UserDetailsService userDetails(
      org.example.traveljavaserver.persistence.StoreMapper store) {
    return username -> {
      var user = store.user(username.toLowerCase(java.util.Locale.ROOT));
      if (user == null)
        throw new org.springframework.security.core.userdetails.UsernameNotFoundException(
            "Unknown user");
      return org.springframework.security.core.userdetails.User.withUsername(user.getUsername())
          .password(user.getPasswordHash())
          .roles("USER")
          .build();
    };
  }

  @Bean
  SecurityContextRepository contextRepository() {
    return new HttpSessionSecurityContextRepository();
  }

  @Bean
  CsrfTokenRepository csrfRepository() {
    return new HttpSessionCsrfTokenRepository();
  }

  @Bean
  SecurityFilterChain security(
      HttpSecurity http,
      SecurityContextRepository contexts,
      CsrfTokenRepository csrf,
      org.example.traveljavaserver.persistence.StoreMapper store,
      ActiveRequestCleanup activities)
      throws Exception {
    http.addFilterAfter(
            new CredentialVersionFilter(store),
            org.springframework.security.web.context.SecurityContextHolderFilter.class)
        .securityContext(c -> c.securityContextRepository(contexts).requireExplicitSave(true))
        .csrf(c -> c.csrfTokenRepository(csrf))
        .requestCache(c -> c.disable())
        .formLogin(c -> c.disable())
        .httpBasic(c -> c.disable())
        .authorizeHttpRequests(
            c ->
                c.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers(
                        "/api/auth/csrf",
                        "/api/auth/register",
                        "/api/auth/login",
                        "/api/travel/hello",
                        "/api/health")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            c ->
                c.authenticationEntryPoint(
                        (req, res, error) -> writeError(res, 401, "AUTH_REQUIRED", "请先登录"))
                    .accessDeniedHandler(
                        (req, res, error) ->
                            writeError(
                                res,
                                403,
                                error instanceof CsrfException ? "CSRF_INVALID" : "FORBIDDEN",
                                error instanceof CsrfException ? "请求凭证已失效，请刷新后重试" : "无权执行此操作")))
        .logout(
            c ->
                c.logoutUrl("/api/auth/logout")
                    .invalidateHttpSession(true)
                    .clearAuthentication(true)
                    .deleteCookies("JSESSIONID")
                    .addLogoutHandler(
                        (req, res, auth) -> {
                          if (auth != null && auth.getPrincipal() instanceof UserPrincipal user) {
                            activities.cancelUser(user.id());
                          }
                        })
                    .logoutSuccessHandler(
                        (req, res, auth) -> {
                          res.setContentType("application/json;charset=UTF-8");
                          res.getWriter().write("{\"success\":true,\"code\":200,\"data\":null}");
                        }));
    return http.build();
  }

  private static void writeError(
      HttpServletResponse response, int status, String code, String message) throws IOException {
    response.setStatus(status);
    response.setContentType("application/json;charset=UTF-8");
    Result<Void> result = Result.fail(status, message);
    result.setErrorCode(code);
    response.getWriter().write(new ObjectMapper().writeValueAsString(result));
  }
}
