package cn.edu.approval.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.edu.approval.identity.AuthFailure;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class UploadBodyTest {
  private byte[] multipart(String headers, String data, String ending) {
    return ("--test\r\n" + headers + "\r\n\r\n" + data + "\r\n--test" + ending)
        .getBytes(StandardCharsets.UTF_8);
  }

  private static final String HEADERS =
      "Content-Disposition: form-data; name=\"file\"; filename=\"票据.pdf\"\r\nContent-Type: application/pdf";

  @Test
  void streamsExactBytesWithoutBoundaryOrEagerContainerParsing() throws Exception {
    try (var body =
        new UploadBody(
            new ByteArrayInputStream(multipart(HEADERS, "%PDF-1.7\r\ntext", "--\r\n")),
            "multipart/form-data; boundary=test",
            Duration.ofSeconds(5))) {
      assertThat(body.name()).isEqualTo("票据.pdf");
      assertThat(body.media()).isEqualTo("application/pdf");
      assertThat(new String(body.readAllBytes(), StandardCharsets.UTF_8))
          .isEqualTo("%PDF-1.7\r\ntext");
    }
  }

  @Test
  void rejectsMalformedBoundaryHeadersTruncationAndExtraParts() {
    for (byte[] bytes :
        new byte[][] {
          multipart(HEADERS, "%PDF", "\r\n"),
          multipart("X-Unknown: x", "data", "--\r\n"),
          multipart(HEADERS, "data", "--\r\nextra"),
          "--test\r\n".getBytes(StandardCharsets.UTF_8)
        }) {
      assertThatThrownBy(
              () -> {
                try (var body =
                    new UploadBody(
                        new ByteArrayInputStream(bytes),
                        "multipart/form-data; boundary=test",
                        Duration.ofSeconds(5))) {
                  body.readAllBytes();
                }
              })
          .isInstanceOf(AuthFailure.class);
    }
  }

  @Test
  void closesBlockedNetworkReadAtDeadlineNotJustBetweenChunks() throws Exception {
    var closed = new CountDownLatch(1);
    InputStream blocked =
        new InputStream() {
          @Override
          public int read() throws IOException {
            try {
              if (!closed.await(5, TimeUnit.SECONDS)) throw new IOException("Watchdog failed");
              return -1;
            } catch (InterruptedException ex) {
              Thread.currentThread().interrupt();
              throw new IOException(ex);
            }
          }

          @Override
          public void close() {
            closed.countDown();
          }
        };
    long start = System.nanoTime();
    assertThatThrownBy(
            () ->
                new UploadBody(
                    blocked, "multipart/form-data; boundary=test", Duration.ofMillis(40)))
        .isInstanceOf(AuthFailure.class)
        .extracting("status")
        .isEqualTo(408);
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
    assertThat(closed.await(1, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void invalidBoundaryClosesSource() {
    assertThatThrownBy(
            () ->
                new UploadBody(
                    new ByteArrayInputStream(new byte[0]),
                    "multipart/form-data; boundary=bad space",
                    Duration.ofSeconds(1)))
        .isInstanceOf(AuthFailure.class);
  }
}
