package cn.edu.approval.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.edu.approval.identity.AuthPrincipal;
import cn.edu.approval.security.IdentityErrors;
import cn.edu.approval.security.IdentityResponses;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FixedBusinessErrorsTest {
  @Test
  void specificFieldAdvicePrecedesGenericIdentityCatchAll() throws Exception {
    var service = mock(FixedBusinessValidationService.class);
    var actor = new AuthPrincipal("1", "2", "ACTIVE", false, "NATIVE");
    when(service.validate(eq(actor), eq("3"), any()))
        .thenThrow(
            new FixedBusinessFailure(
                400,
                "INVALID_FIXED_BUSINESS",
                List.of(
                    new FixedBusinessFailure.FieldError(
                        "payload.items[1].amount", "Expense must be positive"))));
    var mvc =
        MockMvcBuilders.standaloneSetup(new FixedBusinessValidationController(service))
            .setControllerAdvice(
                new IdentityErrors(new ObjectMapper()),
                new FixedBusinessErrors(),
                new IdentityResponses(Clock.systemUTC()))
            .addFilter(
                (request, response, chain) -> {
                  ((HttpServletResponse) response).setHeader("X-Request-Id", "request-test");
                  chain.doFilter(request, response);
                })
            .build();
    mvc.perform(
            post("/api/v1/applications/3/validate")
                .requestAttr("identity", actor)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":\"7\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.code").value("INVALID_FIXED_BUSINESS"))
        .andExpect(jsonPath("$.fieldErrors[0].path").value("payload.items[1].amount"))
        .andExpect(jsonPath("$.fieldErrors[0].message").value("Expense must be positive"))
        .andExpect(jsonPath("$.requestId").value("request-test"))
        .andExpect(jsonPath("$.data").doesNotExist())
        .andExpect(header().string("X-Request-Id", "request-test"))
        .andExpect(jsonPath("$.retryable").value(false));
    when(service.validate(eq(actor), eq("3"), any())).thenReturn(Map.of("amount", "11.05"));
    mvc.perform(
            post("/api/v1/applications/3/validate")
                .requestAttr("identity", actor)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":\"7\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.amount").value("11.05"))
        .andExpect(jsonPath("$.requestId").value("request-test"))
        .andExpect(jsonPath("$.serverTime").isNotEmpty());
  }
}
