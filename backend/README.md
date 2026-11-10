# Backend

Java 17.0.17+10、Maven 3.9.11、Spring Boot 3.5.7。默认启动仍是封闭骨架；显式启用 `db` 且未启用 `migrate` 时加载身份、组织管理及草稿查询服务。身份历史证据见 [TASK-03 验证记录](<../specs/001-enterprise-approval/task03-validation.md>)；TASK-04/05 的公共写协议见[草稿版本与重试协议](<../specs/001-enterprise-approval/draft-write-protocol.md>)。

## 启动模式

| profile | 行为 |
| --- | --- |
| 默认 | 不加载数据库；仅健康入口开放，其余入口拒绝 |
| `db` | 连接 MySQL，加载身份、组织管理和草稿查询入口；常驻进程须显式关闭 Flyway |
| `db,migrate` | 迁移用途；不加载 TASK-03 身份服务和控制器，迁移流程结束退出 |
| `db,demo` | 额外运行独立演示种子，须显式提供 `APP_DEMO_PASSWORD`；不用于生产 |

`GET /api/v1/health` 保留以下骨架响应，不表示认证、数据库及业务流程已经就绪：

```json
{"status":"UP","stage":"SKELETON","businessReady":false}
```

数据库配置见 [application-db.yml](<src/main/resources/application-db.yml>)。运行身份服务前，通过进程环境设置：

- `SPRING_PROFILES_ACTIVE=db`。
- `SPRING_FLYWAY_ENABLED=false`：常驻身份服务不执行迁移；先用独立 `db,migrate` 一次性作业完成迁移，Compose 已按此拆分。
- `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`：MySQL JDBC 地址与业务账号；明确使用 UTC，例如 JDBC 参数 `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`。
- `DB_MIGRATION_USER`、`DB_MIGRATION_PASSWORD`：仅一次性迁移作业注入的独立账号；常驻服务不携带迁移凭据，也不授予业务账号迁移权限。
- `APP_CSRF_SECRET`：外部注入至少 32 字节随机密钥；多实例必须一致，不入仓库、日志或命令行历史。
- `APP_WEB_ORIGIN`：精确可信 Origin，默认 `https://localhost`；不含路径、用户信息、查询或片段。
- `APP_COOKIE_SECURE`：默认 `true`。仅本机 HTTP 联调可显式设为 `false` 并配置对应 HTTP Origin；生产保持 HTTPS 与 `true`。

Hikari 为每条连接设置 `innodb_lock_wait_timeout=3`，连接池获取超时为 5 秒。锁等待超时与死锁按数据库暂不可用返回脱敏 503，不自动重放写请求。生产还需同源反向代理、TLS、请求体限制及私有数据库网络；当前 IP 限流读取 Servlet `remoteAddr`，不要直接信任客户端自带转发头。反代地址提取必须由可信部署边界单独配置并验证。

## 构建与本地检查

在仓库根目录配置工具链后执行：

```powershell
. ./scripts/use-java.ps1
.\backend\mvnw.cmd -f backend/pom.xml -B verify
.\backend\mvnw.cmd -f backend/pom.xml spring-boot:run
```

Unix：`sh backend/mvnw -f backend/pom.xml -B verify`。Wrapper 下载 Maven 3.9.11 并校验固定 SHA-256；构建需能访问依赖仓库。`verify` 包含工具版本门禁、Spotless、Checkstyle、`-Xlint:all -Werror`、JUnit、HTTP 测试及打包；修正 Java 格式可运行 `spotless:apply`。默认单元与模拟服务 HTTP 测试不证明真实 MySQL 的唯一约束、隔离级别、锁及事务行为。

2026-11-10 16:14:05 +08:00 当前 `verify` 通过：72 项测试，无失败/错误/跳过，格式、静态检查、编译和打包通过。TASK-04/05 成品随后在真实 MySQL 8.4.7 完成 11/11 组验证，共 134 次 HTTP 请求；包含管理权限、配置竞态、草稿幂等、授权隔离、回滚、UTC 和锁超时恢复。完整结果见[本轮验证记录](<../specs/001-enterprise-approval/task0405-validation.md>)和[原始 JSON](<evidence/task0405-mysql.json>)。

TASK-03 的历史身份验证独立保留：当时 26 项 Java/HTTP 测试，成品实库 11/11 组、133 次 HTTP 请求、2 次应用启动，包含限流重启与会话期限；原始结果为[身份验证 JSON](<evidence/task03-mysql.json>)，不以本轮记录覆盖。

[骨架成品冒烟](<../scripts/smoke_backend.py>)使用默认profile；[TASK-02脚本](<../scripts/verify_task02_mysql.py>)验证迁移与基础数据。身份服务另用[独立成品验证脚本](<../scripts/verify_task03_mysql.py>)，构建后执行 `python scripts/verify_task03_mysql.py --skip-build`。组织/草稿使用[本轮成品脚本](<../scripts/verify_task0405_mysql.py>)，构建后执行 `python scripts/verify_task0405_mysql.py --skip-build`。完整环境、命令、结果及未覆盖项见[TASK-03记录](<../specs/001-enterprise-approval/task03-validation.md>)和[TASK-04/05记录](<../specs/001-enterprise-approval/task0405-validation.md>)。

## TASK-03 已落地边界

[AuthService](<src/main/java/cn/edu/approval/identity/AuthService.java>) 使用 `JdbcTemplate`、`TransactionTemplate` 与注入的 `Clock`，不使用 JWT 或内存会话库。

- 注册只写用户名、姓名、邮箱、密码与申请部门。新用户为 `PENDING`，与 `inbox_state` 同事务；不授角色、确认部门。数据库唯一竞争返回 409。
- 用户名去首尾空格、小写唯一；邮箱整体小写、保留点与加号别名。密码按 Unicode code point 计 8—64 位，不裁剪、规范化或截断，并拒绝孤立代理项。
- Argon2id 参数为内存 19,456 KiB、迭代 2、并行度 1、盐 16 字节、摘要 32 字节，使用现有 Bouncy Castle 提供者。不存在账号也执行同类哈希验证，失败不区分不存在、错误密码或停用。
- 会话令牌来自 32 字节安全随机数；仅客户端获得原文，数据库仅存 SHA-256。绝对期限 7 天、空闲期限 30 分钟，等于截止时间即失效；成功鉴权只更新最近访问时间。登录轮换提供的旧令牌；退出撤销当前，重复退出无业务错误；改密撤销全部会话。
- 服务每次读取或更新本人资料、改密与重申时重新锁定当前用户和会话，检查归属、客户端、撤销、期限与当前停用状态，不信任旧 `AuthPrincipal` 中的状态快照。
- IP 登录 20 次/分钟、注册 10 次/分钟；账号每 15 分钟 5 次失败。计数使用 MySQL SHA-256 桶键及原子行锁，当前为固定时间窗口；第 5 次错误仍返回 401，后续返回 429 及 `Retry-After`，成功登录清除账号当前窗口失败数。失败计数在独立事务提交后才抛出业务异常，不能随错误响应回滚。
- 公共服务入口将数据库及事务失败转为脱敏 503，不携带原始异常 cause，也不记录凭据。

内部 `resetPassword`、`disable` hook 仅包内可见，要求已有实际事务。TASK-04 的管理 API 由独立组织服务完成配置锁、管理员和对象版本检查、最后管理员及引用检查、审计和撤会话；不把内部 hook 暴露为绕过管理约束的接口。

## 身份 HTTP 契约

所有路径前缀为 `/api/v1`。非空成功响应按 `data,requestId,serverTime` 封装，下表描述 `data` 中的载荷；204 无响应体，健康骨架响应保持不变。登录及本人资料的 `permissions` 返回当前角色与管理/草稿/正文范围/统计范围提示；摘要不是授权凭据，正文与统计范围不由管理员或财务角色推导。

| 方法与路径 | 输入或行为 |
| --- | --- |
| `GET /auth/csrf` | 签发 15 分钟预认证 CSRF 上下文；不创建数据库登录会话 |
| `GET /departments` | 匿名可读启用部门 ID/名称，不含成员与负责人 |
| `POST /auth/register`、`/native-auth/register` | `username,name,email,password,requestedDepartmentId`；创建返回 201 与 `userId,status,mustChangePassword` |
| `POST /auth/login`、`/native-auth/login` | `username,password`；返回用户 ID、状态、强制改密标志；仅原生体返回 `token` |
| `POST /auth/logout` | 撤当前；Web 清 Cookie；204 |
| `GET /auth/me`、`GET /profile` | 本人资料/状态；强制改密时仅状态入口开放 |
| `PATCH /profile` | 必填 `expectedVersion,name,email`，只更新姓名、邮箱与用户版本 |
| `POST /profile/password` | `oldPassword,newPassword`；撤全并重新登录；204 |
| `POST /profile/confirmation-request` | 仅拒绝账户；`requestedDepartmentId,expectedVersion,expectedConfigVersion`；配置锁后重新验证，回 `rowVersion,configVersion` |

服务接口保留三参数 `updateProfile(p,name,email)` 但恒拒绝 400；HTTP 使用四参数版本重载，不允许缺失版本绕过并发保护。ID 与版本输出十进制字符串。角色、组织及管理变更入口见下文；`permissions` 仅作界面提示，访问时仍查询当前持久化权限。

Web 登录只设置 `sid` Cookie，不向体暴露会话令牌；Cookie 为 Path=/、无 Domain、HttpOnly、SameSite=Lax，生产 Secure。Cookie 写请求（含匿名注册、登录和退出）要求可信 Origin 与 `X-CSRF-Token`；登录成功轮换签名上下文。原生匿名入口不接受 Cookie 或 Authorization，后续只支持无 Cookie 的 Bearer；同时携带 Cookie 与 Authorization 拒绝。客户端类型由入口和凭据位置决定，不接受请求体自报。响应设置 `no-store`，错误含追踪信息且不暴露 SQL、令牌或内部路径。

## 当前依赖与许可

| 依赖 | 固定来源 / 版本 | 用途 | 许可 |
| --- | --- | --- | --- |
| Spring Boot Web / Security / Test | BOM 3.5.7 | HTTP、身份边界、JUnit/HTTP 测试 | Apache-2.0 |
| Spring Boot JDBC / HikariCP | BOM 3.5.7 | 数据库身份事务与连接池 | Apache-2.0 |
| Flyway core / MySQL | BOM 3.5.7（Flyway 11.7.2） | 版本化迁移 | Apache-2.0 |
| MySQL Connector/J | BOM 3.5.7 | MySQL 8.4 驱动 | GPL-2.0 with FOSS exception |
| BouncyCastle `bcprov-jdk18on` | 1.86 | 账户及演示账号 Argon2id 哈希 | MIT-style Bouncy Castle License |
| Maven / wrapper | 3.9.11 / 3.3.4 only-script | 构建、下载校验 | Apache-2.0 |
| Spotless / google-java-format | 2.43.0 / 1.24.0 | Java 格式 | Apache-2.0 |
| Checkstyle plugin | 3.6.0 | 静态检查 | Apache-2.0（引擎 LGPL-2.1-or-later） |
| Enforcer / compiler / Surefire | 3.6.2 / 3.14.1 / 3.5.4 | 工具约束、编译、测试 | Apache-2.0 |
| Azul Zulu OpenJDK（本地验证） | 17.62.17 / Java 17.0.17+10 | 本地运行时 | GPL-2.0 with Classpath Exception |

TASK-02 历史验证见 [TASK-02 记录](<../specs/001-enterprise-approval/task-02-validation.md>)。MyBatis-Plus 尚未引入，本次直接采用 JDBC，未新增依赖。尚未实现业务 UI、提交、正式扩展正文、附件 HTTP 接口或统计 API；TASK-09/TASK-10 模板与流程执行继续保持学生独立编码边界。

## TASK-04 组织与权限管理

管理入口统一为 `/api/v1/admin`：读取 `config`、`users`、`departments`、`grants`、`audit-records`；用户确认/拒绝、启停或调部门、角色替换、密码重置；部门创建/修改与负责人同步；READ_BODY、STATISTICS 独立范围授权及历史撤销。详见[管理 API](<../specs/001-enterprise-approval/organization-api.md>)。

写操作都要求非空 `reason` 和 `expectedConfigVersion`，修改已有用户或部门另带 `expectedVersion`。配置锁之后重新检查当前管理员、用户和会话；业务、版本递增与审计同事务。最后启用管理员不能停用或移除管理员角色。负责人必须是同部门已确认用户；角色与负责人关系必须一致。密码重置/停用撤销全部会话，审计不写临时密码或哈希。管理员不因此获得他人正文或统计范围。

当前责任检查只支持已落地的数据：存在启用策略指针或在审申请而无法证明无流程引用时，阻止涉及责任人的危险调整，返回 409 `RESPONSIBILITY_CHECK_UNAVAILABLE`，不绕过检查，也不虚构 TASK-09 学生模块。

## TASK-05 草稿与查询

- `POST /api/v1/applications`：创建固定类型草稿，201；CUSTOM 及正式源单复制目前明确返回不可用，不写半成品。
- `GET /api/v1/applications`、`GET /api/v1/applications/{id}`：本人/当前授权查询，先授权再计数分页；他人草稿不可见。正式正文未集成时返回基础元数据与 `bodyAvailable=false`。
- `PUT /api/v1/applications/{id}/draft`：`expectedVersion,title,remark,payload`，完整替换草稿；允许未填完整，但出现的字段必须符合固定语法。
- `DELETE /api/v1/applications/{id}`：JSON 正文 `expectedVersion`，成功 204。

创建、保存、删除必须带 UUID `Idempotency-Key`。相同成功请求可重放，异内容/异目标冲突 409；权限检查先于历史重放，重放先于旧版本检查。金额、ID、版本均为字符串。稳定行键、附件解绑与清理候选、客户端串行写队列及重试规则见[公共写协议](<../specs/001-enterprise-approval/draft-write-protocol.md>)。目前不提供附件物理删除或前端队列实现。
