package cn.edu.approval.health;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
  @GetMapping("/api/v1/health")
  public ResponseEntity<HealthStatus> health() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(new HealthStatus("UP", "SKELETON", false));
  }

  public record HealthStatus(String status, String stage, boolean businessReady) {}
}
