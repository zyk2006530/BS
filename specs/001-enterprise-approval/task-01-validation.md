# TASK-01 工程骨架验证记录

日期：2026-11-10。起始 Git：`850ef6b264b53bf02c477dbfce076eb0a4b84f1a`，本次变更未自动提交。关联需求 REQ-12；关联业务验收 T15/T21，**不因下列工程检查而判为 PASS**。

## 范围与状态

建立 [backend](<../../backend/README.md>)、[web](<../../web/README.md>)、[mobile](<../../mobile/README.md>)、[deploy](<../../deploy/README.md>)，以及[工程 CI](<../../.github/workflows/skeleton.yml>)。后端仅 `GET /api/v1/health`，固定 `businessReady=false`，其余入口默认拒绝。客户端只显示骨架与健康结果。没有业务账户、数据库表/迁移、采购、模板或流程执行；没有越过 TASK-09/TASK-10 学生独立编码边界。

TASK-01 为 `in_progress`。缺少 Docker/MySQL 实际运行、微信平台/HTTPS/体验成员/真机和 SMTP 真实收件证据，不能满足任务全部完成条件。后续依赖 TASK-01 的任务未启动；T01—T22 仍为 `NOT_RUN`。

## 固定工具链与供应链

| 项目 | 固定版本 / 说明 |
| --- | --- |
| 本地 Java | Azul Zulu 17.62.17，Java `17.0.17+10`，工作区 `.local`，不修改系统配置 |
| Maven | `3.9.11`；wrapper `3.3.4` only-script；下载 SHA-256 强校验 |
| Spring Boot | `3.5.7`，Web/Security/Test 由 BOM 固定 |
| Node / npm | `24.17.0` / `11.13.0` |
| 客户端依赖 | 精确直接版本和两份实际生成的 package-lock，使用 `npm ci`；详细兼容链/许可见两端 README |
| 镜像 | 三个官方 registry 的 OCI 多架构 index 已 HTTP 200，并逐项计算响应字节 SHA-256 与 `Docker-Content-Digest` 一致；完整 tag@digest 见部署说明。本机无 Docker，未拉取/启动镜像 |
| 规格工具 | Python `3.14.6`（本地），标准库；CI 固定 `3.12.10` |

Maven zip 先核对官方 SHA-512：`03e2d65d4483a3396980629f260e25cac0d8b6f7f2791e4dc20bc83f9514db8d0f05b0479e699a5f34679250c49c8e52e961262ded468a20de0be254d8207076`；wrapper 固定 SHA-256：`0d7125e8c91097b36edb990ea5934e6c68b4440eef4ea96510a0f6815e7eeadb`。Zulu zip SHA-256：`bd8a942bb543f109a28d3eadf3ec2f29a3ee28ab53506e31d2858292f63c6949`，与 Azul package UUID `3a32c861-d07a-4699-b6de-f230795da715` 元数据一致。

工具初始下载在被忽略的 `.local`，Maven wrapper/依赖使用标准用户 `.m2` 缓存；target/node_modules/dist 为忽略的构建产物，不提交工具二进制或真实密钥。Temurin 本地 zip 因网络重置下载失败，改用相同 Java 版本的 Zulu；Compose 运行镜像与本地发行版差异必须在容器实测时再验证。

## 本地检查

| 检查 | 命令 / 目的 | 本次结果 |
| --- | --- | --- |
| 规格一致性 | `python scripts/verify_specs.py` | PASS（12 需求、16 任务、22 验收定义；仅元数据） |
| 检查器与部署回归 | `. ./scripts/use-java.ps1` 后设 `DEPLOY_JAVA_HOME=$env:JAVA_HOME`，执行 `python -m unittest discover -s tests -p 'test_*.py' -v` | PASS，58/58，3.975s；26 规格 + 26 配置/摘要证据正反例 + 6 真实 Java/HTTP 探针，零跳过 |
| 后端 | `backend/mvnw.cmd -f backend/pom.xml -B -ntp spotless:apply clean verify` | PASS，2026-11-10 11:09 +08:00；Enforcer、Spotless、Checkstyle 零违规、严格编译、2/2 随机端口 HTTP 测试、Boot 可执行 JAR 重打包成功 |
| 成品 JAR | `python scripts/smoke_backend.py` | PASS：真实启动成品，健康 200 / 业务 GET 401 / POST 403，无 Cookie，`businessReady=false`，临时进程已关闭；[脚本](<../../scripts/smoke_backend.py>) |
| Web | `npm ci`、`npm run check`（含 build） | PASS：格式、ESLint、类型、9 单测、Vite 构建；[细节](<../../web/VALIDATION.md>) |
| 微信小程序 | `npm ci`、`npm run check`（含 `uni build -p mp-weixin`） | PASS：净安装、格式、ESLint、类型、17 单测、真实微信目标构建；[细节](<../../mobile/VALIDATION.md>) |
| 部署 | 标准库配置/镜像证据回归和 JDK HTTP 探针测试 | PASS，32/32，已包含在 58 例中；[细节](<../../deploy/validation.md>)；非 Docker 实测 |
| 工作区空白 | `git diff --check` | PASS；新文件未跟踪不等同已审查/已提交 |
| CI | GitHub Actions 规格、后端、双客户端作业 | 配置已提供，未在 GitHub 上运行 |

本地成品 `backend/target/approval-backend-0.1.0-SNAPSHOT.jar` SHA-256：`b3bc30ea472d0dd115f9fac812680c3a365fc8191781a57a8b11362cc7c18165`。主工作区再次执行两端 `npm run check` 均退出 0（Web 9/9、mobile 17/17）；小程序仅有未配置 AppID、Node 模块类型性能提示等已记录警告。镜像摘要原始响应及逐项比对结果见[镜像锁](<../../deploy/image-lock.json>)，**不代表镜像层拉取或容器运行通过**。

首次 Maven wrapper 生成因 PowerShell 未引用 `-Dmaven=3.9.11` 参数而失败（`Unknown lifecycle phase ".9.11"`）；已修正固定 distributionUrl，并加入 SHA-256 校验，不把失败命令记为通过。

后端首次 `verify` 在 Checkstyle 插件依赖 `org.apache.velocity:velocity-engine-core:2.4` 描述符解析时失败，Maven `.lastUpdated` 记录原因为 `Connection reset`，不是 Java 检查通过。第二次下载长期无进展，在用户确认网络恢复后取消旧进程并重新 `clean verify`。

网络恢复后的首次真实 HTTP 回归发现 `HealthHttpTest.businessAndManagementEndpointsAreNotPublic:41`：`expected: 403 but was: 401`。默认 `sendError` 引发容器错误转发，随后默认拒绝边界将拒绝响应覆盖；改为显式 `setStatus` 的认证入口 401 / 拒绝处理 403，保留 CSRF 和其余路径默认拒绝，未降低断言或关闭 CSRF。修复后的完整重跑结果以检查表为准。

## 客户端安全与微信编译边界

真实 `npm audit --json` 均返回 1，不能宣称安全检查通过。Web 为 9 moderate（主要 minimatch 的构建/检查链）；mobile 为 53 项（10 low / 20 moderate / 23 high / 0 critical）。移动端已移除旧 Vitest/tinypool 的 2 项 critical，改用 Node 原生测试器，但 uni 的固定 Vite 5.2.8、Vue 3.4.21、i18n、CLI 等链仍有未关闭公告，不能全部视作只在开发中使用。未使用 `audit fix --force` 破坏 uni peer 兼容，不对外开放开发服务器；升级与产物暴露评估未完成，**不满足生产安全就绪**。完整机器结果及依赖链见两端验证记录。

初次验证时小程序源码 AppID 留空，uni 编译器会在忽略提交的构建产物中自动写标准 `touristappid`。随后用户提供微信 AppID `wxb911cea6b9583890`，已配置到 `mp-weixin.appid`，DCloud 外层标识保持空值，保留 `urlCheck:true`。AppID 配置不代表账号归属、开发者工具导入、体验版或真机成功，也不是关闭域名校验的替代路径。

配置后再次运行 mobile `npm run check` 退出 0，格式/静态/类型、17/17 测试及微信目标构建通过；生成的 `project.config.json` 已核对为 `appid=wxb911cea6b9583890`、`compileType=miniprogram`、`urlCheck=true`。编译器仍提示未配置 uni 统计 AppID（独立 DCloud 标识留空），不等于微信 AppID 未生效。

2026-11-10 12:13 +08:00，用户开启 IDE 服务端口 54205 后，CLI 登录状态 `true`，使用官方隔离测试客户端连接本机自动化端口 9420，执行 [模拟器脚本](../../scripts/smoke_wechat.cjs) 退出 0：真实重载首页、正确显示缺 API 地址提示、点击重试保持预期反馈、截图成功，本次操作期间未捕获异常或 error 级 console 事件。共 5 项检查；[截图](../../mobile/evidence/wechat-simulator-2026-11-10.png)和[机器结果](../../mobile/evidence/wechat-simulator-2026-11-10.json)已保存。逻辑窗口 390×753、基础库 3.17.6、`platform=devtools`，不是 iOS 真机。没有上传/发布、没有关闭域名校验，真实 HTTPS 网络仍未运行；完整范围见[移动端验证记录](../../mobile/VALIDATION.md)。

## 尚缺证据与下一步

2026-11-10 13:09 +08:00 再次核实：用户已安装 Docker Desktop `4.99.0`，Docker Client/Engine 均为 `29.9.3`，`desktop-linux` 的 Linux/amd64 引擎正常响应。此前“无 Docker”属于初始验证环境历史；本次仅确认引擎可用，未启动项目 Compose/MySQL。用户提出使用微信云托管网关作为小程序接入方案，尚未部署或适配调用，也未把方案讨论记为验收通过。

[开发环境核实表](<../../docs/development-environment.md>)逐项记录外部条件。需在真实环境拉取镜像并启动三服务，核实 MySQL 版本与私有网络；配置真实微信 AppID、合法 HTTPS 域名和体验成员，保留 Android/iOS 实测；配置专用 SMTP 后保留手机实际收件的脱敏记录。不得向对话、Git、构建日志写入账号密码或授权码，也不允许关闭微信域名校验冒充完成。

当前证据只支持“工程骨架已创建并进行本地检查”，不支持 T15 空环境全业务复现、T21 小程序全核心与邮件收件，更不支持生产安全就绪。
