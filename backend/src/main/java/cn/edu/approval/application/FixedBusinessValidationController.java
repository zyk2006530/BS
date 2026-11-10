package cn.edu.approval.application;

import cn.edu.approval.identity.AuthPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("db & !migrate")
public class FixedBusinessValidationController {
  private final FixedBusinessValidationService service;

  public FixedBusinessValidationController(FixedBusinessValidationService service) {
    this.service = service;
  }

  @PostMapping("/api/v1/applications/{id}/validate")
  public Map<String, Object> validate(
      @RequestAttribute("identity") AuthPrincipal actor,
      @PathVariable String id,
      @RequestBody JsonNode body) {
    return service.validate(actor, id, body);
  }
}
