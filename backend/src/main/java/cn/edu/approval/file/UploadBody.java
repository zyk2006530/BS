package cn.edu.approval.file;

import cn.edu.approval.identity.AuthFailure;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single-file multipart reader: no servlet getParts(), eager disk buffering or unbounded headers.
 */
final class UploadBody extends InputStream {
  private final InputStream source;
  private final long deadline;
  private final byte[] delimiter;
  private final Deque<Integer> pending = new ArrayDeque<>();
  private boolean ended;
  private String name;
  private String media;

  UploadBody(InputStream source, String contentType, Duration limit) throws IOException {
    this.source = new DeadlineInputStream(source, limit);
    deadline = System.nanoTime() + limit.toNanos();
    Matcher boundary =
        Pattern.compile(
                "multipart/form-data;\\s*boundary=(?:\"([A-Za-z0-9'()+_,./:=?-]{1,70})\"|([A-Za-z0-9'()+_,./:=?-]{1,70}))",
                Pattern.CASE_INSENSITIVE)
            .matcher(contentType == null ? "" : contentType);
    if (!boundary.matches()) {
      close();
      throw invalid();
    }
    String value = boundary.group(1) != null ? boundary.group(1) : boundary.group(2);
    delimiter = ("\r\n--" + value).getBytes(StandardCharsets.US_ASCII);
    try {
      if (!line().equals("--" + value)) throw invalid();
      boolean disposition = false;
      boolean typed = false;
      for (int i = 0; i < 16; i++) {
        String line = line();
        if (line.isEmpty()) break;
        if (line.regionMatches(true, 0, "Content-Disposition: ", 0, 21)) {
          if (disposition) throw invalid();
          disposition = true;
          Matcher match =
              Pattern.compile(
                      "Content-Disposition: form-data;\\s*name=\"file\";\\s*filename=\"([^\"]+)\"",
                      Pattern.CASE_INSENSITIVE)
                  .matcher(line);
          if (!match.matches()) throw invalid();
          name = match.group(1);
        } else if (line.regionMatches(true, 0, "Content-Type: ", 0, 14)) {
          if (typed) throw invalid();
          typed = true;
          media = line.substring(14).trim();
        } else throw invalid();
        if (i == 15) throw invalid();
      }
      PrivateFileStore.media(name, media);
    } catch (IOException | RuntimeException ex) {
      close();
      throw ex;
    }
  }

  String name() {
    return name;
  }

  String media() {
    return media;
  }

  void assertWithinDeadline() {
    if (System.nanoTime() >= deadline) throw new AuthFailure(408, "UPLOAD_TIMEOUT", 0);
  }

  private void check() {
    assertWithinDeadline();
  }

  private int raw() throws IOException {
    check();
    int result = source.read();
    check();
    return result;
  }

  private String line() throws IOException {
    var bytes = new java.io.ByteArrayOutputStream();
    while (bytes.size() < 8192) {
      int value = raw();
      if (value < 0) throw invalid();
      if (value == 13) {
        if (raw() != 10) throw invalid();
        return bytes.toString(StandardCharsets.UTF_8);
      }
      bytes.write(value);
    }
    throw invalid();
  }

  @Override
  public int read() throws IOException {
    check();
    if (ended) return -1;
    while (pending.size() < delimiter.length) {
      int value = raw();
      if (value < 0) throw invalid();
      pending.addLast(value);
    }
    int index = 0;
    boolean match = true;
    for (int value : pending)
      if (value != (delimiter[index++] & 255)) {
        match = false;
        break;
      }
    if (match) {
      if (raw() != '-' || raw() != '-' || raw() != 13 || raw() != 10 || raw() != -1)
        throw invalid();
      ended = true;
      pending.clear();
      return -1;
    }
    return pending.removeFirst();
  }

  @Override
  public int read(byte[] bytes, int offset, int length) throws IOException {
    if (length == 0) return 0;
    int first = read();
    if (first < 0) return -1;
    bytes[offset] = (byte) first;
    int count = 1;
    while (count < length) {
      int value = read();
      if (value < 0) break;
      bytes[offset + count++] = (byte) value;
    }
    return count;
  }

  @Override
  public void close() throws IOException {
    source.close();
  }

  private static AuthFailure invalid() {
    return new AuthFailure(400, "INVALID_MULTIPART", 0);
  }
}
