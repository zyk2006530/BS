package cn.edu.approval.file;

import cn.edu.approval.identity.AuthFailure;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Flat private volume. User-supplied names are never paths; no fallback to non-atomic move. */
@Component
@Profile("db & !migrate")
public class PrivateFileStore {
  private final Path root;
  private final long maxBytes;

  public PrivateFileStore(
      @Value("${app.files.root:${APP_UPLOAD_DIR:./private-files}}") String root,
      @Value("${app.files.max-bytes:10485760}") long maxBytes) {
    this.root = Path.of(root).toAbsolutePath().normalize();
    if (maxBytes < 1 || maxBytes > 10485760)
      throw new IllegalArgumentException("Invalid file limit");
    this.maxBytes = maxBytes;
    try {
      Files.createDirectories(this.root);
      Path ancestor = this.root;
      while (ancestor != null) {
        if (Files.isSymbolicLink(ancestor)) throw new IOException("Symbolic volume path");
        ancestor = ancestor.getParent();
      }
    } catch (IOException ex) {
      throw unavailable();
    }
  }

  public String newKey() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  public Path root() {
    return root;
  }

  public Path path(String key, boolean temporary) {
    if (key == null || !key.matches("[a-f0-9]{32}")) throw unavailable();
    Path result = root.resolve(key + (temporary ? ".part" : "")).normalize();
    if (!result.getParent().equals(root) || Files.isSymbolicLink(result)) throw unavailable();
    return result;
  }

  public static String media(String name, String declared) {
    if (name == null
        || name.isBlank()
        || name.length() > 255
        || name.codePoints().anyMatch(c -> c < 32 || c == 127)
        || name.contains("/")
        || name.contains("\\")) throw new AuthFailure(400, "INVALID_FILE_NAME", 0);
    String lower = name.toLowerCase(Locale.ROOT);
    String type =
        lower.endsWith(".jpg") || lower.endsWith(".jpeg")
            ? "image/jpeg"
            : lower.endsWith(".png")
                ? "image/png"
                : lower.endsWith(".pdf") ? "application/pdf" : "";
    if (type.isEmpty() || !type.equals(declared))
      throw new AuthFailure(400, "INVALID_FILE_TYPE", 0);
    return type;
  }

  public Stored write(String key, String type, InputStream input) throws IOException {
    MessageDigest digest = digest();
    byte[] header = new byte[8];
    int headerLength = 0;
    long total = 0;
    try (OutputStream output =
        Files.newOutputStream(
            path(key, true), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Upload interrupted");
        total += count;
        if (total > maxBytes) throw new AuthFailure(413, "FILE_TOO_LARGE", 0);
        int copy = Math.min(count, 8 - headerLength);
        if (copy > 0) {
          System.arraycopy(buffer, 0, header, headerLength, copy);
          headerLength += copy;
        }
        digest.update(buffer, 0, count);
        output.write(buffer, 0, count);
      }
    }
    if (total == 0 || !header(type, Arrays.copyOf(header, headerLength)))
      throw new AuthFailure(400, "INVALID_FILE_HEADER", 0);
    return new Stored(total, digest.digest());
  }

  static boolean header(String type, byte[] bytes) {
    if (type.equals("image/png"))
      return bytes.length >= 8
          && Arrays.equals(bytes, new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10});
    if (type.equals("image/jpeg"))
      return bytes.length >= 3
          && (bytes[0] & 255) == 255
          && (bytes[1] & 255) == 216
          && (bytes[2] & 255) == 255;
    return type.equals("application/pdf")
        && bytes.length >= 5
        && bytes[0] == '%'
        && bytes[1] == 'P'
        && bytes[2] == 'D'
        && bytes[3] == 'F'
        && bytes[4] == '-';
  }

  public void publish(String key) throws IOException {
    Files.move(path(key, true), path(key, false), StandardCopyOption.ATOMIC_MOVE);
  }

  public boolean verify(String key, long size, byte[] hash) {
    try {
      Path path = path(key, false);
      if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != size)
        return false;
      MessageDigest digest = digest();
      try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
        byte[] bytes = new byte[8192];
        int count;
        while ((count = input.read(bytes)) != -1) digest.update(bytes, 0, count);
      }
      return MessageDigest.isEqual(hash, digest.digest());
    } catch (IOException | AuthFailure ex) {
      return false;
    }
  }

  public void delete(String key) throws IOException {
    Files.deleteIfExists(path(key, true));
    Files.deleteIfExists(path(key, false));
  }

  static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }

  static AuthFailure unavailable() {
    return new AuthFailure(503, "FILE_UNAVAILABLE", 0);
  }

  public record Stored(long size, byte[] sha256) {
    public String hex() {
      return HexFormat.of().formatHex(sha256);
    }
  }
}
