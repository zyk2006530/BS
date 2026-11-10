# Backend

Java 17.0.17+10、Maven 3.9.11、Spring Boot 3.5.7。默认启动仍是封闭骨架；显式启用 `db` 且未启用 `migrate` 时加载 TASK-03 身份服务与 HTTP 入口。代码已实现不等于验收通过，当前测试执行及 MySQL 证据以 [TASK-03 验证记录](<../specs/001-enterprise-approval/task03-validation.md>) 为准。

## 启动模式

| profile | 行为 |
| --- | --- |
| 默认 | 不加载数据库；仅健康入口开放，其余入口拒绝 |
| `db` | 连接 MySQL、执行 Flyway、加载持久身份服务及身份 HTTP 入口 |
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

2026-11-10 最终 `verify` 通过：26 项测试（16项服务、8项身份HTTP、2项骨架HTTP），无失败/错误/跳过，格式、静态检查、编译和打包通过。随后成品在真实 MySQL 8.4.7 上完成11/11组独立身份验证，共133次HTTP请求和2次应用启动，包含并发、回滚、重启、期限和锁超时恢复；原始结果为[身份验证JSON](<evidence/task03-mysql.json>)。

[骨架成品冒烟](<../scripts/smoke_backend.py>)使用默认profile；[TASK-02脚本](<../scripts/verify_task02_mysql.py>)验证迁移与基础数据。身份服务另用[独立成品验证脚本](<../scripts/verify_task03_mysql.py>)，构建后执行 `python scripts/verify_task03_mysql.py --skip-build`。完整环境、命令、结果及未覆盖项见[TASK-03记录](<../specs/001-enterprise-approval/task03-validation.md>)。

## TASK-03 已落地边界

[AuthService](<src/main/java/cn/edu/approval/identity/AuthService.java>) 使用 `JdbcTemplate`、`TransactionTemplate` 与注入的 `Clock`，不使用 JWT 或内存会话库。

- 注册只写用户名、姓名、邮箱、密码与申请部门。新用户为 `PENDING`，与 `inbox_state` 同事务；不授角色、确认部门。数据库唯一竞争返回 409。
- 用户名去首尾空格、小写唯一；邮箱整体小写、保留点与加号别名。密码按 Unicode code point 计 8—64 位，不裁剪、规范化或截断，并拒绝孤立代理项。
- Argon2id 参数为内存 19,456 KiB、迭代 2、并行度 1、盐 16 字节、摘要 32 字节，使用现有 Bouncy Castle 提供者。不存在账号也执行同类哈希验证，失败不区分不存在、错误密码或停用。
- 会话令牌来自 32 字节安全随机数；仅客户端获得原文，数据库仅存 SHA-256。绝对期限 7 天、空闲期限 30 分钟，等于截止时间即失效；成功鉴权只更新最近访问时间。登录轮换提供的旧令牌；退出撤销当前，重复退出无业务错误；改密撤销全部会话。
- 服务每次读取或更新本人资料、改密与重申时重新锁定当前用户和会话，检查归属、客户端、撤销、期限与当前停用状态，不信任旧 `AuthPrincipal` 中的状态快照。
- IP 登录 20 次/分钟、注册 10 次/分钟；账号每 15 分钟 5 次失败。计数使用 MySQL SHA-256 桶键及原子行锁，当前为固定时间窗口；第 5 次错误仍返回 401，后续返回 429 及 `Retry-After`，成功登录清除账号当前窗口失败数。失败计数在独立事务提交后才抛出业务异常，不能随错误响应回滚。
- 公共服务入口将数据库及事务失败转为脱敏 503，不携带原始异常 cause，也不记录凭据。

内部 `resetPassword`、`disable` hook 仅包内可见，要求已有实际事务，并撤销全部会话。它们不是 TASK-04 管理 API；调用方仍须在同一事务完成配置锁、管理员及对象版本检查、最后管理员和引用检查、审计等条件。账户确认、拒绝、角色和组织管理尚不属于本次交付。

## 身份 HTTP 契约

所有路径前缀为 `/api/v1`。非空成功响应按 `data,requestId,serverTime` 封装，下表描述 `data` 中的载荷；204 无响应体，健康骨架响应保持不变。登录尚无角色权限摘要。

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

服务接口保留三参数 `updateProfile(p,name,email)` 但恒拒绝 400；HTTP 使用四参数版本重载，不允许缺失版本绕过并发保护。ID 与版本输出十进制字符串。登录输出不包含角色摘要；管理员启用及组织分配须等 TASK-04。

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

TASK-02 历史验证见 [TASK-02 记录](<../specs/001-enterprise-approval/task-02-validation.md>)。MyBatis-Plus 尚未引入，本次直接采用 JDBC，未新增依赖。未实现 UI、TASK-04 管理能力或申请业务；TASK-09/TASK-10 模板与流程执行继续保持学生独立编码边界。
