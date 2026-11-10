package cn.edu.approval.application;

import cn.edu.approval.identity.AuthPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("db & !migrate")
@RequestMapping("/api/v1/applications")
public class ApplicationController {
  private final ApplicationService service;

  public ApplicationController(ApplicationService service) {
    this.service = service;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> create(
      @RequestAttribute("identity") AuthPrincipal actor,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody JsonNode body) {
    return service.create(actor, key, body);
  }

  @GetMapping
  public Map<String, Object> list(
      @RequestAttribute("identity") AuthPrincipal actor,
      @RequestParam Map<String, String> filters) {
    return service.list(actor, filters);
  }

  @GetMapping("/{id}")
  public Map<String, Object> detail(
      @RequestAttribute("identity") AuthPrincipal actor, @PathVariable String id) {
    return service.get(actor, id);
  }

  @PutMapping("/{id}/draft")
  public Map<String, Object> save(
      @RequestAttribute("identity") AuthPrincipal actor,
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody JsonNode body) {
    return service.save(actor, id, key, body);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(
      @RequestAttribute("identity") AuthPrincipal actor,
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody JsonNode body) {
    service.delete(actor, id, key, body);
  }
}
