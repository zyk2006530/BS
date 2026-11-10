package cn.edu.approval.demo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Separate demo seed, enabled only by the {@code demo} Spring profile.
 *
 * <p>It creates documented demo duties without a universal default password. The initial password
 * comes only from {@code APP_DEMO_PASSWORD}; only the Argon2id hash is stored. Existing users are
 * never overwritten: a rerun keeps their password, name, status and roles. Inbox rows are repaired
 * for every user.
 */
@Component
@Profile("demo")
@Order(1)
public class DemoDataInitializer implements ApplicationRunner {
  private static final String PASSWORD_ENV = "APP_DEMO_PASSWORD";
  private static final int ARGON2_SALT_BYTES = 16;
  private static final int ARGON2_HASH_BYTES = 32;
  private static final int ARGON2_PARALLELISM = 1;
  private static final int ARGON2_MEMORY_KIB = 19_456;
  private static final int ARGON2_ITERATIONS = 2;

  private final JdbcTemplate jdbc;
  private final Argon2PasswordEncoder passwordEncoder =
      new Argon2PasswordEncoder(
          ARGON2_SALT_BYTES,
          ARGON2_HASH_BYTES,
          ARGON2_PARALLELISM,
          ARGON2_MEMORY_KIB,
          ARGON2_ITERATIONS);

  public DemoDataInitializer(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    String password = System.getenv(PASSWORD_ENV);
    if (password == null || password.isEmpty()) {
      throw new IllegalStateException(PASSWORD_ENV + " must be set for the demo profile");
    }
    int passwordLength = password.codePointCount(0, password.length());
    if (passwordLength < 8 || passwordLength > 64) {
      throw new IllegalStateException(PASSWORD_ENV + " must contain 8 to 64 Unicode characters");
    }

    Map<String, Long> departments = ensureDepartments();
    Map<String, Long> roles = ensureRoles();

    List<DemoUser> users = demoUsers();
    int insertedUsers = 0;
    for (DemoUser user : users) {
      Long userId = findUser(user.username());
      if (userId != null) {
        continue;
      }
      jdbc.update(
          """
          INSERT INTO `user` (`username`, `email`, `password_hash`, `name`,
            `requested_department_id`, `department_id`, `status`, `rejection_reason`,
            `must_change_password`, `row_version`, `created_at`, `updated_at`)
          VALUES (?, ?, ?, ?, ?, ?, ?, NULL, 0, 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
          """,
          user.username(),
          user.email(),
          passwordEncoder.encode(password),
          user.displayName(),
          departments.get(user.requestedDepartment()),
          user.status().equals("ACTIVE") ? departments.get(user.department()) : null,
          user.status());
      userId = findUser(user.username());
      if (userId == null) {
        throw new IllegalStateException(
            "Demo user insert did not produce a row: " + user.username());
      }
      insertedUsers++;
      for (String roleCode : user.roles()) {
        jdbc.update(
            "INSERT INTO `user_role` (`user_id`, `role_id`, `created_at`)"
                + " VALUES (?, ?, UTC_TIMESTAMP(6))",
            userId,
            roles.get(roleCode));
      }
    }

    assignManagerIfMissing("研发部", "rd_head");
    assignManagerIfMissing("市场部", "mkt_head");
    assignManagerIfMissing("财务部", "fin_head");

    jdbc.update(
        """
        INSERT INTO `inbox_state` (`user_id`, `last_delivery_seq`, `revision`, `updated_at`)
        SELECT `u`.`id`, 0, 0, UTC_TIMESTAMP(6)
        FROM `user` AS `u`
        WHERE NOT EXISTS (SELECT 1 FROM `inbox_state` AS `s` WHERE `s`.`user_id` = `u`.`id`)
        ORDER BY `u`.`id`
        """);

    Long inboxRows = jdbc.queryForObject("SELECT COUNT(*) FROM `inbox_state`", Long.class);
    System.out.println(
        "DEMO_SEED: users="
            + users.size()
            + " newly_inserted="
            + insertedUsers
            + " inbox_rows="
            + inboxRows);
  }

  private Map<String, Long> ensureDepartments() {
    for (String name : List.of("研发部", "市场部", "财务部")) {
      jdbc.update(
          """
          INSERT INTO `department` (`name`, `enabled`, `manager_user_id`, `row_version`,
            `created_at`, `updated_at`)
          SELECT ?, 1, NULL, 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
          WHERE NOT EXISTS (SELECT 1 FROM `department` WHERE `name` = ?)
          """,
          name,
          name);
    }
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT `name`, `id` FROM `department` WHERE `name` IN (?, ?, ?)", "研发部", "市场部", "财务部");
    if (rows.size() != 3) {
      throw new IllegalStateException("Demo seed requires exactly three baseline departments");
    }
    return rows.stream()
        .collect(
            Collectors.toMap(
                row -> (String) row.get("name"), row -> ((Number) row.get("id")).longValue()));
  }

  private Map<String, Long> ensureRoles() {
    for (String code :
        List.of("EMPLOYEE", "DEPT_HEAD", "FINANCE_APPROVER", "ENTERPRISE_HEAD", "SYS_ADMIN")) {
      jdbc.update(
          """
          INSERT INTO `role` (`code`, `name`, `description`, `created_at`)
          SELECT ?, ?, '', UTC_TIMESTAMP(6)
          WHERE NOT EXISTS (SELECT 1 FROM `role` WHERE `code` = ?)
          """,
          code,
          roleDisplayName(code),
          code);
    }
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT `code`, `id` FROM `role` WHERE `code` IN (?, ?, ?, ?, ?)",
            "EMPLOYEE",
            "DEPT_HEAD",
            "FINANCE_APPROVER",
            "ENTERPRISE_HEAD",
            "SYS_ADMIN");
    if (rows.size() != 5) {
      throw new IllegalStateException("Demo seed requires exactly five baseline roles");
    }
    return rows.stream()
        .collect(
            Collectors.toMap(
                row -> (String) row.get("code"), row -> ((Number) row.get("id")).longValue()));
  }

  private static String roleDisplayName(String code) {
    return switch (code) {
      case "EMPLOYEE" -> "员工";
      case "DEPT_HEAD" -> "部门负责人";
      case "FINANCE_APPROVER" -> "财务审批人";
      case "ENTERPRISE_HEAD" -> "企业负责人";
      case "SYS_ADMIN" -> "系统管理员";
      default -> throw new IllegalArgumentException("Unknown role code: " + code);
    };
  }

  private Long findUser(String username) {
    List<Long> ids =
        jdbc.queryForList("SELECT `id` FROM `user` WHERE `username` = ?", Long.class, username);
    if (ids.size() > 1) {
      throw new IllegalStateException(
          "Duplicate username cannot exist under the unique constraint");
    }
    return ids.isEmpty() ? null : ids.get(0);
  }

  private void assignManagerIfMissing(String departmentName, String username) {
    Long userId = findUser(username);
    if (userId == null) {
      return;
    }
    jdbc.update(
        """
        UPDATE `department`
        SET `manager_user_id` = ?, `updated_at` = UTC_TIMESTAMP(6),
            `row_version` = `row_version` + 1
        WHERE `name` = ? AND `manager_user_id` IS NULL
        """,
        userId,
        departmentName);
  }

  private static List<DemoUser> demoUsers() {
    String rd = "研发部";
    String mkt = "市场部";
    String fin = "财务部";
    List<DemoUser> users = new ArrayList<>();
    users.add(new DemoUser("rd_head", "研发负责人", rd, rd, "ACTIVE", List.of("DEPT_HEAD")));
    users.add(new DemoUser("rd_staff", "研发员工", rd, rd, "ACTIVE", List.of("EMPLOYEE")));
    users.add(new DemoUser("mkt_head", "市场负责人", mkt, mkt, "ACTIVE", List.of("DEPT_HEAD")));
    users.add(new DemoUser("mkt_staff", "市场员工", mkt, mkt, "ACTIVE", List.of("EMPLOYEE")));
    users.add(
        new DemoUser(
            "fin_head", "财务负责人", fin, fin, "ACTIVE", List.of("DEPT_HEAD", "FINANCE_APPROVER")));
    users.add(new DemoUser("finance", "财务审批人", fin, fin, "ACTIVE", List.of("FINANCE_APPROVER")));
    users.add(
        new DemoUser("finance_backup", "财务备选审批人", fin, fin, "ACTIVE", List.of("FINANCE_APPROVER")));
    users.add(new DemoUser("boss", "企业负责人", rd, rd, "ACTIVE", List.of("ENTERPRISE_HEAD")));
    users.add(
        new DemoUser("boss_backup", "企业备选负责人", mkt, mkt, "ACTIVE", List.of("ENTERPRISE_HEAD")));
    users.add(new DemoUser("admin", "系统管理员", fin, fin, "ACTIVE", List.of("SYS_ADMIN")));
    users.add(new DemoUser("pending_user", "待确认用户", fin, fin, "PENDING", List.of()));
    return users;
  }

  private record DemoUser(
      String username,
      String displayName,
      String department,
      String requestedDepartment,
      String status,
      List<String> roles) {
    private String email() {
      return username + "@demo.example.test";
    }
  }
}
