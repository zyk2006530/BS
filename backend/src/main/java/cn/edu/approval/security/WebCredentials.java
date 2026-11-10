package cn.edu.approval.security;

import cn.edu.approval.identity.AuthFailure;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.ResponseCookie;

/** Stateless pre-auth context; the proof is additionally bound to the current sid. */
public final class WebCredentials {
  private final byte[] secret;
  private final String origin;
  private final boolean secure;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public WebCredentials(String secret, String origin, boolean secure, Clock clock) {
    if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
      throw new IllegalArgumentException("APP_CSRF_SECRET must have at least 32 bytes");
    }
    var uri = java.net.URI.create(origin);
    if (uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !(uri.getPath().isEmpty())
        || !(uri.getScheme().equals("https") || (!secure && uri.getScheme().equals("http")))) {
      throw new IllegalArgumentException("APP_WEB_ORIGIN must be one exact trusted origin");
    }
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
    this.origin = origin;
    this.secure = secure;
    this.clock = clock;
  }

  static String cookie(HttpServletRequest request, String name) {
    String result = null;
    if (request.getCookies() != null) {
      for (Cookie cookie : request.getCookies()) {
        if (cookie.getName().equals(name)) {
          if (result != null) throw new AuthFailure(400, "AMBIGUOUS_CREDENTIALS", 0);
          result = cookie.getValue();
        }
      }
    }
    return result;
  }

  String sid(HttpServletRequest request) {
    return cookie(request, "sid");
  }

  void setCookie(HttpServletResponse response, String name, String value, long seconds) {
    response.addHeader(
        "Set-Cookie",
        ResponseCookie.from(name, value)
            .path("/")
            .httpOnly(true)
            .secure(secure)
            .sameSite("Lax")
            .maxAge(seconds)
            .build()
            .toString());
  }

  public Map<String, Object> issue(HttpServletResponse response, String sid) {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String body =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            + "."
            + (clock.instant().getEpochSecond() + 900);
    String context = body + "." + sign("context:" + body);
    setCookie(response, "csrf_ctx", context, 900);
    return Map.of("csrfToken", sign("proof:" + context + ":" + (sid == null ? "" : sid)));
  }

  void verify(HttpServletRequest request, String sid) {
    if (!origin.equals(request.getHeader("Origin"))) {
      throw new AuthFailure(403, "UNTRUSTED_ORIGIN", 0);
    }
    String context = cookie(request, "csrf_ctx");
    String proof = request.getHeader("X-CSRF-Token");
    if (context == null || context.length() > 256 || proof == null || proof.length() > 128) {
      throw new AuthFailure(403, "CSRF_INVALID", 0);
    }
    String[] parts = context.split("\\.", -1);
    try {
      if (parts.length != 3
          || Long.parseLong(parts[1]) <= clock.instant().getEpochSecond()
          || !equal(parts[2], sign("context:" + parts[0] + "." + parts[1]))
          || !equal(proof, sign("proof:" + context + ":" + (sid == null ? "" : sid)))) {
        throw new AuthFailure(403, "CSRF_INVALID", 0);
      }
    } catch (NumberFormatException ex) {
      throw new AuthFailure(403, "CSRF_INVALID", 0);
    }
  }

  private static boolean equal(String a, String b) {
    return MessageDigest.isEqual(
        a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
  }

  private String sign(String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException("HMAC unavailable");
    }
  }
}
