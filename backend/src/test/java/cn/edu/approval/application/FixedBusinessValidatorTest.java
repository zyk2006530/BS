package cn.edu.approval.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class FixedBusinessValidatorTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final FixedBusinessValidator validator =
      new FixedBusinessValidator((id, owner) -> Optional.empty());

  private ObjectNode purchase() throws Exception {
    return (ObjectNode)
        mapper.readTree(
            """
        {"reason":"needed","expectedDate":"2026-11-10","project":"project","budget":"100.00",
         "freightAmount":"0.00","otherAmount":"0.00","selectedQuoteKey":"q1",
         "items":[{"clientKey":"i1","name":"a","specification":"s","quantity":"1.005","unit":"piece","unitPrice":"1.00"},
                  {"clientKey":"i2","name":"b","specification":"s","quantity":"1.005","unit":"piece","unitPrice":"1.00"}],
         "quotes":[{"clientKey":"q1","supplier":"one","quoteDate":"2026-11-10","total":"2.02","sourceType":"OFFLINE","basisText":"written quote"},
                   {"clientKey":"q2","supplier":"two","quoteDate":"2026-11-10","total":"3.00","sourceType":"OFFLINE","basisText":"written quote"}]}
        """);
  }

  private ObjectNode travel() throws Exception {
    return (ObjectNode)
        mapper.readTree(
            """
        {"origin":"杭州","destination":"北京","startAt":"2026-01-31T16:01:00Z",
         "endAt":"2026-02-01T15:59:00Z","purpose":"visit","transport":"TRAIN","budget":"20000.00"}
        """);
  }

  private ObjectNode reimbursement() throws Exception {
    return (ObjectNode)
        mapper.readTree(
            """
        {"description":"expenses","independentReason":"independent purchase",
         "items":[{"clientKey":"e1","type":"TRANSPORT","expenseDate":"2026-11-10","purpose":"trip","amount":"1.01","noReceipt":false},
                  {"clientKey":"e2","type":"ACCOMMODATION","expenseDate":"2026-11-10","purpose":"hotel","amount":"2.02","noReceipt":false},
                  {"clientKey":"e3","type":"MEALS","expenseDate":"2026-11-10","purpose":"food","amount":"3.03","noReceipt":true,"noReceiptReason":"lost"}]}
        """);
  }

  private FixedBusinessValidator.Binding binding(
      String id, String purpose, String target, String number) {
    return new FixedBusinessValidator.Binding(
        id,
        id,
        purpose,
        target,
        number,
        "票据.png",
        "image/png",
        "100",
        id.repeat(64),
        "READY",
        "1",
        null,
        null,
        null,
        "EXPENSE_RECEIPT".equals(purpose) ? id.repeat(64) : null,
        "2026-11-10T00:00:00Z",
        "OK",
        "2026-11-10T00:00:00Z");
  }

  private List<FixedBusinessValidator.Binding> receipts() {
    return List.of(
        binding("1", "EXPENSE_RECEIPT", "e1", "票1"),
        binding("2", "EXPENSE_RECEIPT", "e1", "票2"),
        binding("3", "EXPENSE_RECEIPT", "e2", null));
  }

  private FixedBusinessValidator.Result check(
      String type, ObjectNode payload, List<FixedBusinessValidator.Binding> files) {
    return validator.validate("1", type, "title", "", payload, files);
  }

  private void rejected(
      String type, ObjectNode payload, List<FixedBusinessValidator.Binding> files, String path) {
    assertThatThrownBy(() -> check(type, payload, files))
        .isInstanceOfSatisfying(
            FixedBusinessFailure.class,
            failure ->
                assertThat(failure.fieldErrors())
                    .extracting(FixedBusinessFailure.FieldError::path)
                    .contains(path));
  }

  @Test
  void roundsEachLineBeforeSummingAndSnapshotIsDetached() throws Exception {
    var p = purchase();
    var result = check("PURCHASE", p, List.of());
    assertThat(result.amount()).isEqualTo("2.02");
    assertThat(result.naturalDays()).isNull();
    p.put("budget", "0.00");
    assertThat(result.formSnapshot().path("payload").path("budget").asText()).isEqualTo("100.00");
  }

  @ParameterizedTest
  @CsvSource({
    "freightAmount,0.01,payload.selectedQuoteKey",
    "otherAmount,0.01,payload.otherReason",
    "budget,2.01,payload.budget"
  })
  void rejectsDifferenceOtherWithoutReasonAndBudget(String field, String amount, String path)
      throws Exception {
    var p = purchase().put(field, amount);
    rejected("PURCHASE", p, List.of(), path);
  }

  @Test
  void freightOtherCloseAndReasonsAreFrozen() throws Exception {
    var p =
        purchase()
            .put("freightAmount", "0.01")
            .put("otherAmount", "0.01")
            .put("otherReason", "packaging");
    ((ObjectNode) p.path("quotes").get(0)).put("total", "2.04");
    assertThat(check("PURCHASE", p, List.of()).amount()).isEqualTo("2.04");
  }

  @Test
  void singleSourceAndNonLowestReasonsAreConditional() throws Exception {
    var p = purchase();
    ((com.fasterxml.jackson.databind.node.ArrayNode) p.path("quotes")).remove(1);
    rejected("PURCHASE", p, List.of(), "payload.singleSourceReason");
    p.put("singleSourceReason", "exclusive vendor");
    assertThat(
            check("PURCHASE", p, List.of())
                .formSnapshot()
                .path("payload")
                .path("singleSourceReason")
                .asText())
        .isEqualTo("exclusive vendor");
    p = purchase();
    ((ObjectNode) p.path("quotes").get(1)).put("total", "2.01");
    rejected("PURCHASE", p, List.of(), "payload.nonLowestReason");
    p.put("nonLowestReason", "better support");
    assertThat(check("PURCHASE", p, List.of()).amount()).isEqualTo("2.02");
  }

  @Test
  void duplicateSuppliersAndMissingBasisRejected() throws Exception {
    var p = purchase();
    ((ObjectNode) p.path("quotes").get(1)).put("supplier", " ONE ");
    rejected("PURCHASE", p, List.of(), "payload.quotes[1].supplier");
    p = purchase();
    ((ObjectNode) p.path("quotes").get(0)).remove("basisText");
    rejected("PURCHASE", p, List.of(), "payload.quotes[0].basisText");
    assertThat(check("PURCHASE", p, List.of(binding("1", "QUOTE_EVIDENCE", "q1", null))).amount())
        .isEqualTo("2.02");
  }

  @Test
  void onlineNeedsUrlAndScreenshotAndNeverFetchesUrl() throws Exception {
    var p = purchase();
    var quote = (ObjectNode) p.path("quotes").get(0);
    quote.put("sourceType", "ONLINE");
    rejected("PURCHASE", p, List.of(), "payload.quotes[0].purchaseUrl");
    quote.put("purchaseUrl", "https://127.0.0.1:1/private");
    rejected("PURCHASE", p, List.of(), "payload.quotes[0].clientKey");
    var image = binding("1", "QUOTE_EVIDENCE", "q1", null);
    var pdf =
        new FixedBusinessValidator.Binding(
            image.id(),
            image.fileId(),
            image.purpose(),
            image.draftTargetKey(),
            null,
            "quote.pdf",
            "application/pdf",
            image.byteSize(),
            image.sha256(),
            "READY",
            "1",
            null,
            null,
            null,
            null,
            image.createdAt(),
            image.integrityStatus(),
            image.verifiedAt());
    rejected("PURCHASE", p, List.of(pdf), "payload.quotes[0].clientKey");
    assertThat(check("PURCHASE", p, List.of(image)).amount()).isEqualTo("2.02");
  }

  @ParameterizedTest
  @ValueSource(strings = {"0.0001", "1000000000", "1e3", "-1", "01", "0"})
  void quantitiesRejectInvalidScaleRangeAndFormat(String quantity) throws Exception {
    var p = purchase();
    ((ObjectNode) p.path("items").get(0)).put("quantity", quantity);
    rejected("PURCHASE", p, List.of(), "payload.items[0].quantity");
  }

  @ParameterizedTest
  @ValueSource(strings = {"1.001", "10000000.01", "1e3", "-1.00", "1", "1.0"})
  void amountsRejectInvalidScaleRangeAndFormat(String budget) throws Exception {
    rejected("TRAVEL", travel().put("budget", budget), List.of(), "payload.budget");
  }

  @Test
  void rejectsLineOverflowAndAggregateOverflow() throws Exception {
    var p = purchase();
    ((ObjectNode) p.path("items").get(0))
        .put("quantity", "999999999.999")
        .put("unitPrice", "10000000.00");
    rejected("PURCHASE", p, List.of(), "payload.items[0].unitPrice");
    var r = reimbursement();
    ((ObjectNode) r.path("items").get(0)).put("amount", "10000000.00");
    rejected("REIMBURSEMENT", r, receipts(), "payload.items");
  }

  @ParameterizedTest
  @CsvSource({
    "2026-01-01T00:00:00Z,2026-01-01T00:00:00Z,1",
    "2026-01-31T15:59:59Z,2026-01-31T16:00:00Z,2",
    "2026-01-31T23:59:59Z,2026-02-01T00:00:00Z,1",
    "2024-02-28T16:00:00Z,2024-03-01T15:59:59Z,2"
  })
  void beijingNaturalDaysUseDatesNotHours(String start, String end, long days) throws Exception {
    var result = check("TRAVEL", travel().put("startAt", start).put("endAt", end), List.of());
    assertThat(result.naturalDays()).isEqualTo(days);
  }

  @Test
  void zeroBudgetValidReverseTimesAndOtherTransportRejected() throws Exception {
    assertThat(check("TRAVEL", travel().put("budget", "0.00"), List.of()).amount())
        .isEqualTo("0.00");
    rejected("TRAVEL", travel().put("endAt", "2026-01-01T00:00:00Z"), List.of(), "payload.endAt");
    var t = travel().put("transport", "OTHER");
    rejected("TRAVEL", t, List.of(), "payload.transportOtherReason");
    t.put("transportOtherReason", "bicycle");
    assertThat(check("TRAVEL", t, List.of()).naturalDays()).isEqualTo(1L);
    rejected("TRAVEL", travel().put("transport", "anything"), List.of(), "payload.transport");
  }

  @ParameterizedTest
  @ValueSource(strings = {"2026-02-29", "2026-04-31", "2026-2-1", "0000-01-01"})
  void realCalendarDatesAreRequired(String date) throws Exception {
    rejected("PURCHASE", purchase().put("expectedDate", date), List.of(), "payload.expectedDate");
  }

  @Test
  void threeExpensesMultiReceiptsAndReceiptNumbersSnapshot() throws Exception {
    var result = check("REIMBURSEMENT", reimbursement(), receipts());
    assertThat(result.amount()).isEqualTo("6.06");
    assertThat(result.formSnapshot().path("bindings")).hasSize(3);
    assertThat(result.formSnapshot().path("bindings").get(0).path("receiptNumber").asText())
        .isEqualTo("票1");
    assertThat(result.formSnapshot().path("bindings").get(1).path("receiptNumber").asText())
        .isEqualTo("票2");
  }

  @Test
  void everyExpenseNeedsReceiptsOrExplicitReasonAndNeverBoth() throws Exception {
    rejected("REIMBURSEMENT", reimbursement(), List.of(), "payload.items[0].clientKey");
    var r = reimbursement();
    ((ObjectNode) r.path("items").get(2)).remove("noReceiptReason");
    rejected("REIMBURSEMENT", r, receipts(), "payload.items[2].noReceiptReason");
    r = reimbursement();
    ((ObjectNode) r.path("items").get(0))
        .put("noReceipt", true)
        .put("noReceiptReason", "partial missing");
    rejected("REIMBURSEMENT", r, receipts(), "payload.items[0].noReceipt");
    r = reimbursement();
    ((ObjectNode) r.path("items").get(0)).remove("noReceipt");
    rejected("REIMBURSEMENT", r, receipts(), "payload.items[0].noReceipt");
    r = reimbursement();
    ((ObjectNode) r.path("items").get(0)).put("amount", "0.00");
    rejected("REIMBURSEMENT", r, receipts(), "payload.items[0].amount");
  }

  @Test
  void independentReasonAndEnumRequired() throws Exception {
    var r = reimbursement();
    r.remove("independentReason");
    rejected("REIMBURSEMENT", r, receipts(), "payload.independentReason");
    r = reimbursement();
    ((ObjectNode) r.path("items").get(0)).put("type", "arbitrary");
    rejected("REIMBURSEMENT", r, receipts(), "payload.items[0].type");
  }

  @Test
  void invalidBindingTargetsDuplicateDigestOwnershipAndFormalIdsRejected() throws Exception {
    rejected(
        "REIMBURSEMENT",
        reimbursement(),
        List.of(binding("1", "EXPENSE_RECEIPT", "other", null)),
        "bindings[0].draftTargetKey");
    var list = new ArrayList<>(receipts());
    list.add(binding("1", "EXPENSE_RECEIPT", "e2", "different-number"));
    rejected("REIMBURSEMENT", reimbursement(), list, "bindings[3].fileId");
    var b = binding("1", "EXPENSE_RECEIPT", "e1", null);
    var invalid =
        new FixedBusinessValidator.Binding(
            b.id(),
            b.fileId(),
            b.purpose(),
            b.draftTargetKey(),
            null,
            b.originalName(),
            b.mediaType(),
            b.byteSize(),
            b.sha256(),
            "DELETING",
            "2",
            "99",
            null,
            null,
            b.receiptSha256(),
            b.createdAt(),
            b.integrityStatus(),
            b.verifiedAt());
    rejected("REIMBURSEMENT", reimbursement(), List.of(invalid), "bindings[0].fileId");
    rejected("REIMBURSEMENT", reimbursement(), List.of(invalid), "bindings[0].draftTargetKey");
  }

  @ParameterizedTest
  @CsvSource({"UNKNOWN,2026-11-10T00:00:00Z", "DAMAGED,2026-11-10T00:00:00Z", "OK,NULL"})
  void unverifiedOrDamagedAttachmentsCannotBecomeEvidence(String health, String verified)
      throws Exception {
    var b = binding("1", "GENERAL", null, null);
    var unhealthy =
        new FixedBusinessValidator.Binding(
            b.id(),
            b.fileId(),
            b.purpose(),
            b.draftTargetKey(),
            b.receiptNumber(),
            b.originalName(),
            b.mediaType(),
            b.byteSize(),
            b.sha256(),
            b.status(),
            b.ownerId(),
            b.expenseItemId(),
            b.quoteId(),
            b.fieldKey(),
            b.receiptSha256(),
            b.createdAt(),
            health,
            "NULL".equals(verified) ? null : verified);
    rejected("TRAVEL", travel(), List.of(unhealthy), "bindings[0].fileId");
  }

  @Test
  void verifiedAttachmentMetadataIsFrozen() throws Exception {
    var b = binding("1", "GENERAL", null, null);
    var metadata = check("TRAVEL", travel(), List.of(b)).formSnapshot().path("bindings").get(0);
    assertThat(metadata.path("integrityStatus").asText()).isEqualTo("OK");
    assertThat(metadata.path("verifiedAt").asText()).isEqualTo("2026-11-10T00:00:00Z");
  }

  @Test
  void titleCompleteAndUnknownServerFieldsHavePaths() throws Exception {
    assertThatThrownBy(() -> validator.validate("1", "TRAVEL", " ", "", travel(), List.of()))
        .isInstanceOfSatisfying(
            FixedBusinessFailure.class,
            f ->
                assertThat(f.fieldErrors())
                    .extracting(FixedBusinessFailure.FieldError::path)
                    .contains("title"));
    rejected("TRAVEL", travel().put("naturalDays", 99), List.of(), "payload.naturalDays");
    rejected("TRAVEL", mapper.createObjectNode(), List.of(), "payload.origin");
  }

  @Test
  void relatedApplicationRequiresOwnApprovedFixedSource() throws Exception {
    var r = reimbursement().put("relatedApplicationId", "9007199254740993");
    rejected("REIMBURSEMENT", r, receipts(), "payload.relatedApplicationId");
    for (String owner : List.of("2", "1")) {
      var wrong =
          new FixedBusinessValidator(
              (id, applicant) ->
                  Optional.of(
                      new FixedBusinessRelatedApplicationSource.RelatedApplication(
                          id,
                          owner,
                          owner.equals("1") ? "IN_REVIEW" : "APPROVED",
                          "TRAVEL",
                          "CL-1",
                          "trip",
                          mapper.createObjectNode())));
      assertThatThrownBy(() -> wrong.validate("1", "REIMBURSEMENT", "r", "", r, receipts()))
          .isInstanceOfSatisfying(
              FixedBusinessFailure.class, f -> assertThat(f.status()).isEqualTo(400));
    }
  }

  @Test
  void relatedTravelSnapshotCopiesOnlyWhitelistedSummaryAndDoesNotDeductBudget() throws Exception {
    var original = check("TRAVEL", travel(), List.of()).formSnapshot();
    original.put("privateDetails", "must not copy");
    var linked =
        new FixedBusinessValidator(
            (id, owner) ->
                Optional.of(
                    new FixedBusinessRelatedApplicationSource.RelatedApplication(
                        id, owner, "APPROVED", "TRAVEL", "CL-20261110-1", "trip", original)));
    var r = reimbursement().put("relatedApplicationId", "9007199254740993");
    var result = linked.validate("1", "REIMBURSEMENT", "reimburse", "", r, receipts());
    assertThat(result.amount()).isEqualTo("6.06");
    var summary = result.formSnapshot().path("relatedApplicationSnapshot");
    assertThat(summary.path("applicationId").asText()).isEqualTo("9007199254740993");
    assertThat(summary.path("budget").asText()).isEqualTo("20000.00");
    assertThat(summary.has("privateDetails")).isFalse();
    assertThat(summary.has("payload")).isFalse();
    assertThat(linked.validate("1", "REIMBURSEMENT", "again", "", r, receipts()).amount())
        .isEqualTo("6.06");
    assertThat(original.path("amount").asText()).isEqualTo("20000.00");
  }

  @Test
  void relatedPurchaseSummaryIncludesProjectAndExpectedTotalButNotItems() throws Exception {
    var original = check("PURCHASE", purchase(), List.of()).formSnapshot();
    var linked =
        new FixedBusinessValidator(
            (id, owner) ->
                Optional.of(
                    new FixedBusinessRelatedApplicationSource.RelatedApplication(
                        id, owner, "APPROVED", "PURCHASE", "CG-1", "purchase", original)));
    var result =
        linked.validate(
            "1",
            "REIMBURSEMENT",
            "r",
            "",
            reimbursement().put("relatedApplicationId", "3"),
            receipts());
    var summary = result.formSnapshot().path("relatedApplicationSnapshot");
    assertThat(summary.path("expectedTotal").asText()).isEqualTo("2.02");
    assertThat(summary.path("project").asText()).isEqualTo("project");
    assertThat(summary.has("items")).isFalse();
  }

  @Test
  void incompatibleSourceSnapshotFailsClosed() throws Exception {
    var linked =
        new FixedBusinessValidator(
            (id, owner) ->
                Optional.of(
                    new FixedBusinessRelatedApplicationSource.RelatedApplication(
                        id,
                        owner,
                        "APPROVED",
                        "PURCHASE",
                        "CG-1",
                        "p",
                        mapper.createObjectNode())));
    assertThatThrownBy(
            () ->
                linked.validate(
                    "1",
                    "REIMBURSEMENT",
                    "r",
                    "",
                    reimbursement().put("relatedApplicationId", "3"),
                    receipts()))
        .isInstanceOfSatisfying(
            FixedBusinessFailure.class,
            f -> {
              assertThat(f.status()).isEqualTo(503);
              assertThat(f.code()).isEqualTo("RELATED_APPLICATION_SNAPSHOT_UNAVAILABLE");
            });
  }
}
