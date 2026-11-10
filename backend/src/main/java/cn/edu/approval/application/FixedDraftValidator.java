package cn.edu.approval.application;

import cn.edu.approval.identity.AuthFailure;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/** Fixed-business draft syntax only; deliberately not a dynamic form interpreter. */
public final class FixedDraftValidator {
  private FixedDraftValidator() {}

  public static void validate(String type, JsonNode payload) {
    if (payload == null || !payload.isObject()) throw invalid();
    Map<String, String> fields = new HashMap<>();
    switch (type) {
      case "PURCHASE" -> {
        fields.putAll(
            schema(
                "reason:t2000,expectedDate:date,project:t200,budget:money,freightAmount:money,otherAmount:money,otherReason:t2000,items:items,quotes:quotes,selectedQuoteKey:key,selectionReason:t2000,singleSourceReason:t2000,nonLowestReason:t2000"));
      }
      case "TRAVEL" ->
          fields.putAll(
              schema(
                  "origin:t200,destination:t200,startAt:time,endAt:time,purpose:t2000,companions:t2000,transport:t80,budget:money"));
      case "REIMBURSEMENT" ->
          fields.putAll(
              schema(
                  "description:t2000,relatedApplicationId:id,independentReason:t2000,items:expenses"));
      default -> throw new AuthFailure(503, "DRAFT_TYPE_UNAVAILABLE", 0);
    }
    Set<String> keys = new HashSet<>();
    object(payload, fields, keys);
    if (type.equals("PURCHASE")
        && payload.hasNonNull("selectedQuoteKey")
        && !payload.get("selectedQuoteKey").asText().isEmpty()) {
      Set<String> quotes = rowKeys(payload.get("quotes"));
      if (!quotes.contains(payload.get("selectedQuoteKey").asText())) throw invalid();
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

  private static void object(JsonNode node, Map<String, String> fields, Set<String> keys) {
    if (!node.isObject()) throw invalid();
    Iterator<Map.Entry<String, JsonNode>> entries = node.properties().iterator();
    while (entries.hasNext()) {
      var entry = entries.next();
      String format = fields.get(entry.getKey());
      if (format == null) throw invalid();
      JsonNode value = entry.getValue();
      if (value.isNull()) throw invalid();
      switch (format) {
        case "items" ->
            rows(
                value,
                100,
                "clientKey:key,name:t80,specification:t200,quantity:quantity,unit:t80,unitPrice:price",
                keys);
        case "quotes" ->
            rows(
                value,
                10,
                "clientKey:key,supplier:t80,quoteDate:date,total:money,sourceType:source,purchaseUrl:url,basisText:t2000",
                keys);
        case "expenses" ->
            rows(
                value,
                50,
                "clientKey:key,type:t80,expenseDate:date,purpose:t2000,amount:money,noReceipt:boolean,noReceiptReason:t2000",
                keys);
        case "boolean" -> {
          if (!value.isBoolean()) throw invalid();
        }
        default -> scalar(value, format);
      }
    }
  }

  private static void rows(JsonNode rows, int maximum, String definition, Set<String> keys) {
    if (!rows.isArray() || rows.size() > maximum) throw invalid();
    for (JsonNode row : rows) {
      object(row, schema(definition), keys);
      if (!row.hasNonNull("clientKey")) throw invalid();
      String key = row.get("clientKey").asText();
      if (!keys.add(key)) throw invalid();
    }
  }

  private static void scalar(JsonNode node, String format) {
    if (!node.isTextual()) throw invalid();
    String value = node.textValue();
    if (!unicode(value)) throw invalid();
    if (format.matches("t[0-9]+")) {
      if (value.codePointCount(0, value.length()) > Integer.parseInt(format.substring(1)))
        throw invalid();
      return;
    }
    if (format.equals("key")) {
      if (!value.matches("[A-Za-z0-9_-]{1,64}")) throw invalid();
      return;
    }
    // Empty present values are a normal incomplete draft, not a fabricated numeric zero.
    if (value.isEmpty()) return;
    try {
      switch (format) {
        case "id" -> ApplicationService.id(value);
        case "money" -> decimal(value, 2, "10000000.00", false);
        case "price" -> decimal(value, 2, "10000000.00", true);
        case "quantity" -> decimal(value, 3, "999999999.999", true);
        case "date" -> {
          if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalid();
          LocalDate.parse(value);
        }
        case "time" -> {
          if (!value.matches(
              "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?Z"))
            throw invalid();
          OffsetDateTime.parse(value);
        }
        case "source" -> {
          if (!Set.of("ONLINE", "OFFLINE").contains(value)) throw invalid();
        }
        case "url" -> {
          var uri = java.net.URI.create(value);
          if (value.length() > 2000
              || uri.getHost() == null
              || uri.getUserInfo() != null
              || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())))
            throw invalid();
        }
        default -> throw invalid();
      }
    } catch (java.time.DateTimeException | IllegalArgumentException ex) {
      throw invalid();
    }
  }

  private static void decimal(String value, int scale, String maximum, boolean positive) {
    String pattern =
        scale == 2
            ? "(?:0|[1-9][0-9]*)\\.[0-9]{2}"
            : "(?:0|[1-9][0-9]*)(?:\\.[0-9]{1," + scale + "})?";
    if (!value.matches(pattern)) throw invalid();
    BigDecimal amount = new BigDecimal(value);
    if (amount.compareTo(new BigDecimal(maximum)) > 0 || (positive && amount.signum() <= 0))
      throw invalid();
  }

  public static Set<String> rowKeys(JsonNode rows) {
    Set<String> result = new HashSet<>();
    if (rows != null && rows.isArray()) {
      for (JsonNode row : rows)
        if (row.hasNonNull("clientKey")) result.add(row.get("clientKey").asText());
    }
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

  private static AuthFailure invalid() {
    return new AuthFailure(400, "INVALID_DRAFT", 0);
  }
}
