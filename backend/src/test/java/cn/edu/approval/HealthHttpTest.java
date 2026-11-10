package cn.edu.approval;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthHttpTest {
  @LocalServerPort private int port;

  private HttpResponse<String> request(String path, String method) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void healthIsExplicitlyNotBusinessReadiness() throws Exception {
    var response = request("/api/v1/health", "GET");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body())
        .isEqualTo("{\"status\":\"UP\",\"stage\":\"SKELETON\",\"businessReady\":false}");
    assertThat(response.headers().firstValue("cache-control")).contains("no-store");
    assertThat(response.headers().firstValue("set-cookie")).isEmpty();
    assertThat(response.headers().firstValue("x-content-type-options")).contains("nosniff");
  }

  @Test
  void businessAndManagementEndpointsAreNotPublic() throws Exception {
    assertThat(request("/api/v1/applications", "GET").statusCode()).isEqualTo(401);
    assertThat(request("/actuator/env", "GET").statusCode()).isEqualTo(401);
    assertThat(request("/api/v1/health", "POST").statusCode()).isEqualTo(403);
    assertThat(request("/login", "GET").statusCode()).isEqualTo(401);
  }
}
