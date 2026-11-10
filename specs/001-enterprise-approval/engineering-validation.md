# 工程修订版增量验证记录

日期：2026-11-10。范围为设计基线同步和文件对象数据库兼容，不是审批业务验收。

## 基线与历史

- 当前权威：[工程修订版 PDF](<../../docs/midterm/中期设计文档-工程修订版.pdf>)，SHA-256 `938b113b1694c3a0146b83bb9da3be93af04ed6bffdc388c57bf48bba7b3ddab`，与交付原件一致。
- [优化版历史副本](<../../docs/midterm/中期设计文档-修订版.pdf>)及 TASK-00/01/02 的历史验证记录保留，不覆盖原证据。
- 本轮开始时已有中期说明与工作源稿修改；保留这些修改，未重新编译报告，未对 PDF 做视觉排版验收，未自动提交或推送。
- REQ-01—REQ-12、TASK-00—TASK-15、T01—T22 编号和既有任务状态保持不变。TASK-01 仍在进行；TASK-02 的 done 指原 V3 数据库基线，不表示新版附件 API 已完成。TASK-09/10 仍为学生独立编码。

## 数据库增量决策

[V4 迁移](<../../backend/src/main/resources/db/migration/V4__engineering_file_lifecycle.sql>)只调整文件对象生命周期，不创建模板、实例、节点、条件解释器或流程执行代码。

1. 保留 V1—V3，不修改其 SQL 校验和；运行中系统须先按维护屏障停止业务写入、上传和清理，再迁移。
2. 旧 TEMP/BOUND/FROZEN 均已有大小和摘要元数据，将其对象状态映射为 READY；是否绑定、是否随已提交申请冻结仍由原绑定和申请关系表达。保留原 ID、存储键、摘要、时间戳和全部绑定。不在迁移中删除文件或历史。
3. 先创建 UPLOADING 元数据时尚不知道最终大小与 SHA-256，故两列允许 NULL；READY 必须有正大小和摘要。UPLOADING 必须有 `upload_lease_until`，其他状态清空该租约，另建租约/清理时间索引。15分钟租约、2分钟上传上限、过期后不可完成、仅 READY 可绑定/预览和引用保护由 TASK-06 服务实现；SQL CHECK 不代替这些行为。
4. 迁移只确认元数据兼容，不能确认私有卷字节存在或正确。启用文件服务前须对账；缺字节或摘要异常告警并拒绝新绑定，不删除历史绑定。
5. MySQL 多条 DDL 不构成可整体回滚事务；失败应停在维护模式，检查实际 schema、数据和 Flyway 历史后修复，再继续。不能无条件 repair 或重跑，也不通过改 V1 或清空库解决。

本轮复核保留 V4 原 SQL：MySQL CHECK 会接受 UNKNOWN，因此不能只写 `byte_size > 0`。现有 READY 约束显式使用 `byte_size IS NOT NULL` 与 `sha256 IS NOT NULL`；真实 MySQL 负向测试分别覆盖仅大小为 NULL（摘要有效）、仅摘要为 NULL、两者均 NULL 和大小为 0，均应返回 3819。上传阶段两列均为 NULL 则允许，不能把 READY 规则误扩展到 UPLOADING。

## 验证结果

| 检查 | 实际结果 | 证据与边界 |
|---|---|---|
| 交付原件、仓库 PDF 与规格清单 | PASS | 工程版 SHA-256 一致；12 组需求、16 个任务、22 个验收定义及链接/状态/独立编码边界检查通过 |
| 真实 MySQL：空库 V1—V4 | PASS | 18 张业务表，无演示用户或文件；不是整体 Compose 验收 |
| 真实 MySQL：含旧文件和绑定的 V3 升 V4 | PASS | 三种旧状态均映射为 READY；元数据、时间戳、草稿及已提交 IN_REVIEW 申请、三条绑定、业务账号权限和 V1—V3 校验和不变 |
| 真实 MySQL：新约束与最小权限 | PASS | READY 空大小/空摘要/双空/零大小被拒绝；状态非空、租约约束和索引通过；业务账号可写文件，不能 CREATE/ALTER/DROP、读取 Flyway 历史或 UPDATE/DELETE 审计 |
| 真实 MySQL：重复迁移 | PASS | 文件数据与全部迁移校验和不变 |
| 真实 MySQL：篡改校验和负向门禁 | PASS | 仅在一次性库将 V4 历史校验和篡改，Flyway 明确报 checksum mismatch 并失败；还原原值后迁移成功，未执行 repair |
| Python 回归 | PASS，68/68，最终 3.985 秒，无跳过 | 显式设置 DEPLOY_JAVA_HOME，包含六项真实 JDK 健康探针测试；新增五项工程版静态回归，不是文件 API 验收 |
| 后端 Maven verify | BUILD SUCCESS，2/2 测试，0 失败/错误/跳过 | Java 17.0.17+10、Maven 3.9.11；Spotless/Checkstyle 通过，健康接口测试和迁移打包成功，非业务测试 |
| 变更空白检查 | PASS | git diff --check 返回 0；只有 LF/CRLF 提示 |

数据库环境为 MySQL 8.4.7，固定镜像摘要、迁移摘要、运行时间和五组结果见[独立 JSON 证据](<../../backend/evidence/engineering-v4-mysql.json>)，本轮生成于 2026-11-10 14:53:28（Asia/Singapore）。临时容器已清理，未迁移用户已有数据库。

### 14:53 增量门禁复核

本轮使用现有 jar，未启动 Maven，避免与主线程构建并发。`python -m unittest discover -s tests -p test_engineering_baseline.py -v` 返回 0，7/7 通过（0.002 秒，无跳过）；`python scripts/verify_engineering_mysql.py --skip-build` 返回 0，5/5 数据库检查通过。前置检查确认 jar 内 V1—V4 与当前 SQL 逐字节相等，jar SHA-256 为 `775f270454134feded4cb52fdc646b4ab15e8fd8100ea0836b18c68e81abf97c`，V4 SHA-256 为 `b23c43a8bcf6441e23e1a60b6058d95a9331d2e84996fdc58ff5909f3a4450d4`；独立证据同时记录 V1—V4 各自 SHA-256。此前还以未增强脚本执行同一 `--skip-build` 命令，原四组检查全部通过。

本轮未修改 V4、V1—V3、认证代码、manifest、tasks 或 README，未生成 TASK-09/10 业务实现。表中 68/68 Python 全量回归和 Maven verify 是先前验证记录，不是本次重跑结果；本轮仅重跑上述七项工程静态回归及真实 MySQL 增量门禁。

回归过程中首次加入新验证记录到清单后，检查器的固定文档集合尚未同步，触发 `documents: managed document coverage mismatch`；已将该记录同时纳入检查器要求集合，最终完整重跑通过。第一次未设置 DEPLOY_JAVA_HOME 时六项探针测试跳过，后续已配置 JDK 并全部实跑通过。全部业务 T01—T22 仍为 NOT_RUN。

## 复现

```powershell
. ./scripts/use-java.ps1
$env:DEPLOY_JAVA_HOME = $env:JAVA_HOME
python scripts/verify_specs.py --source-pdf "..\交付\中期设计文档-工程修订版.pdf"
python -m unittest discover -s tests -p "test_*.py" -v
python scripts/verify_engineering_mysql.py
./backend/mvnw.cmd -f backend/pom.xml -B -ntp verify
git diff --check
```

新脚本默认在一次性 MySQL 容器验证空库 V1—V4、含三种旧状态和草稿/已提交申请绑定的 V3 升级、NULL 反绕过/业务账号权限、重复迁移及 Flyway 校验和篡改拒绝，生成[独立数据库证据](<../../backend/evidence/engineering-v4-mysql.json>)。已有主线程构建时使用 `python scripts/verify_engineering_mysql.py --skip-build`，不并发启动 Maven；`--skip-build` 仍检查 jar 中全部迁移与源码字节一致，防止误用旧 jar。`--keep` 才保留容器供检查。旧 TASK-02 验证脚本显式固定到 V3，可继续复现历史，但不能作为 V4 验证。

## 尚未执行的业务项

全部业务 T01—T22 仍为 NOT_RUN，包括上传进程中断/重命名后崩溃/物理删除重试、私有卷对账、两端串行写队列、锁后权限时点、交接时钟、预览提交差异、规则结构版本、维护备份、整体 Compose、真机与性能。数据库 DDL/DML 或规格静态检查不能证明这些 API、事务或跨端行为已实现。
