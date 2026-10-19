package org.example.traveljavaserver;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.example.traveljavaserver.auth.*;
import org.example.traveljavaserver.common.AppException;
import org.example.traveljavaserver.controller.AuthController;
import org.example.traveljavaserver.knowledge.RagQuestions;
import org.example.traveljavaserver.planning.PlanningJobs;
import org.example.traveljavaserver.service.TravelService;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;

class CredentialCleanupTests {
  final AuthService service = mock(AuthService.class);
  final TravelService travel = mock(TravelService.class);
  final PlanningJobs planning = mock(PlanningJobs.class);
  final RagQuestions knowledge = mock(RagQuestions.class);
  final org.springframework.beans.factory.support.StaticListableBeanFactory factory =
      new org.springframework.beans.factory.support.StaticListableBeanFactory(
          java.util.Map.of("travel", travel, "planning", planning, "knowledge", knowledge));
  final ActiveRequestCleanup cleanup =
      new ActiveRequestCleanup(
          factory.getBeanProvider(TravelService.class),
          factory.getBeanProvider(PlanningJobs.class),
          factory.getBeanProvider(RagQuestions.class));
  final AuthController controller =
      new AuthController(
          service, mock(SecurityContextRepository.class), mock(CsrfTokenRepository.class), cleanup);

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void cleanupFailureCannotTurnCommittedCredentialChangeIntoAnError() {
    var auth =
        UsernamePasswordAuthenticationToken.authenticated(
            new UserPrincipal(7, "sample", 0), null, java.util.List.of());
    SecurityContextHolder.getContext().setAuthentication(auth);
    var request = new MockHttpServletRequest();
    var session = (MockHttpSession) request.getSession();
    doThrow(new IllegalStateException("fixture cleanup failed")).when(knowledge).cancelUser(7);
    var response =
        assertDoesNotThrow(
            () ->
                controller.password(
                    auth,
                    new AuthController.PasswordChange(
                        "old-test-pass", "new-test-pass", "new-test-pass"),
                    request,
                    new MockHttpServletResponse()));
    assertTrue(response.getSuccess());
    assertTrue(session.isInvalid());
    assertNull(SecurityContextHolder.getContext().getAuthentication());
    verify(service)
        .changePassword(
            any(UserPrincipal.class),
            eq("old-test-pass"),
            eq("new-test-pass"),
            eq("new-test-pass"));
  }

  @Test
  void rejectedCredentialChangeDoesNotLogOutOrRunCleanup() {
    var auth =
        UsernamePasswordAuthenticationToken.authenticated(
            new UserPrincipal(7, "sample", 0), null, java.util.List.of());
    SecurityContextHolder.getContext().setAuthentication(auth);
    var request = new MockHttpServletRequest();
    var session = (MockHttpSession) request.getSession();
    doThrow(new AppException(HttpStatus.BAD_REQUEST, "CURRENT_PASSWORD_INVALID", "当前密码不正确"))
        .when(service)
        .changePassword(any(), anyString(), anyString(), anyString());
    assertThrows(
        AppException.class,
        () ->
            controller.password(
                auth,
                new AuthController.PasswordChange(
                    "old-test-pass", "new-test-pass", "new-test-pass"),
                request,
                new MockHttpServletResponse()));
    assertFalse(session.isInvalid());
    assertSame(auth, SecurityContextHolder.getContext().getAuthentication());
    verifyNoInteractions(travel, planning, knowledge);
  }

  @Test
  void oneActivityFailureDoesNotPreventOtherActivitiesFromBeingCancelled() {
    doThrow(new IllegalStateException("fixture cleanup failure")).when(travel).cancelUser(7);
    assertDoesNotThrow(() -> cleanup.cancelUser(7));
    verify(planning).cancelUser(7);
    verify(knowledge).cancelUser(7);
  }

  @Test
  void disabledOptionalActivitiesDoNotBreakLogout() {
    var empty = new org.springframework.beans.factory.support.StaticListableBeanFactory();
    var cleanup =
        new ActiveRequestCleanup(
            empty.getBeanProvider(TravelService.class),
            empty.getBeanProvider(PlanningJobs.class),
            empty.getBeanProvider(RagQuestions.class));
    assertDoesNotThrow(() -> cleanup.cancelUser(7));
  }
}
