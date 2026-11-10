package cn.edu.approval.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** No flow schema is created here. Missing/incompatible student schema fails closed. */
@Configuration
@Profile("db & !migrate")
public class FixedBusinessRelatedApplicationConfiguration {
  @Bean
  @ConditionalOnMissingBean(FixedBusinessRelatedApplicationSource.class)
  public FixedBusinessRelatedApplicationSource fixedBusinessRelatedApplicationSource(
      JdbcTemplate jdbc, ObjectMapper mapper) {
    return (applicationId, applicantId) -> {
      var applications =
          jdbc.queryForList(
              "SELECT id,applicant_id,status,type,application_no,title FROM application WHERE id=? AND applicant_id=? AND status='APPROVED' AND type IN ('PURCHASE','TRAVEL')",
              applicationId,
              applicantId);
      if (applications.isEmpty()) return Optional.empty();
      var application = applications.get(0);
      try {
        List<String> snapshots =
            jdbc.queryForList(
                "SELECT form_snapshot FROM flow_instance WHERE application_id=?",
                String.class,
                applicationId);
        if (snapshots.size() != 1 || snapshots.get(0) == null) throw unavailable();
        return Optional.of(
            new FixedBusinessRelatedApplicationSource.RelatedApplication(
                application.get("id").toString(),
                application.get("applicant_id").toString(),
                application.get("status").toString(),
                application.get("type").toString(),
                application.get("application_no") == null
                    ? ""
                    : application.get("application_no").toString(),
                application.get("title").toString(),
                mapper.readTree(snapshots.get(0))));
      } catch (DataAccessException | JsonProcessingException ex) {
        throw unavailable();
      }
    };
  }

  static FixedBusinessFailure unavailable() {
    return new FixedBusinessFailure(
        503,
        "RELATED_APPLICATION_SNAPSHOT_UNAVAILABLE",
        List.of(
            new FixedBusinessFailure.FieldError(
                "payload.relatedApplicationId",
                "Approved application snapshot is unavailable or incompatible")));
  }
}
