# TASK-01 开发部署骨架

这是三服务开发配置，不是完成的审批产品。当前唯一后端入口是 `GET /api/v1/health`，预期为 `{"status":"UP","stage":"SKELETON","businessReady":false}`。`status=UP` 只说明进程及骨架接口能回应；`businessReady=false` 明确业务尚未就绪。MySQL 的 ping 也只检查进程，不证明迁移、权限或业务数据正确。

**仅绑定 `127.0.0.1:8088` 的开发 HTTP，不是生产 HTTPS。** 不可直接改成公网监听当作生产交付，不能用于小程序合法 HTTPS 域名验收。没有生成表、迁移、演示种子、默认业务账户，TASK-02 之前不会借数据库容器启动宣称 T15/T21 已通过。

## 文件与版本

- [开发 Compose](<compose.yaml>) 使用 JSON（YAML 的子集），便于标准库静态测试可靠读取；用 Compose 的 `-f` 显式指定。
- [后端 Dockerfile](<backend.Dockerfile>)、[前端 Dockerfile](<frontend.Dockerfile>) 均为 **artifact-first**，Docker 内不临时下载 Maven/npm 依赖，也不以未锁定构建工具重新编译。构建上下文用各自同名 `.dockerignore` 白名单排除环境文件与其他材料。
- [Nginx 配置](<nginx.conf>) 只公开静态 `dist` 和同源 `/api/` 代理，保留 API 完整路径；覆盖而非追加客户端来源头；没有附件卷或静态附件 alias。
- [Java 健康探针](<HealthProbe.java>) 直接使用 JDK 17 自带 HTTP API，检查 HTTP 200 及完整的三字段骨架响应，不依赖镜像是否带 curl/wget。故镜像刻意选择 **JDK，不是 JRE**。该探针将来启用业务时必须随健康契约显式修改，不把 `businessReady=true` 错当成本阶段响应。

| 项目 | 精确版本/镜像 | 用途与许可 |
| --- | --- | --- |
| 主机 Java | 17.0.17+10；本机编译验证可使用 Zulu 17.62.17 | 后端编译；OpenJDK GPL-2.0 with Classpath Exception |
| Maven | 3.9.11 | 主机编译；Apache-2.0 |
| Spring Boot parent | 3.5.7 | 后端依赖基线；Apache-2.0 |
| Node.js / npm | 24.17.0 / 11.13.0 | 主机前端构建；Node MIT，npm Artistic-2.0，依赖各依其许可 |
| 后端基础镜像 | `eclipse-temurin:17.0.17_10-jdk-jammy` | OpenJDK；镜像脚本及 Ubuntu 基础包另有各自许可 |
| 前端基础镜像 | `nginx:1.28.0-alpine3.21` | Nginx BSD-2-Clause；Alpine 各组件依其许可 |
| 数据库镜像 | `mysql:8.4.7` | MySQL 8.4 LTS，GPL-2.0，依赖包另有各自许可 |
| 本地构建镜像 | `approval-backend:0.1.0-skeleton` / `approval-frontend:0.1.0-skeleton` | 本项目构建产物；不表示已发布到 registry |

现在同时锁定**完整版本标签与多架构 OCI index digest**。2026-11-10 最初网络请求出现 `WinError 10060` / Docker Hub `fetch failed`；用户确认网络恢复后，于北京时间 11:02—11:05 重新请求官方 `registry-1.docker.io`，三个标签均返回 HTTP 200。对响应原始字节计算的 SHA-256 与 `Docker-Content-Digest` 响应头逐项相同，且每个 index 均包含 Linux amd64 / arm64。以下摘要已写入 `FROM` 和 Compose 的 `tag@sha256:...`：

| 镜像 | 已核对的多架构 index digest |
| --- | --- |
| Temurin 17.0.17_10-jdk-jammy | `sha256:73c73439d25b953b34e0065172ad8ce01bea5bc8fc25158f0eeecb4c06d14fed` |
| Nginx 1.28.0-alpine3.21 | `sha256:30f1c0d78e0ad60901648be663a710bdadf19e4c10ac6782c235200619158284` |
| MySQL 8.4.7 | `sha256:0426ec38c7a10aa45ba383887df7878f74ee70e2fd589c7b69207f3577901903` |

[镜像锁定记录](<image-lock.json>) 保留时间、官方 URL、响应头摘要、长度、平台；[原始 index 响应](<registry/>) 保留未经重序列化的响应字节，静态测试离线重新哈希并拒绝证据或 digest 被篡改。这里核实的是 registry 元数据，**没有拉取镜像层、构建镜像或运行容器**，不证明运行兼容性、安全扫描或供应链签名验证。可手动只读复核网络上的标签是否仍对应已锁定摘要：

```powershell
python deploy/verify_image_lock.py
```

[核验脚本](<verify_image_lock.py>) 只依赖标准库，默认不改文件、不打印临时 registry bearer token；标签漂移、非 index 或摘要不一致会失败。`--capture` 是显式更新同一锁定摘要的原始证据，不会自动接受漂移的新摘要。官方镜像信息：

- [Temurin 官方镜像](<https://hub.docker.com/_/eclipse-temurin>)
- [Nginx 官方镜像](<https://hub.docker.com/_/nginx>)
- [MySQL 官方镜像](<https://hub.docker.com/_/mysql>)

镜像许可表不是完整 SBOM 或法律结论；发布前保留镜像、依赖和基础系统许可通知，并进行漏洞扫描。精确版本保证本阶段可追踪，不代表永久安全或允许跳过补丁评估。

## 开发启动（尚未在本机执行 Docker）

前提：已安装并运行支持 Linux 容器的 Docker Engine / Docker Desktop，且 Docker Compose v2 支持 `service_healthy`；能访问上述 registry；磁盘有卷空间。本任务不自动安装 Docker、不启动替代数据库/服务。仓库根目录执行：

```powershell
. ./scripts/use-java.ps1
.\backend\mvnw.cmd -f backend/pom.xml -version
node --version
npm --version
# 分别核对 Java17.0.17+10、Maven3.9.11、Node24.17.0、npm11.13.0。
.\backend\mvnw.cmd -f backend/pom.xml clean verify
npm --prefix web ci
npm --prefix web run check
npm --prefix web run build
```

构建成功后应存在 [后端 jar](<../backend/target/approval-backend-0.1.0-SNAPSHOT.jar>) 和 [前端构建目录](<../web/dist/>)。缺少任一产物时 Docker `COPY` 必须失败，不使用空页面或假 jar 兜底。后端产物由 Spring Boot Maven 打包生成。

```powershell
Copy-Item deploy/.env.example deploy/.env
# 使用本地编辑器填写 deploy/.env：两个独立强随机密码，MYSQL_APP_USER 不得为root。
# 不在命令行输入密码，不提交该文件；本地文件权限也应限制。
docker compose --env-file deploy/.env -f deploy/compose.yaml config --quiet
docker compose --env-file deploy/.env -f deploy/compose.yaml build
docker compose --env-file deploy/.env -f deploy/compose.yaml up -d
docker compose --env-file deploy/.env -f deploy/compose.yaml ps
Invoke-RestMethod http://127.0.0.1:8088/api/v1/health
```

[环境示例](<.env.example>) 的密码为空，Compose 通过 `${VAR:?message}` 在缺失或空值时拒绝配置。不要运行或粘贴会打印解析后环境的 `docker compose config`（不带 `--quiet`）/ `docker inspect`，也不要把 `.env` 或数据库凭据附进日志。环境变量仍可被有 Docker 管理权限的本机操作者查看，因此本配置不能替代生产 secrets 管理。

在浏览器打开 `http://127.0.0.1:8088`，确认页面显示骨架/业务不可用，健康 JSON 保持上述三字段。前端依赖后端骨架健康，后端依赖 MySQL 进程健康；**这不是“迁移成功后开放业务”的实现**，后者须在 TASK-02 及后续集成时增加真实迁移门禁。

数据库仅加入 `internal: true` 的 `data` 网络，无任何宿主机 `ports`。前端只在 `app` 网络，不能直接访问数据库；后端桥接二者。数据库字节持久到私有 `mysql-data` 命名卷，附件到仅后端挂载的 `uploads` 命名卷，Nginx 不共享该卷。MySQL 官方入口首次启动只创建空 `approval` schema 和环境指定应用账号；没有挂载 `/docker-entrypoint-initdb.d` 或业务 SQL。应用账号对空 schema 的初始化授权不是最终最小权限配置，审批/审计禁止 UPDATE/DELETE、迁移账号分离等由 TASK-02 负责，不能据此标记安全验收通过。骨架后端此时不消费预留的 `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` / `APP_UPLOAD_DIR`，尚不连接数据库或读写附件。

已有数据库卷再次启动不会按新 `.env` 修改旧密码。要轮换凭据应使用受控数据库操作并同步配置；不要误删卷修复。停止开发栈保留数据：

```powershell
docker compose --env-file deploy/.env -f deploy/compose.yaml down
```

**不要带 `-v`**，它会删除数据库和附件卷。当前没有备份/恢复交付，不能把命名卷存在或容器重启替代成组恢复与真实附件下载验证。

## 本阶段验证与缺口

静态测试：

```powershell
python -m unittest discover -s tests -p test_deploy_skeleton.py -v
```

[部署测试](<../tests/test_deploy_skeleton.py>) 全部使用 Python 标准库。静态正例及反例防止数据库/后端裸露、HTTP 公网绑定、数据库外网、默认/空密码开关、root 业务账号、可变镜像标签、公共附件卷/alias、SQL 初始化偷带、错误健康命令和不可信反代地址链；不会假装启动 Docker。需要真正验证 Java 探针时设置本机 JDK 路径：

```powershell
$env:DEPLOY_JAVA_HOME = '你的JDK17目录'
python -m unittest discover -s tests -p test_deploy_skeleton.py -v
Remove-Item Env:DEPLOY_JAVA_HOME
```

这组附加测试真实调用 `javac --release 17`、`java` 和一次 source-file mode，对短暂的本机 `127.0.0.1:8080` HTTP 测试夹具验证成功、重排空白、错误状态、错误阶段、业务已就绪、畸形/重复字段和超大响应；结束关闭 HTTP 测试夹具，编译文件仅放临时 `.local` 并清理。端口占用会真实失败，不抢占、不伪造通过。这是探针单元测试，不是实际 Spring Boot/Docker/MySQL 集成证明。未设置 JDK 环境变量时附加测试明确 SKIP，而不是记 PASS。

| 检查 | 真实状态与边界 |
| --- | --- |
| 配置静态正反例 | PASS：2026-11-10 实际执行 26 项，含原始 index 哈希与 digest 锁定；见[验证记录](<validation.md>) |
| Java 探针编译及 HTTP 单测 | PASS：Zulu17.0.17+10 实际执行 6 项（含 source-file mode），总计 32 项通过；不代表 Temurin Linux 镜像实测 |
| registry manifest / digest | PASS：三个官方多架构 index HTTP 200，响应字节 SHA-256 与响应头相等，已锁定 tag@digest |
| 镜像层拉取 / 镜像构建 / 容器运行 | NOT_RUN；没有 Docker，registry 元数据核验不是容器实测 |
| Compose config、build、Nginx `-t`、空环境 up | NOT_RUN；本机无 Docker，未安装或启动替代服务 |
| MySQL 初始化/持久、最小权限、附件持久/下载 | NOT_RUN；TASK-02 与后续真实环境验证 |
| 生产 HTTPS / 可信反代 / Secure Cookie | NOT_RUN；本配置不是生产 HTTPS，需单独生产配置和证书 |
| AppID/主体/合法域名/体验成员、安卓/iOS真机、SMTP收信 | NOT_RUN；须用户提供平台/域名/证书/设备/SMTP条件 |
| 采购、流程、自定义、T15/T21/T22业务验收 | NOT_RUN；健康/配置测试不证明业务已交付 |

生产部署还必须有受控迁移门禁、独立迁移账号/最小权限、证书与 HTTPS、可信来源清单、私有文件鉴权、持久任务、脱敏日志、成组备份恢复和真实业务回归；不要把本文件中的开发便利当作这些完成条件。
