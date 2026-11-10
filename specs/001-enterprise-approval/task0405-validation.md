# TASK-04 / TASK-05 验证记录

## 本轮范围

TASK-04 提供组织、角色、账号确认及独立范围授权；TASK-05 提供固定类型草稿、当前权限查询、版本及幂等写入协议。没有新增 SQL 迁移或依赖；原 V1—V4 校验和不变。没有实现 TASK-09/TASK-10 的模板或流程代码。

接口与客户端约束分别见[组织管理 API](<organization-api.md>)和[草稿公共写协议](<draft-write-protocol.md>)。后端接入说明见[运行说明](<../../backend/README.md>)。

## 已执行的构建回归

2026-11-10 16:14:05 +08:00，使用工作区 Java 17.0.17+10 / Maven 3.9.11 执行：

```powershell
. ./scripts/use-java.ps1
./backend/mvnw.cmd -f backend/pom.xml -B -ntp spotless:apply verify
```

结果：BUILD SUCCESS，72 个测试全部通过，失败、错误、跳过均为 0，Checkstyle 违规 0，Spotless 检查通过并生成可执行 jar。

| 测试类 | 数量 | 主要检查 |
|---|---:|---|
| ApplicationServiceTest | 11 | 幂等指纹及重放、对象版本、白名单、范围查询、费用删行解绑与共享文件保留 |
| FixedDraftValidatorTest | 5 | 三类固定草稿语法、不完整输入、时间/精度、稳定行键 |
| BusinessAccessTest | 5 | 当前身份、独立授权能力与范围、他人草稿不可见 |
| OrganizationServiceTest | 19 | 配置与对象双版本、锁序、末位管理员、负责人关系、授权、审计和回滚、角色写入不要求 UPDATE 权限 |
| OrganizationControllerTest | 4 | HTTP 参数、严格 JSON 类型与字段白名单 |
| AuthServiceTest | 17 | 原身份服务回归及权限摘要不赋予访问权限 |
| IdentityHttpTest | 9 | 原凭据/CSRF 回归及业务路由账户状态限制 |
| HealthHttpTest | 2 | 无数据库健康接口和默认拒绝 |

构建期间发现并修复：固定草稿校验器将 `time` 错当作 `t数字` 文本上限，现以 `t[0-9]+` 区分；组织测试补齐 JdbcTemplate 无参重载模拟和 Mockito varargs 取参，不为测试弱化生产授权。报销费用在 `payload.items`，不是新增 `payload.expenses`；专门补充费用删行回归。

首轮实库验证在确认用户时发现 503：角色关系的 `ON DUPLICATE KEY UPDATE` 需要 UPDATE 权限，但 V3 按设计只授予 SELECT、INSERT、DELETE。已改为配置锁内的 `INSERT SELECT ... LEFT JOIN ... IS NULL` 幂等插入，新增权限回归测试，不扩大运行账号权限、不改历史迁移。集成脚本也改为只有全部预期检查组通过才给出 PASS，失败尝试不计入成功证据。

## 真实 MySQL 集成验证

使用[集成脚本](<../../scripts/verify_task0405_mysql.py>)在隔离 MySQL 8.4.7 中运行冻结 jar，应用仅使用 `approval_app`、关闭运行态 Flyway，迁移独立使用 `approval_migrator`。2026-11-10 16:16:05 +08:00 生成的[机器证据](<../../backend/evidence/task0405-mysql.json>)为 PASS：11/11 组，134 次 HTTP 请求、1 次应用启动、83 次成功响应信封检查。脚本退出码 0，应用、临时 jar 和隔离容器均已清理。

- Java 运行时区：Asia/Shanghai。应用更新时间、提交时间和审计创建时间均与 SQL DATETIME 按 UTC 解释后的值严格对照；没有出现 8 小时偏移。
- MySQL 镜像：`mysql:8.4.7@sha256:0426ec38c7a10aa45ba383887df7878f74ee70e2fd589c7b69207f3577901903`。
- 本轮成品 jar SHA-256：`5b4d63d18a28c7d9a7d33e8fc54127a62e4817f15fbd1b92e403f68a021fa410`。迁移与运行使用同一冻结副本；测试期间未执行 Maven。V1—V4 摘要与历史记录一致。

| 实库检查组 | 结果及核对内容 |
|---|---|
| 隔离启动与基础夹具 | PASS；新库迁移、独立迁移账号与最小权限运行账号，SQL 只准备初始管理员/员工及明确标注的测试夹具 |
| 确认、驳回与审计 | PASS；通过管理 API 改状态、部门及员工角色，审计可读且无凭据，分页、非管理员拒绝及陈旧版本有效 |
| 部门、角色及末位管理员 | PASS；创建/重命名、负责人资格、责任保护、角色差异审计，末位有效管理员不可移除 |
| 配置版本竞态 | PASS；两个管理员持同一对象/配置版本并发写入，仅一个 200、另一个 409，配置版本只增加一次 |
| 草稿幂等、版本及可见性 | PASS；同键并发创建只得到一个字符串大 ID，指纹区分正文与目标，重放、陈旧版本、白名单、行键、他人草稿和计数隔离 |
| 固定草稿日期时间 | PASS；合法 UTC-Z 差旅字段保存，offset、乱码、不存在的日期时间及采购日期均返回 400 |
| 范围授权与正文隔离 | PASS；管理员/财务角色、STATISTICS 均不隐含 READ_BODY；部门范围、负责人变更、有效期和撤销即时影响查询，他人草稿仍不可见 |
| 重置密码与禁用 | PASS；通过 API 撤销旧会话、强制改密及禁用后拒绝旧凭据 |
| 审计及幂等结果原子性 | PASS；触发器故障使管理对象/配置或草稿/成功记录一起回滚，恢复后相同请求键可成功 |
| 删除重放与共享文件 | PASS；删除后相同键仍返回 204；解绑与清理候选原子提交，共享文件及另一绑定保留，不删除字节 |
| 锁后重验与超时恢复 | PASS；等待配置锁期间撤销管理员角色，取得锁后返回 403；3 秒锁等待超时返回可重试 503，释放后恢复成功 |

复现（先按上文构建；需要本机 Docker 与已缓存/可拉取的固定 MySQL 镜像）：

```powershell
. ./scripts/use-java.ps1
python scripts/verify_task0405_mysql.py --skip-build
```

脚本只收集脱敏响应和结构化 SQL 核对，不保存账号密码、原始会话或 CSRF 凭据。SQL 测试夹具不表示已实现正式提交、审批或附件上传。

## 仓库最终检查

同步 TASK-04/05 为 `done` 并附证据后，`python scripts/verify_specs.py` 通过：12 组需求、16 项任务、22 项验收定义、链接及证据路径一致。设置 `DEPLOY_JAVA_HOME` 后运行 `python -m unittest discover -s tests -p "test_*.py" -v`，70/70 通过、无跳过，耗时 4.316 秒；`git diff --check` 返回 0。最终 jar 摘要与实库证据一致。原有报告改动保留；未执行 Git 提交或推送。

## 集成边界

- 管理员、财务角色没有隐含正文或统计权限。正文与统计授权独立；部门负责人仍需当前有效关系；他人草稿始终不可见。
- 未集成学生流程模块前，启用策略指针或在审申请使责任引用无法证明安全时，相关人员/部门危险调整返回 409 `RESPONSIBILITY_CHECK_UNAVAILABLE`。这不是完整策略/节点引用检查，也不自动改派。
- CUSTOM 和正式源单复制明确返回 503，不写半成品。固定类型草稿可保存未填写完整的字段，但提交和正式扩展正文尚不可用；已提交申请返回基础元数据与 `bodyAvailable=false`。
- 删行/删草稿只解绑并登记清理候选，不删除共享文件对象或字节。上传、预览、物理清理等附件 API 属于 TASK-06。
- 两端按申请串行写队列、断网重试及版本冲突合并在协议中定义，实际界面集成属于 TASK-13/TASK-14，本轮未实现。
- T01—T22 端到端业务验收仍为 NOT_RUN；本轮不声称采购/流程闭环、真实小程序或 Compose 整体部署通过。
