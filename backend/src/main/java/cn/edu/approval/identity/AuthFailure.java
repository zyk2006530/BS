package cn.edu.approval.identity;

/** Safe, transport-neutral authentication failure. Never retains database or credential details. */
public final class AuthFailure extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final int status;
  private final String code;
  private final int retryAfter;

  public AuthFailure(int status, String code, int retryAfter) {
    this(status, code, "Identity request rejected", retryAfter);
  }

  public AuthFailure(int status, String code, String message) {
    this(status, code, message, 0);
  }

  public AuthFailure(int status, String code, String message, int retryAfter) {
    super(message);
    this.status = status;
    this.code = code;
    this.retryAfter = retryAfter;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  public int retryAfter() {
    return retryAfter;
  }
}
