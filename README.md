# 企业审批系统

《BS 体系软件设计》课程项目。当前仓库处于**设计与规格基线阶段**：包含设计文档、静态原型和规格检查工具，尚无 Spring Boot、Web 或小程序业务实现，不是可启动的应用。

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
| [本轮验证记录](<specs/001-enterprise-approval/validation.md>) | 规格检查器单测、基线检查和未执行业务项 |

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

第一条检查 PDF 指纹、需求/任务/验收覆盖、依赖、状态与证据、本轮规格文档链接；第二条测试检查器本身，**不验证审批业务**。可用 `--source-pdf "..\交付\中期设计文档-优化版.pdf"` 额外核对外部原件。

## 历史材料与当前缺口

[旧版设计稿](<docs/设计文档.md>)保留历史正文，但不再作为实现依据；其中 JWT、WebSocket 和强制模板迁移等规则已被取代。报告源文件与原型的构建方式见[中期文档说明](<docs/midterm/README.md>)。

Java/Maven、Docker、微信 AppID/HTTPS 合法域名、SMTP 及真机环境尚需准备和实测。后续工程目录、数据库迁移、Compose 与 CI 都是[技术计划](<specs/001-enterprise-approval/plan.md>)中的待建交付物；本次没有伪造这些产物或业务测试结果。
