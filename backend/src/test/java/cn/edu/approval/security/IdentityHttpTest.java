package cn.edu.approval.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.edu.approval.identity.AuthFailure;
import cn.edu.approval.identity.AuthPrincipal;
import cn.edu.approval.identity.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@WebMvcTest(
    controllers = IdentityController.class,
    properties = {
      "APP_CSRF_SECRET=only-for-isolated-tests-32-byte-secret",
      "APP_WEB_ORIGIN=https://approval.example"
    })
@Import({IdentitySecurityConfiguration.class, IdentityErrors.class})
@ActiveProfiles("db")
class IdentityHttpTest {
  private static final String TOKEN = "A".repeat(43);
  private static final String ORIGIN = "https://approval.example";
  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper mapper;
  @MockitoBean private AuthService service;

  @BeforeEach
  void setup() {
    when(service.login(any(), any(), any(), any(), any()))
        .thenReturn(
            Map.of(
                "token",
                TOKEN,
                "userId",
                "9007199254740993",
                "status",
                "PENDING",
                "mustChangePassword",
                false));
    when(service.authenticate(TOKEN, "MINI_PROGRAM"))
        .thenReturn(new AuthPrincipal("1", "2", "PENDING", false, "MINI_PROGRAM"));
    when(service.authenticate(TOKEN, "WEB"))
        .thenReturn(new AuthPrincipal("1", "2", "PENDING", false, "WEB"));
    when(service.profile(any())).thenReturn(Map.of("status", "PENDING", "rowVersion", "1"));
  }

  private MvcResult context(Cookie... cookies) throws Exception {
    var request = get("/api/v1/auth/csrf");
    if (cookies.length > 0) {
      request.cookie(cookies);
    }
    return mvc.perform(request)
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andReturn();
  }

  private String proof(MvcResult result) throws Exception {
    return mapper
        .readTree(result.getResponse().getContentAsString())
        .get("data")
        .get("csrfToken")
        .asText();
  }

  @Test
  void anonymousWebLoginAndRegistrationRequireOriginAndBoundProof() throws Exception {
    var context = context();
    Cookie cookie = context.getResponse().getCookie("csrf_ctx");
    for (String action : new String[] {"login", "register", "logout"}) {
      mvc.perform(
              post("/api/v1/auth/" + action).contentType(MediaType.APPLICATION_JSON).content("{}"))
          .andExpect(status().isForbidden());
      mvc.perform(
              post("/api/v1/auth/" + action)
                  .cookie(cookie)
                  .header("Origin", "https://evil.example")
                  .header("X-CSRF-Token", proof(context))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{}"))
          .andExpect(status().isForbidden());
      mvc.perform(
              post("/api/v1/auth/" + action)
                  .cookie(cookie)
                  .header("Origin", ORIGIN)
                  .header("X-CSRF-Token", "fake")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{}"))
          .andExpect(status().isForbidden());
    }
    verifyNoInteractions(service);
  }

  @Test
  void webLoginRotatesContextAndNeverReturnsSessionToken() throws Exception {
    var context = context();
    var response =
        mvc.perform(
                post("/api/v1/auth/login")
                    .cookie(context.getResponse().getCookie("csrf_ctx"))
                    .header("Origin", ORIGIN)
                    .header("X-CSRF-Token", proof(context))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"user\",\"password\":\"password\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").doesNotExist())
            .andExpect(jsonPath("$.data.csrfToken").isString())
            .andExpect(jsonPath("$.requestId").isString())
            .andExpect(jsonPath("$.serverTime").isString())
            .andReturn()
            .getResponse();
    assertThat(response.getCookie("sid").getValue()).isEqualTo(TOKEN);
    assertThat(response.getCookie("sid").isHttpOnly()).isTrue();
    assertThat(response.getCookie("sid").getSecure()).isTrue();
    assertThat(response.getCookie("sid").getPath()).isEqualTo("/");
    assertThat(response.getCookie("sid").getDomain()).isNull();
    assertThat(response.getHeaders("Set-Cookie").toString()).contains("SameSite=Lax");
    assertThat(response.getCookie("csrf_ctx").getValue())
        .isNotEqualTo(context.getResponse().getCookie("csrf_ctx").getValue());
    mvc.perform(
            post("/api/v1/auth/logout")
                .cookie(new Cookie("sid", TOKEN), context.getResponse().getCookie("csrf_ctx"))
                .header("Origin", ORIGIN)
                .header("X-CSRF-Token", proof(context)))
        .andExpect(status().isForbidden());
  }

  @Test
  void nativeAnonymousNeverReadsOrSetsCookieAndRejectsEvenUnrelatedCookie() throws Exception {
    mvc.perform(
            post("/api/v1/native-auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"user\",\"password\":\"password\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.token").value(TOKEN))
        .andExpect(header().doesNotExist("Set-Cookie"));
    for (String action : new String[] {"login", "register"}) {
      mvc.perform(
              post("/api/v1/native-auth/" + action)
                  .cookie(new Cookie("tracking", "1"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{}"))
          .andExpect(status().isBadRequest())
          .andExpect(header().doesNotExist("Set-Cookie"));
      mvc.perform(
              post("/api/v1/native-auth/" + action)
                  .header("Authorization", "Bearer " + TOKEN)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{}"))
          .andExpect(status().isBadRequest());
    }
  }

  @Test
  void bothCredentialsRejectedAndNativeCannotImpersonateWeb() throws Exception {
    mvc.perform(
            get("/api/v1/profile")
                .cookie(new Cookie("sid", TOKEN))
                .header("Authorization", "Bearer " + TOKEN))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/profile").header("Authorization", "Basic secret"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/auth/login")
                .header("Authorization", "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/auth/csrf").header("Authorization", "Bearer " + TOKEN))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/auth/login")
                .header("User-Agent", "MINI_PROGRAM")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void forcedPasswordChangeAndPendingCannotReachBusinessRoutes() throws Exception {
    mvc.perform(get("/api/v1/profile").header("Authorization", "Bearer " + TOKEN))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/applications").header("Authorization", "Bearer " + TOKEN))
        .andExpect(status().isUnauthorized());
    when(service.authenticate(TOKEN, "MINI_PROGRAM"))
        .thenReturn(new AuthPrincipal("1", "2", "ACTIVE", true, "MINI_PROGRAM"));
    mvc.perform(get("/api/v1/profile").header("Authorization", "Bearer " + TOKEN))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + TOKEN))
        .andExpect(status().isOk());
    mvc.perform(
            post("/api/v1/profile/password")
                .header("Authorization", "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"oldPassword\":\"old-value\",\"newPassword\":\"new-value\"}"))
        .andExpect(status().isNoContent());
  }

  @Test
  void identityJsonRequiresStringsRatherThanScalarCoercion() throws Exception {
    for (String body :
        new String[] {
          "{\"username\":1234,\"password\":\"password\"}",
          "{\"username\":\"user\",\"password\":12345678}",
          "{\"username\":true,\"password\":\"password\"}"
        }) {
      mvc.perform(
              post("/api/v1/native-auth/login")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(
            post("/api/v1/native-auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestedDepartmentId\":1}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  @Test
  void unknownFieldsOversizeAndDatabaseErrorsAreSafe() throws Exception {
    mvc.perform(
            post("/api/v1/native-auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"SYS_ADMIN\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("role"))));
    mvc.perform(
            post("/api/v1/native-auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(" ".repeat(1024 * 1024 + 1)))
        .andExpect(status().isPayloadTooLarge());
    when(service.login(any(), any(), any(), any(), any()))
        .thenThrow(new AuthFailure(429, "RATE_LIMITED", 42));
    mvc.perform(
            post("/api/v1/native-auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "42"));
    org.mockito.Mockito.doThrow(
            new org.springframework.dao.DataAccessResourceFailureException(
                "SQL secret database path"))
        .when(service)
        .login(any(), any(), any(), any(), any());
    mvc.perform(
            post("/api/v1/native-auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.retryable").value(true))
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SQL"))));
  }

  @Test
  void csrfExpiresWithControllableClockAndCannotMoveBetweenSessions() {
    Clock first = Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC);
    WebCredentials web = new WebCredentials("a".repeat(32), ORIGIN, true, first);
    var response = new org.springframework.mock.web.MockHttpServletResponse();
    String token = (String) web.issue(response, TOKEN).get("csrfToken");
    var request = new org.springframework.mock.web.MockHttpServletRequest();
    request.setCookies(response.getCookie("csrf_ctx"));
    request.addHeader("Origin", ORIGIN);
    request.addHeader("X-CSRF-Token", token);
    web.verify(request, TOKEN);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> web.verify(request, "B".repeat(43)))
        .isInstanceOf(AuthFailure.class);
    WebCredentials expired =
        new WebCredentials(
            "a".repeat(32),
            ORIGIN,
            true,
            Clock.fixed(first.instant().plusSeconds(900), ZoneOffset.UTC));
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> expired.verify(request, TOKEN))
        .isInstanceOf(AuthFailure.class);
  }
}
