package cn.edu.approval.application;

import java.util.List;

/** Safe field failures, without retaining input values or database details. */
public final class FixedBusinessFailure extends RuntimeException {
  private static final long serialVersionUID = 1L;
  private final int status;
  private final String code;
  private final List<FieldError> fieldErrors;

  public record FieldError(String path, String message) {}

  public FixedBusinessFailure(int status, String code, List<FieldError> fieldErrors) {
    super("Fixed business validation rejected");
    this.status = status;
    this.code = code;
    this.fieldErrors = List.copyOf(fieldErrors);
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  public List<FieldError> fieldErrors() {
    return fieldErrors;
  }

  static FixedBusinessFailure field(String path, String message) {
    return new FixedBusinessFailure(
        400, "INVALID_FIXED_BUSINESS", List.of(new FieldError(path, message)));
  }
}
