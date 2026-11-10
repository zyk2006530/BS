package cn.edu.approval.organization;

/** Non-executing boundary for student-owned configuration/node reference checks. */
public interface ResponsibilityGuard {
  /** Reject a destructive user change unless all responsibility references are known clear. */
  void requireUserClear(String userId, Object activePolicyId);

  /** Reject disabling a department while business references cannot safely be retired. */
  void requireDepartmentClear(String departmentId, Object activePolicyId);
}
