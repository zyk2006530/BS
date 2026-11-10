package cn.edu.approval.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.edu.approval.identity.AuthFailure;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class DeadlineInputStreamTest {
  @Test
  void completeEofIsStableAndNeverSchedulesLateServletClose() throws Exception {
    var closed = new AtomicBoolean();
    var raw =
        new ByteArrayInputStream(new byte[] {1}) {
          @Override
          public void close() {
            closed.set(true);
          }
        };
    var input = new DeadlineInputStream(raw, Duration.ofSeconds(2));
    assertThat(input.read()).isEqualTo(1);
    assertThat(input.read()).isEqualTo(-1);
    assertThat(input.read()).isEqualTo(-1);
    input.close();
    assertThat(closed).isFalse();
  }

  @Test
  void transportRuntimeFailureIsAnImmediateSafeIoFailure() throws Exception {
    var raw =
        new InputStream() {
          @Override
          public int read() {
            throw new IllegalStateException("private transport");
          }
        };
    try (var input = new DeadlineInputStream(raw, Duration.ofSeconds(2))) {
      assertThatThrownBy(input::read)
          .isInstanceOf(IOException.class)
          .hasMessage("Upload transport unavailable");
    }
  }

  @Test
  void deadlineReturnsEvenWhenTransportReadAndCloseIgnoreInterrupts() throws Exception {
    var release = new CountDownLatch(1);
    var readerLeft = new CountDownLatch(1);
    var closerLeft = new CountDownLatch(1);
    var raw =
        new InputStream() {
          private void awaitRelease() {
            boolean released = false;
            while (!released) {
              try {
                released = release.await(3, TimeUnit.SECONDS);
              } catch (InterruptedException ignored) {
                /* deliberately broken transport */
              }
            }
          }

          @Override
          public int read() {
            awaitRelease();
            readerLeft.countDown();
            return -1;
          }

          @Override
          public void close() {
            awaitRelease();
            closerLeft.countDown();
          }
        };
    long started = System.nanoTime();
    try (var input = new DeadlineInputStream(raw, Duration.ofMillis(40))) {
      assertThatThrownBy(input::read)
          .isInstanceOf(AuthFailure.class)
          .extracting("status")
          .isEqualTo(408);
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    } finally {
      release.countDown();
    }
    assertThat(readerLeft.await(1, TimeUnit.SECONDS)).isTrue();
    assertThat(closerLeft.await(1, TimeUnit.SECONDS)).isTrue();
  }
}
