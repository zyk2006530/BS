package cn.edu.approval.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;

/**
 * Read-only compatibility seam for TASK09's immutable form_snapshot; never reads draft payloads.
 */
public interface FixedBusinessRelatedApplicationSource {
  Optional<RelatedApplication> find(String applicationId, String applicantId);

  record RelatedApplication(
      String id,
      String applicantId,
      String status,
      String type,
      String applicationNo,
      String title,
      JsonNode formSnapshot) {}
}
