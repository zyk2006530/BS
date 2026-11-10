package cn.edu.approval.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.edu.approval.identity.AuthFailure;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class FixedDraftValidatorTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void incompleteFixedDraftsAreAllowedWithoutFabricatedDefaults() throws Exception {
    for (String type : new String[] {"PURCHASE", "TRAVEL", "REIMBURSEMENT"}) {
      FixedDraftValidator.validate(type, mapper.readTree("{}"));
    }
    FixedDraftValidator.validate(
        "PURCHASE",
        mapper.readTree(
            "{\"items\":[{\"clientKey\":\"item_a\",\"name\":\"\",\"quantity\":\"\",\"unitPrice\":\"\"}],\"quotes\":[]}"));
    FixedDraftValidator.validate(
        "REIMBURSEMENT",
        mapper.readTree(
            "{\"items\":[{\"clientKey\":\"expense_a\",\"amount\":\"0.00\",\"noReceipt\":true}]}"));
  }

  @Test
  void rowKeysAreRequiredUniqueAndTyped() throws Exception {
    for (String json :
        new String[] {
          "{\"items\":[{}]}",
          "{\"items\":[{\"clientKey\":\"x\"},{\"clientKey\":\"x\"}]}",
          "{\"items\":[{\"clientKey\":\"x\"}],\"quotes\":[{\"clientKey\":\"x\"}]}",
          "{\"items\":[{\"clientKey\":2}]}",
          "{\"items\":[{\"clientKey\":\"x\",\"id\":\"1\"}]}"
        }) {
      assertThatThrownBy(() -> FixedDraftValidator.validate("PURCHASE", mapper.readTree(json)))
          .isInstanceOf(AuthFailure.class);
    }
    var keys =
        FixedDraftValidator.typedKeys(
            "PURCHASE",
            mapper.readTree(
                "{\"items\":[{\"clientKey\":\"a\"}],\"quotes\":[{\"clientKey\":\"b\"}]}"));
    assertThat(keys).containsEntry("a", "PURCHASE:items").containsEntry("b", "PURCHASE:quotes");
  }

  @Test
  void reimbursementExpenseKeysUseItemsAndTrackRemovedReceiptTargets() throws Exception {
    var previous =
        mapper.readTree(
            "{\"items\":[{\"clientKey\":\"expense_a\"},{\"clientKey\":\"expense_b\"}]}");
    var next = mapper.readTree("{\"items\":[{\"clientKey\":\"expense_b\"}]}");
    FixedDraftValidator.validate("REIMBURSEMENT", previous);
    var before = FixedDraftValidator.typedKeys("REIMBURSEMENT", previous);
    var after = FixedDraftValidator.typedKeys("REIMBURSEMENT", next);
    assertThat(before)
        .containsEntry("expense_a", "REIMBURSEMENT:items")
        .containsEntry("expense_b", "REIMBURSEMENT:items");
    var removed = new java.util.HashSet<>(before.keySet());
    removed.removeAll(after.keySet());
    assertThat(removed).containsExactly("expense_a");
    assertThatThrownBy(
            () ->
                FixedDraftValidator.validate(
                    "REIMBURSEMENT",
                    mapper.readTree("{\"expenses\":[{\"clientKey\":\"expense_a\"}]}")))
        .isInstanceOf(AuthFailure.class);
  }

  @Test
  void unknownServerFieldsAndWrongScalarsAreRejected() throws Exception {
    for (String json :
        new String[] {
          "{\"naturalDays\":1}",
          "{\"budget\":123}",
          "{\"budget\":null}",
          "{\"budget\":\"1.001\"}",
          "{\"budget\":\"1\"}",
          "{\"budget\":\"1.2\"}",
          "{\"startAt\":\"2026-11-10T10:00:00+08:00\"}",
          "{\"startAt\":\"2026-11-10T02:00:00+00:00\"}",
          "{\"budget\":\"10000000.01\"}",
          "{\"budget\":\"-1\"}",
          "{\"budget\":\"1e3\"}",
          "{\"startAt\":\"not-date\"}",
          "{\"purpose\":false}"
        }) {
      assertThatThrownBy(() -> FixedDraftValidator.validate("TRAVEL", mapper.readTree(json)))
          .isInstanceOf(AuthFailure.class);
    }
    FixedDraftValidator.validate(
        "TRAVEL",
        mapper.readTree(
            "{\"budget\":\"10000000.00\",\"startAt\":\"2026-11-10T02:00:00.123456Z\"}"));
  }

  @Test
  void quantityDatesUrlsAndRowCapsAreBounded() throws Exception {
    for (String json :
        new String[] {
          "{\"items\":[{\"clientKey\":\"x\",\"quantity\":\"0\"}]}",
          "{\"items\":[{\"clientKey\":\"x\",\"quantity\":\"1.0001\"}]}",
          "{\"expectedDate\":\"2026-02-30\"}",
          "{\"quotes\":[{\"clientKey\":\"q\",\"purchaseUrl\":\"javascript:alert(1)\"}]}",
          "{\"quotes\":[{\"clientKey\":\"q\",\"purchaseUrl\":\"https://user:pass@example.test\"}]}",
          "{\"selectedQuoteKey\":\"missing\",\"quotes\":[]}"
        }) {
      assertThatThrownBy(() -> FixedDraftValidator.validate("PURCHASE", mapper.readTree(json)))
          .isInstanceOf(AuthFailure.class);
    }
    var payload = mapper.createObjectNode();
    var items = payload.putArray("items");
    for (int i = 0; i < 101; i++) items.addObject().put("clientKey", "k" + i);
    assertThatThrownBy(() -> FixedDraftValidator.validate("PURCHASE", payload))
        .isInstanceOf(AuthFailure.class);
    assertThatThrownBy(() -> FixedDraftValidator.validate("CUSTOM", mapper.createObjectNode()))
        .isInstanceOf(AuthFailure.class);
  }
}
