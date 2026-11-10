package cn.edu.approval.organization;

import cn.edu.approval.identity.AuthFailure;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Conservative adapter until the student-owned policy/node reference reader is supplied. */
@Component
@Profile("db & !migrate")
public class BaselineResponsibilityGuard implements ResponsibilityGuard {
  private final JdbcTemplate jdbc;

  public BaselineResponsibilityGuard(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void requireUserClear(String userId, Object activePolicyId) {
    if (activePolicyId != null
        || count("SELECT COUNT(*) FROM application WHERE status='IN_REVIEW'") > 0) {
      throw new AuthFailure(409, "RESPONSIBILITY_CHECK_UNAVAILABLE", 0);
    }
    if (count("SELECT COUNT(*) FROM department WHERE manager_user_id=?", userId) > 0) {
      throw new AuthFailure(409, "USER_HAS_RESPONSIBILITIES", 0);
    }
  }

  @Override
  public void requireDepartmentClear(String departmentId, Object activePolicyId) {
    if (activePolicyId != null
        || count("SELECT COUNT(*) FROM application WHERE status='IN_REVIEW'") > 0) {
      throw new AuthFailure(409, "RESPONSIBILITY_CHECK_UNAVAILABLE", 0);
    }
    if (count("SELECT COUNT(*) FROM `user` WHERE department_id=? AND status='ACTIVE'", departmentId)
        > 0) {
      throw new AuthFailure(409, "DEPARTMENT_IN_USE", 0);
    }
  }

  private long count(String sql, Object... args) {
    Long count = jdbc.queryForObject(sql, Long.class, args);
    return count == null ? 0 : count;
  }
}
