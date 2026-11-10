package cn.edu.approval.organization;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.edu.approval.identity.AuthPrincipal;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Controller DTO/method contract only; main security integration tests cover authentication. */
class OrganizationControllerTest {
  private OrganizationService service;
  private MockMvc mvc;
  private final AuthPrincipal principal =
      new AuthPrincipal("1", "2", "ACTIVE", false, "MINI_PROGRAM");

  @BeforeEach
  void setup() {
    service = mock(OrganizationService.class);
    ObjectMapper mapper =
        new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    mapper
        .coercionConfigFor(LogicalType.Textual)
        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
    mvc =
        MockMvcBuilders.standaloneSetup(new OrganizationController(service))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
            .build();
  }

  @Test
  void configUsesIdentityAttribute() throws Exception {
    when(service.config(principal)).thenReturn(Map.of("configVersion", "9"));
    mvc.perform(get("/api/v1/admin/config").requestAttr("identity", principal))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.configVersion").value("9"));
    verify(service).config(principal);
  }

  @Test
  void confirmBindsOnlyDepartmentAndVersionReasonFields() throws Exception {
    when(service.confirm(
            eq(principal), eq("17"), eq("3"), eq("5"), eq("9"), eq("confirmed"), any()))
        .thenReturn(Map.of("rowVersion", "6", "configVersion", "10"));
    mvc.perform(
            post("/api/v1/admin/users/17/confirm")
                .requestAttr("identity", principal)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"departmentId\":\"3\",\"expectedVersion\":\"5\",\"expectedConfigVersion\":\"9\",\"reason\":\"confirmed\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rowVersion").value("6"));
    mvc.perform(
            post("/api/v1/admin/users/17/confirm")
                .requestAttr("identity", principal)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"departmentId\":3}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/admin/users/17/confirm")
                .requestAttr("identity", principal)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"SYS_ADMIN\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void grantAndDepartmentCreatesUse201AndExplicitBooleanConfirmation() throws Exception {
    when(service.createGrant(
            eq(principal),
            eq("17"),
            eq("READ_BODY"),
            eq("ENTERPRISE"),
            any(),
            eq("2026-11-10T08:00:00Z"),
            eq("2026-11-11T08:00:00Z"),
            eq(true),
            eq("9"),
            eq("read approved"),
            any()))
        .thenReturn(Map.of("grantId", "8", "configVersion", "10"));
    mvc.perform(
            post("/api/v1/admin/grants")
                .requestAttr("identity", principal)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"userId\":\"17\",\"ability\":\"READ_BODY\",\"scope\":\"ENTERPRISE\",\"validFrom\":\"2026-11-10T08:00:00Z\",\"validUntil\":\"2026-11-11T08:00:00Z\",\"enterpriseConfirmed\":true,\"expectedConfigVersion\":\"9\",\"reason\":\"read approved\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.grantId").value("8"));
    mvc.perform(
            post("/api/v1/admin/departments")
                .requestAttr("identity", principal)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"Support\",\"enabled\":true,\"expectedConfigVersion\":\"9\",\"reason\":\"create\"}"))
        .andExpect(status().isCreated());
  }

  @Test
  void clearingManagerIsExplicitRatherThanAnOmittedPatchField() throws Exception {
    mvc.perform(
            patch("/api/v1/admin/departments/3")
                .requestAttr("identity", principal)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"clearManager\":true,\"expectedVersion\":\"5\",\"expectedConfigVersion\":\"9\",\"reason\":\"clear before move\"}"))
        .andExpect(status().isOk());
    verify(service)
        .patchDepartment(
            principal, "3", null, null, null, true, "5", "9", "clear before move", null);
  }
}
