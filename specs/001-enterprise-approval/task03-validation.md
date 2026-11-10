# TASK-03 身份与会话验证记录

记录日期：2026-11-10。依据为[需求规格](<spec.md>) REQ-01/REQ-12、[技术计划](<plan.md>)及[工程约束](<../../.specify/memory/constitution.md>)。本次完成身份与会话后端，不包含 UI、TASK-04 管理 API 或完整审批业务验收。

## 1 实现与运行条件

[AuthService](<../../backend/src/main/java/cn/edu/approval/identity/AuthService.java>) 使用 JDBC、事务模板与可注入时钟，提供注册、持久会话、资料维护、改密、重申和内部管理撤销 hook。[身份控制器](<../../backend/src/main/java/cn/edu/approval/security/IdentityController.java>)、[请求过滤器](<../../backend/src/main/java/cn/edu/approval/security/IdentityRequestFilter.java>)和 [Web 凭据协议](<../../backend/src/main/java/cn/edu/approval/security/WebCredentials.java>)负责 Cookie/Bearer 分离、Origin/CSRF、状态限制、请求白名单及响应契约。

服务仅在 `db & !migrate` profile 加载。迁移用独立账号先执行，常驻服务使用 `approval_app` 并关闭 Flyway；运行方式和环境变量见[后端说明](<../../backend/README.md>)。本轮未新增依赖，没有生成 TASK-09/TASK-10 的流程或模板执行代码。

实现检查中修复了三个边界：

- Unicode 小写可能将 `K` 转为 ASCII `k`。用户名和邮箱现先检查原始 ASCII 字符集，再小写；登录也不允许该字符匹配已有 ASCII 账户。
- `Timestamp.from` 写入 DATETIME 可能受 JVM/驱动默认时区影响。现写入 UTC `LocalDateTime`，会话期限由 MySQL `DATE_FORMAT` 取无时区字段，再按 UTC 解析。
- Jackson 默认可能将数字或布尔值转换成字符串。身份配置拒绝此类转换，ID、版本、用户名和密码等文本字段不能用标量替代。

## 2 构建与模拟测试

环境：Windows PowerShell、Java 17.0.17+10、Maven 3.9.11、Spring Boot 3.5.7。仓库根目录执行：

```powershell
. ./scripts/use-java.ps1
./backend/mvnw.cmd -f backend/pom.xml -B -ntp spotless:apply verify
```

最终构建于 `2026-11-10T15:12:19+08:00` 完成，退出码 0，`BUILD SUCCESS`；Spotless、Checkstyle、编译及打包均通过。JUnit 结果为 `Tests run: 26, Failures: 0, Errors: 0, Skipped: 0`：

| 测试 | 数量 | 核对内容 |
| --- | ---: | --- |
| [身份服务单测](<../../backend/src/test/java/cn/edu/approval/identity/AuthServiceTest.java>) | 16 | 规范化、密码、事务失败、限流、撤销、当前状态、版本、UTC 与可控时钟边界 |
| [身份 HTTP 测试](<../../backend/src/test/java/cn/edu/approval/security/IdentityHttpTest.java>) | 8 | Cookie/Bearer、匿名与已登录 CSRF/Origin、状态入口、字段类型、响应及安全头 |
| [骨架 HTTP 测试](<../../backend/src/test/java/cn/edu/approval/HealthHttpTest.java>) | 2 | 默认 profile 的健康响应和封闭入口保持不变 |

服务单测使用模拟 JDBC；这些结果不单独证明 MySQL 的锁、唯一约束或事务行为。另运行 Python 全部 70 项测试，启用 `DEPLOY_JAVA_HOME` 后无跳过、无失败；规格检查覆盖 12 组需求、16 个任务和 22 个验收定义。

## 3 真实 MySQL 与成品 HTTP 验证

先完成上述打包，再执行[独立验证脚本](<../../scripts/verify_task03_mysql.py>)：

```powershell
. ./scripts/use-java.ps1
python scripts/verify_task03_mysql.py --skip-build
```

脚本创建一次性 MySQL 8.4.7，先迁移 V1—V4，再启动真实 `db` profile 成品应用；不是 H2 或 MockMvc。运行账号为 `approval_app`，迁移账号为 `approval_migrator`，运行期间 Flyway 关闭。迁移和两次应用启动均使用同一临时冻结成品副本，避免 Windows 运行中重打包改变加载内容。

结果写入[原始 JSON](<../../backend/evidence/task03-mysql.json>)：本地时间 `2026-11-10 15:15:20 +08:00`，退出码 0，11/11 组 PASS、0 FAIL；共 133 次 HTTP 请求、2 次真实应用启动，44 个成功响应逐项核对 `data,requestId,serverTime`、追踪头及 `no-store`。成品 SHA-256 为 `f3c47b40d8ddc44306da4cb005406dedb29afdfedf33d8a1d1491776c767f7d9`。

| 实测组 | 观察到的结果 |
| --- | --- |
| 独立启动与最小权限 | 空库 V1—V4 成功；运行账号不能 DDL 或读取迁移历史 |
| 原生注册、登录与秘密存储 | 新用户 PENDING、无角色/确认部门、有 inbox；Argon2id 为 19456 KiB/2/1、盐16字节/摘要32字节；会话仅存 SHA-256。非 ASCII 反例和 JSON 数字/布尔文本替代被拒绝 |
| Web/原生传输及 CSRF | 精确 Origin、预认证与会话绑定 CSRF、HttpOnly/Path/SameSite、sid 轮换通过；旧 proof、混合/重复凭据及跨客户端传输被拒绝；Web 响应无令牌 |
| 唯一竞争与事务回滚 | 用户名和邮箱规范化竞争各为 201+409；角色/状态越权字段被拒。inbox 插入触发器故障返回脱敏、可重试503，user/inbox 均回滚；去除故障后注册201 |
| 账号与登录 IP 并发限流 | 账号8并发为5×401+3×429，计数5；登录 IP 24并发为20×401+4×429，计数20；重启应用后两者仍429 |
| 注册 IP 并发限流 | 12并发为10×201+2×429，恰好10个用户与10个inbox，计数10 |
| 退出与改密撤销 | 重复退出幂等且只撤当前会话；改密后 Web/原生全部旧会话失效，旧密码被拒，Web Cookie 过期 |
| 强制改密与停用 | SQL 设置强制改密后仅开放相应身份入口；改密清标志并撤全。SQL 设置 DISABLED 后，已有会话和新登录均被拒 |
| 空闲与绝对期限 | JVM 使用 Asia/Shanghai，初始存储时间距 UTC 小于15秒，绝对期限差604800秒；SQL 调整会话后，空闲29分钟可用、30分钟及以后拒绝，绝对截止后拒绝；精确等于截止点另由可控时钟单测核对 |
| 资料版本与拒绝后重申 | 资料更新增版本，旧版本409；SQL-REJECTED 账户持双版本重申后回 PENDING、清拒因、增用户/配置版本；无角色越权 |
| 真实锁等待与恢复 | 独立连接持用户行锁7秒；请求在配置的3秒锁等待后返回脱敏503、`retryable=true`；释放锁后同一会话200 |

错误响应核对固定 `code,message,fieldErrors,requestId,retryable`，通用消息、不携带 SQL 或凭据。应用、临时成品副本和 MySQL 容器在执行后清理，另查进程与容器确认无残留。

## 4 边界与后续依赖

- 当前账号与 IP 限流是固定窗口，不是滑动窗口。成功登录清当前账号失败桶。入口仅使用 `remoteAddr`，不信任任意转发头；反代后用户可能共享 IP 桶，生产可信代理提取未验证。
- 本轮为本机 HTTP，测试显式 `Secure=false`。生产 HTTPS、Secure Cookie、反代、多实例及小程序真机链路均为 NOT_RUN，不用本轮结果替代。
- `updateProfile(p,name,email)` 恒返回400；HTTP 必须传 `expectedVersion`。重申必须传用户和配置双版本。
- `resetPassword(userId,temporaryPassword)`、`disable(userId)` 只是包内 hook，要求已有实际外层管理事务。TASK-04 还须实现管理员鉴权、配置锁、对象版本、最后管理员/引用检查与审计。SQL 构造 REJECTED、DISABLED 或强制改密夹具不是管理 API 验收。
- UI、组织角色管理和申请业务没有在本任务实现。T01—T22 保留 NOT_RUN；TASK-03 后端完成不等于这些跨任务验收整体通过。

TASK-02 历史结果保留在[原验证记录](<task-02-validation.md>)，V4 增量在[工程验证记录](<engineering-validation.md>)，本记录与两份历史证据独立保存。
