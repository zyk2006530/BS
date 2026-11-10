package cn.edu.approval.application;

import cn.edu.approval.identity.AuthFailure;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Fixed-business draft syntax only; deliberately not a dynamic form interpreter. */
public final class FixedDraftValidator {
  private FixedDraftValidator() {}

  /** Compatibility entry point for existing TASK05 callers. */
  public static void validate(String type, JsonNode payload) {
    try {
      validateDetailed(type, payload);
    } catch (FixedBusinessFailure failure) {
      throw new AuthFailure(failure.status(), "INVALID_DRAFT", 0);
    }
  }

  public static void validateDetailed(String type, JsonNode payload) {
    if (payload == null || !payload.isObject()) throw invalid("payload");
    String definition =
        switch (type) {
          case "PURCHASE" ->
              "reason:t2000,expectedDate:date,project:t200,budget:money,freightAmount:money,otherAmount:money,otherReason:t2000,items:items,quotes:quotes,selectedQuoteKey:key,selectionReason:t2000,singleSourceReason:t2000,nonLowestReason:t2000";
          case "TRAVEL" ->
              "origin:t200,destination:t200,startAt:time,endAt:time,purpose:t2000,companions:t2000,transport:t80,transportOtherReason:t2000,budget:money";
          case "REIMBURSEMENT" ->
              "description:t2000,relatedApplicationId:id,independentReason:t2000,items:expenses";
          default -> throw new AuthFailure(503, "DRAFT_TYPE_UNAVAILABLE", 0);
        };
    object(payload, schema(definition), new HashSet<>(), "payload");
    if (type.equals("PURCHASE")
        && payload.hasNonNull("selectedQuoteKey")
        && !payload.get("selectedQuoteKey").asText().isEmpty()
        && !rowKeys(payload.get("quotes")).contains(payload.get("selectedQuoteKey").asText())) {
      throw invalid("payload.selectedQuoteKey");
    }
  }

  private static Map<String, String> schema(String definitions) {
    Map<String, String> result = new HashMap<>();
    for (String definition : definitions.split(",")) {
      String[] pair = definition.split(":");
      result.put(pair[0], pair[1]);
    }
    return result;
  }

  private static void object(
      JsonNode node, Map<String, String> fields, Set<String> keys, String path) {
    if (!node.isObject()) throw invalid(path);
    for (var entry : node.properties()) {
      String fieldPath = path + "." + entry.getKey();
      String format = fields.get(entry.getKey());
      JsonNode value = entry.getValue();
      if (format == null || value.isNull()) throw invalid(fieldPath);
      switch (format) {
        case "items" ->
            rows(
                value,
                100,
                "clientKey:key,name:t80,specification:t200,quantity:quantity,unit:t80,unitPrice:price",
                keys,
                fieldPath);
        case "quotes" ->
            rows(
                value,
                10,
                "clientKey:key,supplier:t80,quoteDate:date,total:money,sourceType:source,purchaseUrl:url,basisText:t2000",
                keys,
                fieldPath);
        case "expenses" ->
            rows(
                value,
                50,
                "clientKey:key,type:t80,expenseDate:date,purpose:t2000,amount:money,noReceipt:boolean,noReceiptReason:t2000",
                keys,
                fieldPath);
        case "boolean" -> {
          if (!value.isBoolean()) throw invalid(fieldPath);
        }
        default -> scalar(value, format, fieldPath);
      }
    }
  }

  private static void rows(
      JsonNode rows, int maximum, String definition, Set<String> keys, String path) {
    if (!rows.isArray() || rows.size() > maximum) throw invalid(path);
    for (int i = 0; i < rows.size(); i++) {
      JsonNode row = rows.get(i);
      String rowPath = path + "[" + i + "]";
      object(row, schema(definition), keys, rowPath);
      if (!row.hasNonNull("clientKey") || !keys.add(row.get("clientKey").asText()))
        throw invalid(rowPath + ".clientKey");
    }
  }

  private static void scalar(JsonNode node, String format, String path) {
    if (!node.isTextual()) throw invalid(path);
    String value = node.textValue();
    if (!unicode(value)) throw invalid(path);
    if (format.matches("t[0-9]+")) {
      if (value.codePointCount(0, value.length()) > Integer.parseInt(format.substring(1)))
        throw invalid(path);
      return;
    }
    if (format.equals("key")) {
      if (!value.matches("[A-Za-z0-9_-]{1,64}")) throw invalid(path);
      return;
    }
    if (value.isEmpty()) return;
    try {
      switch (format) {
        case "id" -> ApplicationService.id(value);
        case "money" -> decimal(value, 2, "10000000.00", false, path);
        case "price" -> decimal(value, 2, "10000000.00", true, path);
        case "quantity" -> decimal(value, 3, "999999999.999", true, path);
        case "date" -> {
          if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || value.startsWith("0000"))
            throw invalid(path);
          LocalDate.parse(value);
        }
        case "time" -> {
          if (!value.matches(
                  "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,6})?Z")
              || value.startsWith("0000")) throw invalid(path);
          // Instant parsing is lenient for 24:00; OffsetDateTime alone is not, preserving exact
          // input.
          OffsetDateTime.parse(value);
          LocalDate.parse(value.substring(0, 10));
        }
        case "source" -> {
          if (!Set.of("ONLINE", "OFFLINE").contains(value)) throw invalid(path);
        }
        case "url" -> {
          var uri = java.net.URI.create(value);
          if (value.length() > 2000
              || uri.getHost() == null
              || uri.getUserInfo() != null
              || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())))
            throw invalid(path);
        }
        default -> throw invalid(path);
      }
    } catch (java.time.DateTimeException | IllegalArgumentException | AuthFailure ex) {
      throw invalid(path);
    }
  }

  private static void decimal(
      String value, int scale, String maximum, boolean positive, String path) {
    String pattern =
        scale == 2
            ? "(?:0|[1-9][0-9]*)\\.[0-9]{2}"
            : "(?:0|[1-9][0-9]*)(?:\\.[0-9]{1," + scale + "})?";
    if (!value.matches(pattern)) throw invalid(path);
    BigDecimal amount = new BigDecimal(value);
    if (amount.compareTo(new BigDecimal(maximum)) > 0 || (positive && amount.signum() <= 0))
      throw invalid(path);
  }

  public static Set<String> rowKeys(JsonNode rows) {
    Set<String> result = new HashSet<>();
    if (rows != null && rows.isArray())
      for (JsonNode row : rows)
        if (row.hasNonNull("clientKey")) result.add(row.get("clientKey").asText());
    return result;
  }

  public static Map<String, String> typedKeys(String type, JsonNode payload) {
    Map<String, String> result = new HashMap<>();
    for (String key : rowKeys(payload.get("items"))) result.put(key, type + ":items");
    for (String key : rowKeys(payload.get("quotes"))) result.put(key, type + ":quotes");
    return result;
  }

  static boolean unicode(String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (Character.isHighSurrogate(c)) {
        if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) return false;
      } else if (Character.isLowSurrogate(c)) return false;
    }
    return true;
  }

  private static FixedBusinessFailure invalid(String path) {
    return FixedBusinessFailure.field(path, "Invalid field format or unsupported field");
  }
}
