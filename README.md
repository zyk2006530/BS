# 企业审批系统

《BS 体系软件设计》课程项目。当前已建立规格基线，并完成 **TASK-02 数据库迁移和最小初始化**：18 张基础表、幂等生产种子、演示初始化、业务/迁移账号分离及真实 MySQL 8.4.7 验证；同时保留 TASK-01 的 Spring Boot 健康接口、Vue 桌面端、uni-app 微信小程序构建工程。尚无审批 API 和流程执行，不能据此声称业务可用。

## 从这里开始

| 入口 | 用途 |
|---|---|
| [最新版设计 PDF](<docs/midterm/中期设计文档-修订版.pdf>) | 与用户指定的优化版交付文件字节一致，作为设计依据 |
| [规格入口](<specs/001-enterprise-approval/README.md>) | 需求 → 技术计划 → 任务 → 验收的阅读与开发顺序 |
| [需求规格](<specs/001-enterprise-approval/spec.md>) | 12 组需求及业务边界 |
| [实施任务](<specs/001-enterprise-approval/tasks.md>) | 依赖、负责人边界和完成条件 |
| [验收方案](<specs/001-enterprise-approval/acceptance.md>) | PDF T01—T22 的可执行验证步骤；业务测试均待运行 |
| [差异分析](<specs/001-enterprise-approval/gap-analysis.md>) | 历史设计与最新方案差异、当前缺口 |
| [工程约束](<.specify/memory/constitution.md>) | 安全、并发、独立编码及证据要求 |
| [规格验证记录](<specs/001-enterprise-approval/validation.md>) | 规格检查器单测、基线检查和未执行业务项 |
| [TASK-02 验证记录](<specs/001-enterprise-approval/task-02-validation.md>) | 18 张基础表、真实 MySQL 约束/权限、演示数据与生产分离证据 |

## 最新设计的关键选择

- Java 17 / Spring Boot 3、Vue 3 / TypeScript、MySQL 8；移动端交付 uni-app 微信小程序，不以 H5 代替。
- 可撤销的持久不透明会话，而不是 JWT；前台 10 秒轮询权威摘要，而不是 WebSocket。
- 三类固定业务加自定义设备借用示例；金额、审批路径、模板版本及人员在提交时冻结。
- 管理写入由全局配置版本串行保护；申请写入使用预期版本和请求幂等键。
- 增强范围仅为相同票据文件预警、超时催办，不含 OCR、自动审批或自动改派。
- **自定义表单、模板配置、动态录入与共用流程执行实现由学生独立编码，不使用 AI 编程。** 本仓库的规格、计划和静态原型不等于这些模块的实现。

## 规格驱动开发

1. 在[需求规格](<specs/001-enterprise-approval/spec.md>)找到 REQ 编号，核对来源 PDF。
2. 按[技术计划](<specs/001-enterprise-approval/plan.md>)确定边界；新增设计决策先写入规格，不直接改变旧实例语义。
3. 从[任务列表](<specs/001-enterprise-approval/tasks.md>)选取依赖已满足的任务，小步实现并关联对应验收编号。
4. 提交代码时同时给出测试、构建版本和证据；仅文档检查成功不能将业务验收标为通过。
5. 同步[机器追踪清单](<specs/001-enterprise-approval/manifest.json>)并运行下列检查。

在仓库根目录执行（Python 3.10+，仅标准库）：

```powershell
python scripts/verify_specs.py
python -m unittest discover -s tests -p "test_*.py" -v
```

第一条检查 PDF 指纹、需求/任务/验收覆盖、依赖、状态与证据、本轮规格文档链接；第二条测试规格检查器和部署静态约束，**不验证审批业务，也不启动 Docker**；真实 MySQL 迁移和权限证据由 `python scripts/verify_task02_mysql.py` 单独生成。可用 `--source-pdf "..\交付\中期设计文档-优化版.pdf"` 额外核对外部原件。

## 历史材料与当前缺口

[旧版设计稿](<docs/设计文档.md>)保留历史正文，但不再作为实现依据；其中 JWT、WebSocket 和强制模板迁移等规则已被取代。报告源文件与原型的构建方式见[中期文档说明](<docs/midterm/README.md>)。

Java 17/Maven 已提供工作区局部配置；真实 MySQL 8.4.7 迁移、账号权限和演示数据已在 TASK-02 验证。微信 AppID/HTTPS 合法域名、体验成员、SMTP 及真机环境仍待准备和实测；TASK-01 保持 `in_progress`。详见[环境条件清单](<docs/development-environment.md>)、[TASK-01 验证记录](<specs/001-enterprise-approval/task-01-validation.md>)和 [TASK-02 验证记录](<specs/001-enterprise-approval/task-02-validation.md>)。业务 API、采购/流程闭环和 T11/T15/T18/T20/T22 验收仍待后续任务；本次没有执行真实 Compose 整体启动。

## 运行工程骨架

锁定 Node `24.17.0` / npm `11.13.0`、Java `17.0.17+10` / Maven `3.9.11`。从仓库根目录执行：

```powershell
# 仅配置当前 PowerShell；首次下载校验 SHA-256 后解压到被忽略的 .local。
. ./scripts/use-java.ps1
./backend/mvnw.cmd -f backend/pom.xml -B -ntp verify
./backend/mvnw.cmd -f backend/pom.xml spring-boot:run
```

后端仅提供 `GET http://127.0.0.1:8080/api/v1/health`，返回 `status=UP`、`stage=SKELETON`、`businessReady=false`；未开放业务、登录或管理端点。

在另一个终端进入对应客户端目录，分别执行 `npm ci`、`npm run check`、`npm run build`。使用方式与版本说明见[后端](<backend/README.md>)、[桌面端](<web/README.md>)、[微信小程序](<mobile/README.md>)。Compose 只供本机开发，需先生成 jar 与 Web dist；命令和未验证项见[部署说明](<deploy/README.md>)。
