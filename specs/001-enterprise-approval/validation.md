# 规格基线验证记录

日期：2026-11-09。验证对象是本次新增的规格文件与检查工具，不是企业审批应用。

本文件保留 TASK-00 历史结果；2026-11-10 后续工程进展另见 [TASK-01 验证记录](<task-01-validation.md>) 和 [TASK-02 验证记录](<task-02-validation.md>)，不得将历史“无工程”描述当作当前目录清单。

## 环境与基线

- Windows PowerShell；使用会话提供的 Python 3.12.14 运行时（通过 `python --version` 实测）。
- 设计基线：用户指定优化版与[仓库修订版](<../../docs/midterm/中期设计文档-修订版.pdf>)，两者 SHA-256 均为 `92c65833438b707a2f5acb66ff58832ceb780f033e9819983dd15fcafa5e4182`，已通过 `Get-FileHash -Algorithm SHA256` 比对。
- 开始工作时 HEAD：`ed2ab04`。仓库原有中期报告/原型/PDF 修改及新增图件保持未提交，未执行 reset、commit 或 push。本次记录不是最终业务发布标签。

## 本轮验证

| 检查 | 实际结果 | 范围 |
|---|---|---|
| 指定 PDF 与仓库副本摘要核对 | PASS | 字节级版本一致，不代替内容审查 |
| 检查器单测 | PASS，26/26，最终回归0.690s | 合成夹具，PDF 错版、断链、漏项、依赖环、状态/证据、独立编码边界和畸形输入等正反例；含非字符串状态与验证记录断链 |
| 完整规格检查 | PASS，含 `--source-pdf` 指定原件核对 | 12组需求、16任务、22验收定义、依赖/状态/证据及受管文档链接；不是业务验收 |
| 工作区变更检查 | PASS，`git diff --check` 返回0 | 未新增空白错误；Git提示现有LF未来转CRLF，不是检查失败；原有报告/原型改动保留 |
| 业务 T01—T22、性能、小程序真机、完整部署恢复 | NOT_RUN | TASK-02 已用真实 MySQL 验证基础表、种子和账号权限；该数据库证据不等于 T11/T15/T18/T20/T22 验收，业务 API/流程/整体 Compose 尚未执行 |

复现命令（仓库根目录，Python 3.10+）：

```powershell
python scripts/verify_specs.py
python scripts/verify_specs.py --source-pdf "..\交付\中期设计文档-优化版.pdf"
python -m unittest discover -s tests -p "test_*.py" -v
git diff --check
```

第二条仅适用于本地交付目录仍存在时；克隆仓库不要求拥有外部原件。检查器只验证[机器清单](<manifest.json>)受管文件，不递归重审历史课件、旧报告或图片。它要求已执行测试具备证据路径，但不能自动判断证据是否充分或业务是否真实正确，仍需人工复核。

## 未执行项与下一步

TASK-03—TASK-15 待实施；TASK-01 仍为 `in_progress`，其微信云端/体验成员/真机与 SMTP 条件未满足；TASK-02 已完成数据库迁移和最小初始化，但业务 API、流程执行和 T11/T15/T18/T20/T22 仍未完成。TASK-09、TASK-10 是本人独立编码边界。Java/Maven、Docker 与 MySQL 8.4.7 已具备并用于 TASK-02 验证；小程序主体/AppID/合法域名/HTTPS、体验成员、真机与 SMTP 需要后续确认。优先按[任务列表](<tasks.md>)从身份、组织和公共草稿继续，而不是将本记录中的 PASS 转写为业务已完成。
