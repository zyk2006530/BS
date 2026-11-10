package cn.edu.approval.file;

import cn.edu.approval.identity.AuthFailure;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded network pump: caller can time out even if a servlet input ignores interrupt/close. */
final class DeadlineInputStream extends InputStream {
  private static final ThreadPoolExecutor READERS =
      new ThreadPoolExecutor(
          0,
          32,
          30,
          TimeUnit.SECONDS,
          new SynchronousQueue<>(),
          r -> {
            Thread thread = new Thread(r, "file-network-reader");
            thread.setDaemon(true);
            return thread;
          });
  private static final ThreadPoolExecutor CLOSERS =
      new ThreadPoolExecutor(
          0,
          32,
          30,
          TimeUnit.SECONDS,
          new SynchronousQueue<>(),
          r -> {
            Thread thread = new Thread(r, "file-network-close");
            thread.setDaemon(true);
            return thread;
          });
  private final ArrayBlockingQueue<Chunk> queue = new ArrayBlockingQueue<>(2);
  private final InputStream source;
  private final long deadline;
  private final Future<?> reader;
  private volatile boolean closed;
  private volatile boolean sourceEnded;
  private boolean eof;
  private byte[] chunk = new byte[0];
  private int offset;

  DeadlineInputStream(InputStream source, Duration timeout) {
    this.source = source;
    deadline = System.nanoTime() + timeout.toNanos();
    try {
      reader = READERS.submit(this::pump);
    } catch (RejectedExecutionException ex) {
      throw new AuthFailure(503, "UPLOAD_CAPACITY_EXHAUSTED", 0);
    }
  }

  private void pump() {
    try {
      while (!closed) {
        byte[] bytes = new byte[8192];
        int count = source.read(bytes);
        if (count == 0) continue;
        if (count < 0) sourceEnded = true;
        offer(new Chunk(bytes, count, null));
        if (count < 0) return;
      }
    } catch (IOException | RuntimeException ex) {
      offer(new Chunk(new byte[0], -1, new IOException("Upload transport unavailable", ex)));
    }
  }

  private void offer(Chunk value) {
    long remaining = deadline - System.nanoTime();
    if (remaining <= 0) return;
    try {
      queue.offer(value, remaining, TimeUnit.NANOSECONDS);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public int read() throws IOException {
    if (eof) return -1;
    if (System.nanoTime() >= deadline) throw expired();
    if (offset < chunk.length) return chunk[offset++] & 255;
    long remaining = deadline - System.nanoTime();
    if (remaining <= 0) throw expired();
    try {
      Chunk next = queue.poll(remaining, TimeUnit.NANOSECONDS);
      if (next == null) throw expired();
      if (next.error() != null) throw next.error();
      if (next.count() < 0) {
        eof = true;
        return -1;
      }
      chunk =
          next.count() == next.bytes().length
              ? next.bytes()
              : java.util.Arrays.copyOf(next.bytes(), next.count());
      offset = 0;
      return read();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IOException("Upload interrupted", ex);
    }
  }

  private AuthFailure expired() {
    close();
    return new AuthFailure(408, "UPLOAD_TIMEOUT", 0);
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    if (sourceEnded) return;
    reader.cancel(true);
    // A broken transport's close may also block; never let it pin the request/deadline thread.
    try {
      CLOSERS.execute(
          () -> {
            try {
              source.close();
            } catch (IOException ignored) {
              /* request has failed closed */
            }
          });
    } catch (RejectedExecutionException ignored) {
      /* bounded fail-closed capacity; container timeout owns abandoned sockets */
    }
  }

  static int activeNetworkOperations() {
    return READERS.getActiveCount() + CLOSERS.getActiveCount();
  }

  private record Chunk(byte[] bytes, int count, IOException error) {}
}
