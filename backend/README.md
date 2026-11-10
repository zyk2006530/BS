# Backend skeleton

Java 17.0.17+10 / Maven 3.9.11 / Spring Boot 3.5.7，当前仅提供 `GET /api/v1/health`：

```json
{"status":"UP","stage":"SKELETON","businessReady":false}
```

健康表示 HTTP 进程可响应，不表示数据库、采购或认证可用。无默认用户、登录页、JWT、数据库表或迁移。所有非健康入口默认拒绝；这是封闭骨架边界，不是 TASK-03 会话安全实现。保留 Spring Security 默认 CSRF/安全头，不提前放开业务入口。

## Build

在仓库根目录配置 Java 17.0.17+10 后执行：

```powershell
. ./scripts/use-java.ps1
.\backend\mvnw.cmd -f backend/pom.xml -B verify
.\backend\mvnw.cmd -f backend/pom.xml spring-boot:run
```

Unix：`sh backend/mvnw -f backend/pom.xml -B verify`。构建需要 Maven Central；wrapper 下载 Maven 3.9.11 并校验固定 SHA-256。输出可执行 jar，不依赖本机 MySQL即可构建/测试。Spring Boot BOM 固定传递依赖；直接插件亦固定版本。

`verify` 执行 Enforcer 工具版本门禁、Spotless 格式、Checkstyle 静态规则、编译器 `-Xlint:all -Werror`、JUnit 和真实随机端口 HTTP 冒烟，再打包。自动修正 Java 格式：`sh backend/mvnw -f backend/pom.xml spotless:apply`。

打包后在根目录执行 `python scripts/smoke_backend.py`（Python 3.12+、已设 `JAVA_HOME`）：校验可执行 JAR 结构，临时绑定回环随机端口，验证健康 200、业务入口 401、无 CSRF 的 POST 403，然后自动关闭进程。脚本见[成品冒烟](<../scripts/smoke_backend.py>)；日志只留在被忽略的 `.local`。这不是 MySQL、Docker 或业务验收。

## 当前依赖与许可

| 依赖 | 固定来源 / 版本 | 用途 | 许可 |
| --- | --- | --- | --- |
| Spring Boot Web / Security / Test | BOM 3.5.7 | HTTP、默认拒绝边界、JUnit/集成测试 | Apache-2.0 |
| Maven | 3.9.11 | 构建 | Apache-2.0 |
| Maven wrapper | 3.3.4 only-script | 下载校验固定 Maven | Apache-2.0 |
| Spotless / google-java-format | 2.43.0 / 1.24.0 | Java格式 | Apache-2.0 |
| Checkstyle plugin | 3.6.0 | 静态检查 | Apache-2.0（引擎 LGPL-2.1-or-later） |
| Enforcer / compiler / Surefire | 3.6.2 / 3.14.1 / 3.5.4 | 工具约束、编译、测试 | Apache-2.0 |
| Azul Zulu OpenJDK（本地验证） | 17.62.17 / Java 17.0.17+10 | 本地运行时 | GPL-2.0 with Classpath Exception |

后续 MyBatis-Plus / MySQL Connector / Flyway 在 TASK-02 按需引入并固定版本，不用空迁移或测试内存库伪造 MySQL 集成。TASK-09/TASK-10 不在本轮编码范围。
