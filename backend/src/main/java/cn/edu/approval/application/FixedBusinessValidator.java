package cn.edu.approval.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Fixed business completeness and server arithmetic only. Contains no submission/route execution.
 */
@Component
@org.springframework.context.annotation.Profile("db & !migrate")
public final class FixedBusinessValidator {
  private static final BigDecimal MAX_AMOUNT = new BigDecimal("10000000.00");
  private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
  public static final Set<String> TRANSPORTS =
      Set.of("TRAIN", "PLANE", "CAR", "BUS", "SHIP", "OTHER");
  public static final Set<String> EXPENSE_TYPES =
      Set.of("TRANSPORT", "ACCOMMODATION", "MEALS", "OFFICE", "OTHER");
  private final FixedBusinessRelatedApplicationSource relatedSource;

  public FixedBusinessValidator(FixedBusinessRelatedApplicationSource relatedSource) {
    this.relatedSource = relatedSource;
  }

  /**
   * SQL column names intentionally follow actual file_binding/file_object storage, not expense
   * JSON.
   */
  public record Binding(
      String id,
      String fileId,
      String purpose,
      String draftTargetKey,
      String receiptNumber,
      String originalName,
      String mediaType,
      String byteSize,
      String sha256,
      String status,
      String ownerId,
      String expenseItemId,
      String quoteId,
      String fieldKey,
      String receiptSha256,
      String createdAt,
      String integrityStatus,
      String verifiedAt) {}

  public record Result(String amount, Long naturalDays, ObjectNode formSnapshot) {}

  /**
   * Caller supplies its already authorized draft/bindings; TASK09 must call again inside submit.
   */
  public Result validate(
      String applicantId,
      String type,
      String title,
      String remark,
      JsonNode payload,
      List<Binding> bindings) {
    FixedDraftValidator.validateDetailed(type, payload);
    List<FixedBusinessFailure.FieldError> errors = new ArrayList<>();
    text(title, 200, true, "title", errors);
    text(remark, 2000, false, "remark", errors);
    validateBindings(applicantId, type, payload, bindings, errors);
    BigDecimal amount;
    Long naturalDays = null;
    ObjectNode related = null;
    switch (type) {
      case "PURCHASE" -> amount = purchase(payload, bindings, errors);
      case "TRAVEL" -> {
        for (String name :
            List.of("origin", "destination", "startAt", "endAt", "purpose", "transport", "budget"))
          required(payload, name, "payload", errors);
        String transport = value(payload, "transport");
        if (!transport.isBlank() && !TRANSPORTS.contains(transport))
          error(errors, "payload.transport", "Unsupported transport");
        if (transport.equals("OTHER")) required(payload, "transportOtherReason", "payload", errors);
        String start = value(payload, "startAt");
        String end = value(payload, "endAt");
        if (!start.isEmpty() && !end.isEmpty()) {
          Instant from = Instant.parse(start);
          Instant to = Instant.parse(end);
          if (to.isBefore(from)) error(errors, "payload.endAt", "End must not precede start");
          else
            naturalDays =
                ChronoUnit.DAYS.between(
                        from.atZone(BEIJING).toLocalDate(), to.atZone(BEIJING).toLocalDate())
                    + 1;
        }
        amount = money(payload, "budget");
      }
      case "REIMBURSEMENT" -> {
        required(payload, "description", "payload", errors);
        amount = reimbursement(payload, bindings, errors);
        String relatedId = value(payload, "relatedApplicationId");
        if (relatedId.isEmpty()) required(payload, "independentReason", "payload", errors);
        // Check ordinary completeness first, before touching any approved source body.
        fail(errors);
        if (!relatedId.isEmpty()) related = relatedSnapshot(relatedId, applicantId);
      }
      default -> throw new IllegalStateException("Unsupported fixed business");
    }
    bounded(amount, "payload.items", errors);
    fail(errors);
    ObjectNode snapshot = JsonNodeFactory.instance.objectNode();
    snapshot.put("schemaVersion", 1).put("type", type).put("title", title).put("remark", remark);
    snapshot.set("payload", payload.deepCopy());
    snapshot.put("amount", amount.setScale(2).toPlainString());
    if (naturalDays == null) snapshot.putNull("naturalDays");
    else snapshot.put("naturalDays", naturalDays);
    if (related == null) snapshot.putNull("relatedApplicationSnapshot");
    else snapshot.set("relatedApplicationSnapshot", related);
    var files = snapshot.putArray("bindings");
    for (Binding binding : bindings) {
      ObjectNode file = files.addObject();
      file.put("bindingId", binding.id())
          .put("fileId", binding.fileId())
          .put("purpose", binding.purpose())
          .put("draftTargetKey", binding.draftTargetKey())
          .put("receiptNumber", binding.receiptNumber())
          .put("originalName", binding.originalName())
          .put("mediaType", binding.mediaType())
          .put("byteSize", binding.byteSize())
          .put("sha256", binding.sha256())
          .put("createdAt", binding.createdAt())
          .put("integrityStatus", binding.integrityStatus())
          .put("verifiedAt", binding.verifiedAt());
    }
    return new Result(amount.setScale(2).toPlainString(), naturalDays, snapshot);
  }

  private static BigDecimal purchase(
      JsonNode p, List<Binding> bindings, List<FixedBusinessFailure.FieldError> errors) {
    for (String field :
        List.of(
            "reason",
            "expectedDate",
            "project",
            "budget",
            "freightAmount",
            "otherAmount",
            "selectedQuoteKey")) required(p, field, "payload", errors);
    JsonNode items = rows(p, 100, "items", errors);
    BigDecimal total = BigDecimal.ZERO;
    for (int i = 0; i < items.size(); i++) {
      JsonNode row = items.get(i);
      String path = "payload.items[" + i + "]";
      for (String field : List.of("name", "specification", "quantity", "unit", "unitPrice"))
        required(row, field, path, errors);
      BigDecimal line =
          money(row, "quantity")
              .multiply(money(row, "unitPrice"))
              .setScale(2, RoundingMode.HALF_UP);
      bounded(line, path + ".unitPrice", errors);
      total = total.add(line);
    }
    total = total.add(money(p, "freightAmount")).add(money(p, "otherAmount"));
    if (money(p, "otherAmount").signum() > 0) required(p, "otherReason", "payload", errors);
    JsonNode quotes = rows(p, 10, "quotes", errors);
    if (quotes.size() == 1) required(p, "singleSourceReason", "payload", errors);
    Set<String> suppliers = new HashSet<>();
    BigDecimal minimum = null;
    BigDecimal selected = null;
    String selectedKey = value(p, "selectedQuoteKey");
    for (int i = 0; i < quotes.size(); i++) {
      JsonNode quote = quotes.get(i);
      String path = "payload.quotes[" + i + "]";
      for (String field : List.of("supplier", "quoteDate", "total", "sourceType"))
        required(quote, field, path, errors);
      if (!suppliers.add(value(quote, "supplier").strip().toLowerCase(Locale.ROOT)))
        error(errors, path + ".supplier", "Supplier must be distinct");
      boolean evidence = hasBinding(bindings, "QUOTE_EVIDENCE", value(quote, "clientKey"));
      if (value(quote, "basisText").isBlank() && !evidence)
        error(errors, path + ".basisText", "Quote requires text or attached evidence");
      if (value(quote, "sourceType").equals("ONLINE")) {
        required(quote, "purchaseUrl", path, errors);
        if (bindings.stream()
            .noneMatch(
                b ->
                    "QUOTE_EVIDENCE".equals(b.purpose())
                        && value(quote, "clientKey").equals(b.draftTargetKey())
                        && Set.of("image/png", "image/jpeg").contains(b.mediaType())))
          error(errors, path + ".clientKey", "Online quote requires attached screenshot evidence");
      }
      BigDecimal quoted = money(quote, "total");
      if (quoted.signum() <= 0 && !value(quote, "total").isEmpty())
        error(errors, path + ".total", "Quote must be positive");
      if (minimum == null || quoted.compareTo(minimum) < 0) minimum = quoted;
      if (value(quote, "clientKey").equals(selectedKey)) selected = quoted;
    }
    if (selected != null) {
      if (total.compareTo(selected) != 0)
        error(
            errors,
            "payload.selectedQuoteKey",
            "Recalculated total must equal selected quote; allocate any difference to items");
      if (minimum != null && selected.compareTo(minimum) > 0)
        required(p, "nonLowestReason", "payload", errors);
    }
    if (total.compareTo(money(p, "budget")) > 0)
      error(errors, "payload.budget", "Recalculated total exceeds budget");
    return total;
  }

  private static BigDecimal reimbursement(
      JsonNode p, List<Binding> bindings, List<FixedBusinessFailure.FieldError> errors) {
    JsonNode items = rows(p, 50, "items", errors);
    BigDecimal total = BigDecimal.ZERO;
    for (int i = 0; i < items.size(); i++) {
      JsonNode row = items.get(i);
      String path = "payload.items[" + i + "]";
      for (String field : List.of("type", "expenseDate", "purpose", "amount"))
        required(row, field, path, errors);
      if (!value(row, "type").isBlank() && !EXPENSE_TYPES.contains(value(row, "type")))
        error(errors, path + ".type", "Unsupported expense type");
      if (!row.has("noReceipt"))
        error(errors, path + ".noReceipt", "Explicit receipt classification required");
      BigDecimal amount = money(row, "amount");
      if (!value(row, "amount").isEmpty() && amount.signum() <= 0)
        error(errors, path + ".amount", "Expense must be positive");
      total = total.add(amount);
      boolean receipt = hasBinding(bindings, "EXPENSE_RECEIPT", value(row, "clientKey"));
      if (row.path("noReceipt").asBoolean(false)) {
        required(row, "noReceiptReason", path, errors);
        if (receipt)
          error(
              errors,
              path + ".noReceipt",
              "No-receipt expense cannot have receipt bindings; split partial receipts into separate rows");
      } else {
        if (!receipt) error(errors, path + ".clientKey", "Expense requires at least one receipt");
        if (!value(row, "noReceiptReason").isBlank())
          error(
              errors,
              path + ".noReceiptReason",
              "Receipted expense cannot have a no-receipt reason");
      }
    }
    return total;
  }

  private static void validateBindings(
      String applicantId,
      String type,
      JsonNode payload,
      List<Binding> bindings,
      List<FixedBusinessFailure.FieldError> errors) {
    Set<String> files = new HashSet<>();
    Set<String> receipts = new HashSet<>();
    Set<String> items = FixedDraftValidator.rowKeys(payload.get("items"));
    Set<String> quotes = FixedDraftValidator.rowKeys(payload.get("quotes"));
    for (int i = 0; i < bindings.size(); i++) {
      Binding b = bindings.get(i);
      String path = "bindings[" + i + "]";
      if (!"READY".equals(b.status()) || !applicantId.equals(b.ownerId()))
        error(errors, path + ".fileId", "File must be ready and owned by applicant");
      if (!"OK".equals(b.integrityStatus()) || b.verifiedAt() == null || b.verifiedAt().isBlank())
        error(errors, path + ".fileId", "File integrity must be verified before validation");
      if (!files.add(b.fileId())) error(errors, path + ".fileId", "Duplicate file");
      if (b.expenseItemId() != null || b.quoteId() != null || b.fieldKey() != null)
        error(errors, path + ".draftTargetKey", "Draft binding cannot contain formal targets");
      switch (b.purpose()) {
        case "GENERAL" -> {
          if (b.draftTargetKey() != null || b.receiptNumber() != null || b.receiptSha256() != null)
            error(errors, path + ".purpose", "Invalid general binding");
        }
        case "QUOTE_EVIDENCE" -> {
          if (!type.equals("PURCHASE")
              || !quotes.contains(b.draftTargetKey())
              || b.receiptNumber() != null
              || b.receiptSha256() != null)
            error(errors, path + ".draftTargetKey", "Quote target must belong to this purchase");
        }
        case "EXPENSE_RECEIPT" -> {
          if (!type.equals("REIMBURSEMENT") || !items.contains(b.draftTargetKey()))
            error(
                errors,
                path + ".draftTargetKey",
                "Expense target must belong to this reimbursement");
          if (b.receiptSha256() == null
              || !b.receiptSha256().equalsIgnoreCase(b.sha256())
              || !receipts.add(b.receiptSha256().toLowerCase(Locale.ROOT)))
            error(errors, path + ".fileId", "Invalid or duplicate receipt digest");
          if (b.receiptNumber() != null)
            text(b.receiptNumber(), 80, false, path + ".receiptNumber", errors);
        }
        default -> error(errors, path + ".purpose", "Unsupported fixed-business binding purpose");
      }
    }
  }

  private ObjectNode relatedSnapshot(String id, String applicantId) {
    var source =
        relatedSource
            .find(id, applicantId)
            .orElseThrow(
                () ->
                    FixedBusinessFailure.field(
                        "payload.relatedApplicationId",
                        "Related application must be your approved purchase or travel"));
    if (!id.equals(source.id())
        || !applicantId.equals(source.applicantId())
        || !"APPROVED".equals(source.status())
        || !Set.of("PURCHASE", "TRAVEL").contains(source.type()))
      throw FixedBusinessFailure.field(
          "payload.relatedApplicationId",
          "Related application must be your approved purchase or travel");
    JsonNode snapshot = source.formSnapshot();
    if (snapshot == null
        || !snapshot.isObject()
        || snapshot.path("schemaVersion").asInt(-1) != 1
        || !source.type().equals(value(snapshot, "type"))
        || source.applicationNo() == null
        || source.applicationNo().isBlank()
        || source.title() == null
        || source.title().isBlank())
      throw FixedBusinessRelatedApplicationConfiguration.unavailable();
    JsonNode payload = snapshot.path("payload");
    ObjectNode result = JsonNodeFactory.instance.objectNode();
    result
        .put("applicationId", id)
        .put("applicationNo", source.applicationNo())
        .put("type", source.type())
        .put("title", source.title());
    try {
      FixedDraftValidator.validateDetailed(source.type(), payload);
      List<String> required =
          source.type().equals("TRAVEL")
              ? List.of("origin", "destination", "startAt", "endAt", "budget")
              : List.of("project");
      for (String field : required) {
        if (value(payload, field).isBlank())
          throw FixedBusinessRelatedApplicationConfiguration.unavailable();
        result.put(field, value(payload, field));
      }
      if (source.type().equals("TRAVEL")) {
        if (Instant.parse(value(payload, "endAt"))
            .isBefore(Instant.parse(value(payload, "startAt"))))
          throw FixedBusinessRelatedApplicationConfiguration.unavailable();
      } else {
        JsonNode amount = snapshot.get("amount");
        if (amount == null
            || !amount.isTextual()
            || !amount.asText().matches("(?:0|[1-9][0-9]*)\\.[0-9]{2}")
            || new BigDecimal(amount.asText()).signum() <= 0
            || new BigDecimal(amount.asText()).compareTo(MAX_AMOUNT) > 0)
          throw FixedBusinessRelatedApplicationConfiguration.unavailable();
        result.put("expectedTotal", amount.asText());
      }
      return result;
    } catch (FixedBusinessFailure | cn.edu.approval.identity.AuthFailure ex) {
      throw FixedBusinessRelatedApplicationConfiguration.unavailable();
    }
  }

  private static boolean hasBinding(List<Binding> bindings, String purpose, String key) {
    return bindings.stream()
        .anyMatch(b -> purpose.equals(b.purpose()) && key.equals(b.draftTargetKey()));
  }

  private static String value(JsonNode node, String field) {
    return node.path(field).asText("");
  }

  private static BigDecimal money(JsonNode node, String field) {
    String value = value(node, field);
    return value.isEmpty() ? BigDecimal.ZERO : new BigDecimal(value);
  }

  private static JsonNode rows(
      JsonNode node, int max, String field, List<FixedBusinessFailure.FieldError> errors) {
    JsonNode rows = node.path(field);
    if (!rows.isArray() || rows.isEmpty() || rows.size() > max)
      error(errors, "payload." + field, "Between 1 and " + max + " rows required");
    return rows;
  }

  private static void required(
      JsonNode node, String field, String path, List<FixedBusinessFailure.FieldError> errors) {
    if (value(node, field).isBlank()) error(errors, path + "." + field, "Required field");
  }

  private static void bounded(
      BigDecimal amount, String path, List<FixedBusinessFailure.FieldError> errors) {
    if (amount.signum() < 0 || amount.compareTo(MAX_AMOUNT) > 0)
      error(errors, path, "Amount exceeds 10000000.00");
  }

  private static void text(
      String value,
      int max,
      boolean required,
      String path,
      List<FixedBusinessFailure.FieldError> errors) {
    if (value == null
        || !FixedDraftValidator.unicode(value)
        || value.codePointCount(0, value.length()) > max
        || (required && value.isBlank())) error(errors, path, "Invalid or missing text");
  }

  private static void error(
      List<FixedBusinessFailure.FieldError> errors, String path, String message) {
    errors.add(new FixedBusinessFailure.FieldError(path, message));
  }

  private static void fail(List<FixedBusinessFailure.FieldError> errors) {
    if (!errors.isEmpty()) throw new FixedBusinessFailure(400, "INVALID_FIXED_BUSINESS", errors);
  }
}
