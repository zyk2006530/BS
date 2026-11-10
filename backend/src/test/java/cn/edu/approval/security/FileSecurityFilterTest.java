package cn.edu.approval.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.edu.approval.identity.AuthPrincipal;
import cn.edu.approval.identity.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class FileSecurityFilterTest {
  private static final String TOKEN = "A".repeat(43);

  @Test
  void uploadDoesNotBufferMultipartAndRequiresActiveIdentity() throws Exception {
    var service = mock(AuthService.class);
    when(service.authenticate(TOKEN, "MINI_PROGRAM"))
        .thenReturn(new AuthPrincipal("1", "2", "ACTIVE", false, "MINI_PROGRAM"));
    var filter = filter(service);
    var request = upload();
    request.addHeader("Authorization", "Bearer " + TOKEN);
    var response = new MockHttpServletResponse();
    var called = new AtomicBoolean();
    filter.doFilter(
        request,
        response,
        (req, res) -> {
          called.set(true);
          assertThat(req).isSameAs(request);
          assertThat(req.getAttribute("identity")).isInstanceOf(AuthPrincipal.class);
          assertThat(req.getInputStream().read()).isEqualTo(42);
        });
    assertThat(called).isTrue();
  }

  @Test
  void uploadCannotBypassCookieOriginOrPendingAccountRestriction() throws Exception {
    var service = mock(AuthService.class);
    when(service.authenticate(TOKEN, "MINI_PROGRAM"))
        .thenReturn(new AuthPrincipal("1", "2", "PENDING", false, "MINI_PROGRAM"));
    for (boolean bearer : new boolean[] {false, true}) {
      var request = upload();
      if (bearer) request.addHeader("Authorization", "Bearer " + TOKEN);
      var response = new MockHttpServletResponse();
      var called = new AtomicBoolean();
      filter(service).doFilter(request, response, (req, res) -> called.set(true));
      assertThat(response.getStatus()).isEqualTo(403);
      assertThat(called).isFalse();
      assertThat(request.getInputStream().read()).isEqualTo(42);
    }
  }

  private static MockHttpServletRequest upload() {
    var request = new MockHttpServletRequest("POST", "/api/v1/files");
    request.setContentType("multipart/form-data; boundary=test");
    byte[] data = new byte[1024 * 1024 + 10];
    data[0] = 42;
    request.setContent(data);
    return request;
  }

  private static IdentityRequestFilter filter(AuthService service) {
    return new IdentityRequestFilter(
        service,
        new WebCredentials(
            "only-for-isolated-tests-32-byte-secret",
            "https://approval.example",
            true,
            Clock.systemUTC()),
        new ObjectMapper());
  }
}
