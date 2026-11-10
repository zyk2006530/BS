# TASK04 组织与授权管理 API

本文记录当前后端管理接口的请求字段、事务约束和责任检查边界，不作为构建、MySQL 回归或业务验收通过证明。接口只在 `db & !migrate` 配置下启用，统一前缀为 `/api/v1/admin`。

实现入口：[OrganizationController](<../../backend/src/main/java/cn/edu/approval/organization/OrganizationController.java>)、[OrganizationService](<../../backend/src/main/java/cn/edu/approval/organization/OrganizationService.java>)。

## 1. 调用前提与数据类型

调用者必须持有当前有效会话，账号状态为 `ACTIVE`，不处于强制改密状态，并在数据库中持有 `SYS_ADMIN`。服务不把登录时的角色或请求中提供的角色当成管理权限证明。`PENDING`、`REJECTED`、`DISABLED` 用户不能调用管理接口。

Web 使用 `sid` Cookie；写请求还需符合身份接口的可信 `Origin` 和会话绑定 CSRF 校验。原生端使用 `MINI_PROGRAM` 会话的 Bearer token，不能同时携带 Cookie。接口继承身份层的请求体上限、无缓存响应和脱敏错误约定。管理员身份不等于申请正文读取权限，也不授予统计权限。

- ID、对象版本、配置版本使用十进制字符串，不接受 JSON 数值替代字符串。
- `enabled`、`clearManager`、`enterpriseConfirmed` 是 JSON 布尔值。
- 时间使用带 `Z` 的 UTC ISO 8601 字符串；数据库写入按 UTC 处理。
- 写请求必须有非空 `reason`，最长 2000 个 Unicode 码点；拒绝确认的原因最长 500 个码点。
- 未知请求字段不接受。部门名称最长 80 个 Unicode 码点，不能空白。

成功响应包含 `data`、`requestId`、`serverTime`。请求 ID 也出现在 `X-Request-Id` 响应头。下文描述的是 `data` 中的字段，不省略外层响应约定。错误使用 `code`、`message`、`fieldErrors`、`requestId`、`retryable`，不返回 SQL、密码或哈希。

## 2. 先读取配置和对象版本

| GET 路径 | 可选查询参数 | 内容 |
| --- | --- | --- |
| `/config` | 无 | `configVersion`、`activePolicyVersionId`、`dependencyGuard` |
| `/users` | `status`、`departmentId`、`page`、`pageSize` | 用户状态、所属/申请部门、角色、对象版本、强制改密标记、拒绝原因、负责人关系数量；不返回密码哈希 |
| `/departments` | `page`、`pageSize` | 部门 ID、名称、启用状态、负责人 ID、对象版本 |
| `/grants` | `userId`、`ability`、`scope`、`page`、`pageSize` | 授权能力、范围、生效期间、撤销时间、授权人和原因；包括历史撤销记录 |
| `/audit-records` | `actorId`、`targetType`、`action`、`page`、`pageSize` | 操作者、目标、动作、前后值、原因、请求 ID、UTC 创建时间 |

列表返回 `items`、`total`、`page`、`pageSize`。后三项均为字符串；默认第 1 页、每页 20 条，每页最多 100 条。用户状态筛选只接受 `PENDING`、`REJECTED`、`ACTIVE`、`DISABLED`。审计目标筛选接受 `USER`、`DEPARTMENT`、`DATA_GRANT`；动作筛选是 1–64 个大写字母或下划线。

用户列表的 `mustChangePassword`、部门列表的 `enabled` 是布尔值。所有管理读取也在服务内检查当前账号、角色及会话，不依赖页面是否显示菜单。

## 3. 用户维护

以下表格中的用户对象写操作均要求 `expectedVersion`、`expectedConfigVersion`、`reason`。两个版本分别取自目标用户的 `rowVersion` 和 `/config` 的 `configVersion`。

| 方法与路径 | 其余请求字段 | 约束 |
| --- | --- | --- |
| `POST /users/{id}/confirm` | `departmentId` | 目标只能是 `PENDING`；部门必须启用。转为 `ACTIVE`，写入确认部门，清空拒绝原因，补入 `EMPLOYEE`，不自动授予管理角色 |
| `POST /users/{id}/reject` | 无 | 目标只能是 `PENDING`；转为 `REJECTED`，保存拒绝原因 |
| `PATCH /users/{id}` | `status`、`departmentId`，至少一个非空 | 只维护已确认的 `ACTIVE`/`DISABLED` 用户；新状态只能是这两者。省略部门表示不变，不能用此接口清空部门；目标部门必须启用 |
| `PUT /users/{id}/roles` | `roles` 字符串数组 | 替换完整角色集合，只允许 `ACTIVE` 用户；拒绝未知角色、重复角色和空元素 |
| `POST /users/{id}/reset-password` | `temporaryPassword` | 8–64 个 Unicode 码点，不裁剪或归一化；写 Argon2id 哈希、设置强制改密并撤销该用户全部会话 |

可配置角色为 `EMPLOYEE`、`DEPT_HEAD`、`FINANCE_APPROVER`、`ENTERPRISE_HEAD`、`SYS_ADMIN`。角色替换不是增量添加；调用方应在读取当前集合后提交完整期望集合。

停用用户、调整所属部门或移除审批角色要先通过责任检查。停用会撤销该用户全部会话，重新启用不会恢复旧会话。停用最后一名 `ACTIVE` 系统管理员或移除其 `SYS_ADMIN` 返回 `LAST_ACTIVE_ADMIN`。该判断与所有管理写共享配置锁，不能依靠两个并发请求绕过。

用户维护成功返回 `targetId`、新的 `rowVersion` 和 `configVersion`。密码重置响应和审计只包含强制改密、会话撤销等状态，不包含临时密码或哈希。临时密码由管理员通过接口之外的受控渠道交付。

## 4. 部门与负责人

`POST /departments` 返回 201。请求字段为 `name`、可选的 `enabled`、`expectedConfigVersion`、`reason`。`enabled` 省略时为 `true`。`managerUserId` 必须省略或为 `null`；新部门需先创建，再调整人员所属，最后分配负责人，不能引用尚不属于新部门的人。

`PATCH /departments/{id}` 的字段为：

| 字段 | 含义 |
| --- | --- |
| `name` | 非空时替换名称，省略或 `null` 时不变 |
| `enabled` | 非空时修改启用状态 |
| `managerUserId` | 非空时分配负责人；省略或 `null` 不表示清空 |
| `clearManager` | 只有显式 `true` 才清空负责人；不能同时提交非空 `managerUserId` |
| `expectedVersion` | 部门当前 `rowVersion` |
| `expectedConfigVersion`、`reason` | 配置版本与修改原因 |

负责人必须是本部门的 `ACTIVE` 用户，且未被要求强制改密。部门负责人关系和 `DEPT_HEAD` 同事务维护：新负责人补入角色并递增用户版本；旧负责人不再负责任何部门时移除角色并递增用户版本。用户角色接口不能脱离负责人关系单独授予或移除 `DEPT_HEAD`。

停用部门前必须清空负责人，并通过部门责任检查。更换或清空负责人也要检查旧负责人的责任；检查失败时，部门变更、角色变更、版本和审计全部回滚。接口不会替审批中的申请自动寻找替代负责人或交接任务。

部门创建成功返回 `targetId`、`rowVersion`、`configVersion`。部门修改还返回 `userVersions`，其键为受影响负责人 ID、值为最新用户版本；调用方据此更新用户视图，或重新读取用户列表。

## 5. 显式数据授权

`POST /grants` 返回 201。字段为 `userId`、`ability`、`scope`、`departmentId`、`validFrom`、`validUntil`、`enterpriseConfirmed`、`expectedConfigVersion`、`reason`。

- `ability` 只能是 `READ_BODY` 或 `STATISTICS`，两者独立；统计授权不隐含正文授权。
- `scope=DEPARTMENT` 时必须提供启用部门的 `departmentId`。
- `scope=ENTERPRISE` 时 `departmentId` 必须省略或为 `null`，并显式提交 `enterpriseConfirmed=true`。这是请求内的企业范围确认，不替代调用者管理权限校验。
- 授权对象必须为 `ACTIVE` 用户。`validUntil` 必须晚于 `validFrom`，并晚于当前时间；允许安排未来开始的授权。

创建返回 `grantId`、`targetId` 和新的 `configVersion`。授权记录没有对象 `rowVersion` 字段，因此创建和撤销仅使用全局配置版本，不虚构 `expectedVersion`。

`POST /grants/{id}/revoke` 请求只有 `expectedConfigVersion`、`reason`。它写入 `revoked_at`，保留授权历史，不物理删除。重复撤销返回 `GRANT_ALREADY_REVOKED`。创建授权只是维护权限元数据，不执行申请查询、正文导出或统计任务；各数据接口仍需逐次检查能力、范围、有效期及撤销状态。

## 6. 写事务、版本冲突和审计

写事务使用 `READ_COMMITTED`：

1. 锁定 `system_config` 单例行。
2. 无锁读取调用者当前账号与 `SYS_ADMIN` 角色进行预检，未通过时不读取目标用户、部门或授权，避免用 403/404 差异探测目标是否存在。
3. 在配置锁保护下发现受影响的用户，再按用户 ID 数值升序加用户行锁。负责人变更包括新、旧负责人和操作者。
4. 锁定操作者当前会话，重新校验账号状态、强制改密、用户归属、客户端类型、撤销状态、30 分钟空闲期限和绝对到期时间，再读取当前管理员角色。
5. 比较对象版本与配置版本，检查原因及业务约束；提交业务变更、审计记录和配置版本递增。

无锁预检不替代锁后授权。客户端不能把“页面加载时是管理员”当成继续修改的依据。版本缺失或格式不合法返回 400，版本不一致返回 `409 VERSION_CONFLICT`，不得静默覆盖；调用方重新读取对象和配置后，应由操作者重新确认修改内容。

每个成功管理写入都记录操作者、目标、动作、前后状态、原因和请求 ID。审计写入失败时，业务修改和版本递增一并回滚。数据库故障和锁超时统一返回 `503 DATABASE_UNAVAILABLE`，响应 `retryable=true`，不泄露数据库内部信息。

## 7. 默认责任检查的边界

[ResponsibilityGuard](<../../backend/src/main/java/cn/edu/approval/organization/ResponsibilityGuard.java>) 是非执行性的责任引用检查接口。当前 [BaselineResponsibilityGuard](<../../backend/src/main/java/cn/edu/approval/organization/BaselineResponsibilityGuard.java>) 只读取既有基线数据，不创建、迁移或解释学生负责的模板、流程定义、流程实例和任务表。

当前 `/config` 返回 `dependencyGuard=BASELINE_FAIL_CLOSED`。只要存在生效策略指针，或存在任意 `IN_REVIEW` 申请，需要责任核验的破坏性维护便以 `RESPONSIBILITY_CHECK_UNAVAILABLE` 拒绝。它不声称已解析策略的人员引用，也不把“不知道是否被引用”判断为可删除。没有上述全局阻断条件时，仍检查负责部门关系；部门停用还检查部门下的启用用户。

这个边界会保守阻止与当前策略无关的人员调整，但不会误删未能解析的责任引用。接入学生实现时，需要由其提供只读引用检查适配器，明确判断生效策略和未结束实例中的责任；接口不承担自动交接或流程执行。不能为使维护操作成功而删除策略指针、忽略审批中申请或放宽默认检查。

## 8. 常见错误

| HTTP | 代码 | 含义 |
| --- | --- | --- |
| 400 | `INVALID_INPUT` | 请求字段、版本格式、原因、分页或期间不合法 |
| 401 | `INVALID_CREDENTIALS` | 会话缺失、归属不符、撤销或到期 |
| 403 | `FORBIDDEN` | 当前账号、强制改密状态或当前角色不允许管理 |
| 404 | `NOT_FOUND` | 管理员授权校验后发现目标不存在 |
| 409 | `VERSION_CONFLICT` | 对象或配置版本已改变 |
| 409 | `USER_NOT_PENDING` / `CONFIRMATION_REQUIRED` / `ACTIVE_USER_REQUIRED` | 目标用户状态不适合本操作 |
| 409 | `LAST_ACTIVE_ADMIN` | 将失去最后一名启用系统管理员 |
| 409 | `USER_HAS_RESPONSIBILITIES` / `DEPARTMENT_IN_USE` | 仍存在可确认的组织引用 |
| 409 | `RESPONSIBILITY_CHECK_UNAVAILABLE` | 生效策略或审批中实例责任尚不能安全解析 |
| 409 | `MANAGER_ROLE_MUST_MATCH_RELATION` / `INVALID_DEPARTMENT_MANAGER` | 负责人角色与关系不一致，或人员资格不符合 |
| 409 | `DISABLED_DEPARTMENT_HAS_MANAGER` / `CREATE_DEPARTMENT_BEFORE_ASSIGNING_MANAGER` | 部门停用或创建顺序不符合约束 |
| 409 | `GRANT_ALREADY_REVOKED` | 授权已经撤销 |
| 503 | `DATABASE_UNAVAILABLE` | 数据库故障或锁等待超时，事务没有部分提交 |
