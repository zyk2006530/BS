package cn.edu.approval.file;

import cn.edu.approval.identity.AuthPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.time.Duration;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("db & !migrate")
public class FileController {
  private final FileService service;
  private final PrivateFileStore store;

  public FileController(FileService service, PrivateFileStore store) {
    this.service = service;
    this.store = store;
  }

  @PostMapping(value = "/api/v1/files", consumes = "multipart/form-data")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> upload(
      @RequestAttribute("identity") AuthPrincipal actor,
      HttpServletRequest request,
      HttpServletResponse response)
      throws IOException {
    try {
      service.beginUpload(actor);
      // No container multipart buffering: header and body reads share one deadline.
      try (UploadBody body =
          new UploadBody(
              request.getInputStream(), request.getContentType(), Duration.ofMinutes(2))) {
        return service.upload(actor, body.name(), body.media(), body);
      }
    } catch (IOException | RuntimeException ex) {
      response.setHeader("Connection", "close");
      throw ex;
    }
  }

  @PostMapping("/api/v1/applications/{id}/bindings")
  public Map<String, Object> bind(
      @RequestAttribute("identity") AuthPrincipal actor,
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody JsonNode body) {
    return service.bind(actor, id, key, body);
  }

  @DeleteMapping("/api/v1/applications/{id}/bindings/{binding}")
  public Map<String, Object> unbind(
      @RequestAttribute("identity") AuthPrincipal actor,
      @PathVariable String id,
      @PathVariable String binding,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody JsonNode body) {
    return service.unbind(actor, id, binding, key, body);
  }

  @GetMapping("/api/v1/files/{id}/{action:preview|download}")
  public void download(
      @RequestAttribute("identity") AuthPrincipal actor,
      @PathVariable String id,
      @PathVariable String action,
      @RequestParam(required = false) String applicationId,
      HttpServletResponse response)
      throws IOException {
    Map<String, Object> file = service.download(actor, id, applicationId);
    String type = file.get("media_type").toString();
    // Only images are inline. PDF is attachment to avoid an active PDF document in our origin.
    boolean inline =
        action.equals("preview") && (type.equals("image/png") || type.equals("image/jpeg"));
    response.setContentType(type);
    response.setHeader(
        "Content-Disposition",
        (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
            .filename(file.get("original_name").toString(), StandardCharsets.UTF_8)
            .build()
            .toString());
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader(
        "Content-Security-Policy", "sandbox; default-src 'none'; frame-ancestors 'none'");
    response.setHeader("Accept-Ranges", "none");
    response.setContentLengthLong(Long.parseLong(file.get("byte_size").toString()));
    try (InputStream input =
        Files.newInputStream(
            store.path(file.get("storage_key").toString(), false), LinkOption.NOFOLLOW_LINKS)) {
      input.transferTo(response.getOutputStream());
    }
  }
}
