package cn.edu.approval.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.edu.approval.identity.AuthFailure;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrivateFileStoreTest {
  @TempDir Path root;
  private static final byte[] PNG = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

  @Test
  void extensionMimeAndMagicMustAgree() {
    assertThat(PrivateFileStore.media("票据.PNG", "image/png")).isEqualTo("image/png");
    for (String name : new String[] {"x.exe", "../x.pdf", "x\\x.pdf", "x\r\n.pdf", "x.pdf\u0000"})
      assertThatThrownBy(() -> PrivateFileStore.media(name, "application/pdf"))
          .isInstanceOf(AuthFailure.class);
    assertThatThrownBy(() -> PrivateFileStore.media("x.pdf", "image/png"))
        .isInstanceOf(AuthFailure.class);
    assertThat(PrivateFileStore.header("image/png", PNG)).isTrue();
    assertThat(PrivateFileStore.header("application/pdf", PNG)).isFalse();
    assertThat(
            PrivateFileStore.header("image/jpeg", new byte[] {(byte) 255, (byte) 216, (byte) 255}))
        .isTrue();
  }

  @Test
  void privateRandomPathsAtomicPublishHashAndIdempotentDeletion() throws Exception {
    var store = new PrivateFileStore(root.toString(), 100);
    String key = store.newKey();
    assertThat(key).matches("[a-f0-9]{32}");
    var bytes = store.write(key, "image/png", new ByteArrayInputStream(PNG));
    assertThat(Files.exists(store.path(key, false))).isFalse();
    assertThat(Files.exists(store.path(key, true))).isTrue();
    store.publish(key);
    assertThat(store.verify(key, bytes.size(), bytes.sha256())).isTrue();
    assertThat(store.verify(key, bytes.size() + 1, bytes.sha256())).isFalse();
    Files.write(store.path(key, false), new byte[8]);
    assertThat(store.verify(key, 8, bytes.sha256())).isFalse();
    store.delete(key);
    store.delete(key);
    assertThat(Files.exists(store.path(key, false))).isFalse();
  }

  @Test
  void rejectsEmptyFakeHeaderOverLimitAndPathTraversal() throws Exception {
    var store = new PrivateFileStore(root.toString(), 8);
    assertThatThrownBy(() -> store.path("../x", false)).isInstanceOf(AuthFailure.class);
    assertThatThrownBy(
            () -> store.write(store.newKey(), "image/png", new ByteArrayInputStream(new byte[0])))
        .isInstanceOf(AuthFailure.class);
    assertThatThrownBy(
            () -> store.write(store.newKey(), "image/png", new ByteArrayInputStream(new byte[8])))
        .isInstanceOf(AuthFailure.class);
    assertThatThrownBy(
            () -> store.write(store.newKey(), "image/png", new ByteArrayInputStream(new byte[9])))
        .isInstanceOf(AuthFailure.class)
        .extracting("status")
        .isEqualTo(413);
    assertThatThrownBy(() -> new PrivateFileStore(root.toString(), 10485761))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void survivingPartOrPublishedBytesSupportCrashRecovery() throws Exception {
    var store = new PrivateFileStore(root.toString(), 100);
    String key = store.newKey();
    store.write(key, "image/png", new ByteArrayInputStream(PNG));
    var restarted = new PrivateFileStore(root.toString(), 100);
    assertThat(Files.exists(restarted.path(key, true))).isTrue();
    restarted.publish(key);
    var restartedAfterRename = new PrivateFileStore(root.toString(), 100);
    assertThat(Files.exists(restartedAfterRename.path(key, false))).isTrue();
    restartedAfterRename.delete(key);
    new PrivateFileStore(root.toString(), 100).delete(key);
  }
}
