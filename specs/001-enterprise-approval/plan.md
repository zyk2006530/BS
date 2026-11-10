# 企业审批系统技术计划

## 1 基线、选型与实现边界

本计划落实[需求规格](<spec.md>)，权威来源为[工程修订版 PDF](<../../docs/midterm/中期设计文档-工程修订版.pdf>)（SHA256 `938B113B1694C3A0146B83BB9DA3BE93AF04ED6BFFDC388C57BF48BBA7B3DDAB`）第1—9章；[report.typ](<../../docs/midterm/report.typ>)为工作源稿；PDF为权威，本轮未重新编译验证源稿与PDF的对应关系。当前已进入 TASK-01 工程骨架，具体交付与环境缺口见 [TASK-01 验证记录](<task-01-validation.md>)。**下列表、业务模块和业务 API 仍为拟建契约，不是现存业务实现；全部业务验收 NOT_RUN**。

| 层 | 拟采用 | 选择与边界 |
| --- | --- | --- |
| 桌面 | Vue 3、TypeScript、Element Plus、ECharts | 办理及管理配置；类型化请求，金额字符串，不用浮点数决定路径 |
| 微信小程序 | uni-app | 四类办理、拍票、审批、进度、通知；不以H5替交付，管理只桌面 |
| 后端 | Java 17、Spring Boot 3、Spring Security、MyBatis-Plus | 模块化单体，控制器白名单校验、服务层授权及事务、DAO受授权查询 |
| 数据 | MySQL 8/InnoDB、Flyway | 业务、会话、限流、通知/邮件/清理任务同库持久；独立MySQL集成验证 |
| 文件/部署 | 私有附件卷、Nginx、Docker Compose | 同源HTTPS，随机对象键；frontend/backend/mysql三服务 |
| 测试 | 后端单元/集成、桌面端到端、k6、小程序工具及真机 | 依赖/镜像在建工程时锁定确切版本，不把大版本选择冒充已锁定依赖 |

不引入Redis、消息队列、WebSocket、JWT或微服务。自定义配置编辑、动态表单、模板规则解析、供自定义调用的共用流程执行模块由学生独立编码；本文只提供需求、实体边界和接口契约，不生成上述执行代码。不得把课程受限模块移到“公共引擎”后由AI实现。独立实现的阶段提交、测试证据单独保留。

## 2 拟建目录与模块责任

以下为最终模块责任规划；TASK-01 已建立顶层工程和 health 入口，业务子目录按后续任务创建，不能将目录存在等同于功能实现：

```text
backend/
  pom.xml
  src/main/java/.../
    identity/          账户、持久会话、CSRF、资料
    organization/      部门、角色、显式数据授权、管理版本
    application/       公共草稿、查询、固定业务正文边界
    purchase/ travel/ reimbursement/
    workflow/          状态/事务契约；受独立编码约束的执行由学生编写
    custom/            模板、动态字段、规则；仅学生独立实现
    file/              私有对象、绑定、摘要预警与清理
    notification/      收件箱摘要、签名边界、邮件及催办任务
    statistics/ audit/ 授权聚合、追加审计
    common/            序列化、错误、追踪号，不放受限执行逻辑
  src/main/resources/db/migration/
  src/test/
web/src/{api,views,components,router,stores}/
mobile/src/{api,pages,components}/
tests/{integration,e2e,performance}/
deploy/                Dockerfile、Compose、Nginx、环境变量说明
scripts/               幂等基础初始化、独立演示种子、备份恢复
```

前端请求封装共享契约，不共享Cookie/Bearer认证存放策略；服务端授权是最终裁决。轮询组件不实现离线审批队列。模板定义及动态执行的代码边界按实际用途审查，不只按目录名。

## 3 数据实体与关键约束

依据PDF第4章（第11—16页）。普通ID及外键BIGINT UNSIGNED，接口十进制字符串；版本从1、计数从0并检查溢出。时间DATETIME(6)/UTC、日期DATE，北京时间仅业务换算。金额DECIMAL(14,2)，数量DECIMAL(12,3)，JSON按固定结构校验，不执行任意JSON规则。正式必填NOT NULL，选填文本空串，未知关联/时间NULL，不用0占外键。

| 实体组 | 拟建实体及关键约束 |
| --- | --- |
| 身份组织 | department名称唯一、负责人可空但依赖规则时不可提交；user规范用户名/邮箱二进制排序唯一，状态PENDING/ACTIVE/REJECTED/DISABLED，确认部门/拒绝原因条件必填；role.code唯一，user_role联合主键 |
| 显式授权 | data_grant能力READ_BODY/STATISTICS、范围DEPARTMENT/ENTERPRISE；部门范围必有部门，企业范围部门空，validUntil>validFrom；当前时间左闭右开且未撤销才有效；不覆盖历史 |
| 会话/全局配置 | auth_session.token_hash BINARY(32)唯一，无明文；system_config唯一id=1，active_policy_id可空至首次发布、row_version保护全管理域；user_role/data_grant集合无独立版本，普通资料只增user版本 |
| 模板/策略 | flow_template.code唯一、current_version_id必须属于本模板；template_department联合主键，启用至少一部门；template_version(template_id,version_no)唯一、schema_version从1起标识解释结构、发布不可变；policy_version不可变、配置行切换生效指针；固定业务模板版本空，自定义必填 |
| 申请/草稿 | application编号唯一，类型四类、状态五类，草稿编号/正式金额/提交时间空；application_draft以申请ID为主外键，保存payload及schema版，提交后删；提交后恰有一匹配业务正文及一flow_instance |
| 固定正文 | purchase、travel、reimbursement以申请ID主外键；purchase_item/expense_item行号本单唯一、另UQ(application_id,id)供复合外键；费用日期/类型必填；supplier_quote及拟选报价必须同单，先插采购空选择再插报价回填，提交结束前不可空 |
| 自定义正文 | custom_form与application引用同一发布版本；字段值JSON、类型引用由学生独立实现，固定业务不伪造模板版本；自定义application.amount空 |
| 实例/历史 | flow_instance.application_id唯一，必有policy_version_id及冻结快照，form_snapshot.relatedApplicationSnapshot保存关联原单编号/类型/标题和差旅地点日期预算或采购项目预计总额，随本单授权返回而原单正文独立鉴权；instance_node(instance_id,order_no)唯一，分配版本默认1，持申请锁维护最多一PENDING；approval_record只追加提交/批准/驳回/撤回/改派，记录版本/操作人快照/结果/请求键 |
| 文件 | file_object.storage_key唯一、原名仅展示；status=UPLOADING/READY/DELETING/DELETED，upload_lease_until及updated_at；file_binding UQ(application_id,file_id)、UQ(application_id,receipt_sha256)，SHA仅票据；用途GENERAL/QUOTE_EVIDENCE/EXPENSE_RECEIPT/CUSTOM_FIELD，费用/报价正式引用用(application_id,id)复合外键 |
| 草稿绑定 | 费用/报价恰有draft_target_key而无正式行FK，键命中本单相应数组；通用不设目标，自定义用有效field_key；提交锁内建正式行、服务端映射外键清草稿键，自定义field_key保留，删草稿副本，任一步失败回滚 |
| 收件箱 | 注册同事务建inbox_state(user_id)，last_delivery_seq/revision初始0；notification UQ(recipient_id,event_key)、UQ(recipient_id,delivery_seq)，事件键二进制比较；变更先锁用户收件箱 |
| 请求/提醒 | request_record UQ(actor_id,action,request_key)，完整指纹及成功结果，保留至归档；target_id是定位值无阻止删草稿的FK；reminder_record UQ(node_id,assignment_version,trigger_type) |
| 邮件/限流/清理 | mail_delivery.notification_id唯一，邮箱快照、重试/租约/脱敏错误；rate_limit_bucket(散列bucket_key,window_start)联合主键原子计数定期清；file_cleanup持久重试；audit_record追加脱敏前后值和原因 |

用户/部门/模板停用不级联删，来源申请/模板/策略RESTRICT，已提交正文/日志/实例不可删，业务账号无审批/审计UPDATE和DELETE权，迁移账号单独。初始化先部门、再用户、最后负责人解决可空循环引用。

工程修订版兼容迁移追加V4，不改已执行V1—V3。文件上传尚未完成时byte_size、sha256可空；READY要求byte_size>0且sha256非空；UPLOADING要求upload_lease_until非空，其余状态租约为空。旧TEMP/BOUND/FROZEN映射为READY仅表示数据库对象就绪，不改变绑定、创建更新时间或历史，不证明磁盘字节实际存在；正式服务仍须核对私有卷及摘要。TASK-02历史done保留，V4升级/空库迁移与数据保持的补验独立记录于[工程增量验证](<engineering-validation.md>)；上传/清理/快照等业务验收仍NOT_RUN。

主要索引按最终SQL验证：申请(applicant_id,submitted_at,id)、(dept_id,status,submitted_at,id)；节点(approver_id,status,activated_at,id)、(status,due_at,id)；记录(actor_id,action,created_at,application_id)；通知(recipient_id,read_at,delivery_seq)；票据(receipt_sha256,application_id)；邮件(status,next_retry_at,id)；审计(actor_id,created_at,id)/(target_type,target_id,created_at,id)。已办先按本人过滤分组，再联查受权正文，避免联表重复破坏分页。

## 4 身份、CSRF及安全参数

依据§3.2/3.4（第7—11页），实现前应写认证矩阵与负例测试。

| 控制 | 拟实现参数及失败行为 |
| --- | --- |
| 密码 | Argon2id内存19,456 KiB、迭代2、并行度1、盐16字节/摘要32字节，明确加密提供者依赖；Unicode 8—64字符不截断；不存在账号也作同类哈希验证，统一凭据错误 |
| 会话 | CSPRNG 32字节不透明令牌，只存SHA-256，登录轮换、当前退出、改密重置停用撤全；绝对7天、空闲30分钟，成功鉴权更新last_seen_at不延绝对；持久非JWT |
| Web | sid根Path无Domain，生产HttpOnly/Secure/SameSite=Lax；GET /auth/csrf短期预认证不创建登录会话，Cookie写包括登录注册退出均验关联CSRF和可信Origin，成功登录轮换上下文 |
| 原生 | /native-auth匿名注册登录不接受Cookie，不读Web会话，不设Cookie；登录凭账号密码返令牌，无需既有Bearer；后续仅Bearer且无Cookie免CSRF，双凭据拒绝，不信User-Agent/clientType |
| 限流 | IP登录20/分钟、注册10/分钟；规范账户15分钟5次失败；上传账户30/分钟；MySQL散列桶原子计数、429与Retry-After，代理仅信自写来源头 |
| 输入输出 | JSON≤1MiB；短文本/标题/正文80/200/2000字；参数化SQL、筛选排序白名单；纯文本转义禁HTML；CSP同源、object-src禁止、禁止第三方框架嵌套 |
| 文件/缓存 | JPEG/PNG/PDF头检，默认10MiB/20个/100MiB且硬上限内只收紧；私有卷、随机键、受限预览、安全Disposition/nosniff；会话/摘要/详情no-store |
| 来源/日志 | 生产Web/API同源，CORS仅部署清单确切源，无带凭据通配；每请求追踪号，错误脱敏，无密码/哈希/令牌/CSRF/SMTP/临时密码/完整表单/票据正文 |

每请求读当前账户/角色/有效授权，不以旧会话权限快照替代；普通写在申请锁后重验账户、节点和动作，读取当前已提交值而非早期快照；撤权不追溯取消已过检查的在途请求，后续请求用新权限；停用前交接与审批共用申请锁，旧审批人不能在交接后继续处理；管理等待配置锁后再次读启用、强制改密和管理员资格。不可见或不存在统一404，有阅读但动作禁止403。

## 5 状态、事务、幂等与锁序

依据第5章（第16—18页）。本节是实现审查约束，不提供受限流程模块代码。

- 状态：草稿提交转IN_REVIEW，当前PENDING中间批准激活下一WAITING并增申请版本，末节点APPROVED；驳回理由必填并取消剩余，申请人撤回取消未完成，终态不可回退。WAITING审批中可阅读但不计时不审批；终态取消未处理节点阅读来源。
- 提交：配置锁→申请锁，重读账户/当前确认部门/模板适用启用/完整正文/附件归属；BigDecimal逐行HALF_UP到分再合计，金额每项合计≤10,000,000.00，数量≤999,999,999.999。默认采购报销>5,000、差旅>20,000才加企业节点，等值不加。解析完整主备及防自审，冻结同一策略版本/人员/条件原因；无合法人拒绝保草稿。编号一次生成，正文与行绑定映射、删草稿同事务。
- 重试：UUID Idempotency-Key用于申请写、绑定/解绑、交接；已存在对象expectedVersion，创建草稿不收该版本；普通管理写仅版本不承诺键重放。完整指纹包括方法/规范路径目标/动作/规范正文。先当前身份/限制及现存访问，再查同操作者动作键；锁后版本检查前再查，已有成功原样返回，异目标/内容409。删除已成功时原操作者同键同指纹可重放204，不先因目标消失404；交接配置锁后重鉴权→查成功→验两版本，避免自身上次增版本误冲突。
- 创建无申请锁：草稿和request_record同事务，唯一键冲突回滚本次新草稿，再读首个成功结果；同键并发等待首事务结束。只保存成功结果，失败可修正用新键；事务失败所有附属记录回滚。
- 原子集合：业务正文、节点、申请版本、审批日志、站内通知、邮件任务、请求结果同库提交，通知展示/邮件发送在提交后。冻结规则供批准驳回撤回催办，不因组织改动自动改派。
- 显式锁序：`system_config → user(ID升序) → auth_session → application(ID升序) → file_object(ID升序) → inbox_state(user_id升序)`，不需要层跳过。普通审批不额外锁全体用户；提交为配置→申请→文件→收件箱，批准/驳回/撤回及催办为申请→收件箱；管理交接为配置→涉及用户/会话→申请→收件箱。一次交接只一个申请，批量维护逐人独立事务，不持锁等待人工。资料/改密为用户→会话，不访问申请或反取配置；绑定申请→文件，清理仅文件不反取申请。收件人排序锁；缺失收件箱由独立修复事务处理，不在申请锁内补建用户数据。配置锁不覆盖传输、摘要或SMTP，SMTP在短事务领取/回写之外发送。锁等待上限3秒；死锁、等待超时整事务回滚，503且retryable=true；用户确认重试复用原键，状态/版本409不自动重放。
- 管理：全管理域expectedConfigVersion，既有对象另expectedVersion，锁配置后重验操作者及最后管理员/主备/负责人/未完成任务，审计与增配置版同事务。冲突人工刷新差异确认，普通管理不自动重放。交接保原快照、追加原人新人原因，同时增分配版本和申请版本，当前待办从交接时重计，WAITING激活/截止仍为空，轮到才计时。
- 文件：上传先建UPLOADING及15分钟租约，单次最长2分钟，写临时路径并校验摘要，同卷原子改名后短事务置READY，成功响应须在READY提交后；只有READY可绑定/预览，不承诺库与卷原子提交。绑定与清理同文件锁，引用共享READY对象不覆盖旧绑定。无引用锁内置DELETING提交，再删临时/正式路径并回写DELETED，字节已不存在视为成功，失败持久重试。24小时未绑定READY、过期UPLOADING入清理；结束上传仅未过期UPLOADING可转READY。每日核对卷与库，24小时无元数据对象复查清理；READY缺字节/摘要错告警、拒新绑、错误可追踪、保历史绑定，不以空文件代替。20个文件是整单上限，与逐行有票/无票校验并用，部分缺票支出拆行。

通知摘要在同InnoDB可重复读只读事务取revision/last_delivery_seq/未读和实时任务摘要，按任务ID排序计算ID/分配版/申请版摘要；不以通知revision或数量代替taskDigest。收件箱锁内新事件分配序号、变更已读增revision；全部已读验证绑定用户/B/15分钟的签名边界，只改≤B，过期409、重复幂等，不吞后到消息。邮件租约、至少一次语义和1/5/30分钟重试；催办每分钟申请锁复验，节点/分配版/类型唯一，24小时默认不改申请版，审批改派先成功则不催旧分配。

## 6 拟建接口与白名单

依据§3.2—3.3（第7—10页）。**表中均为拟建API，未实现**。统一前缀`/api/v1`，表内省略该前缀；`{id}`为服务端ID，非任意客户端标识。普通业务不收申请人、确认部门、正式金额、状态、实际审批人；管理确认和交接可收目标部门/人员并鉴权校验；禁止用未列字段扩大权限；未知/只读字段应明确拒绝。列表view/scope等仅按各接口定义接收。

### 6.1 公共响应与输入

JSON小驼峰；ID/FK/版本十进制字符串，金额固定两位字符串，数量最多三位字符串，不接受指数/千分位/隐式舍入。时间UTC ISO8601带Z，日期YYYY-MM-DD。列表page/pageSize默认1/20、最大100，返回items/total/page/pageSize；筛选白名单type/status/submittedFrom/submittedTo/keyword及有权departmentId，keyword只匹配标题编号≤100、转义通配，无任意字段排序。

成功读取200、创建201及ID、变更200及新版本、删草稿204；业务结果`data`附requestId/serverTime，文件流不包装。详情含id/applicationNo/type/status/rowVersion/正文/amount/currentNode/history/allowedActions。错误code/message/fieldErrors（path/message，可定位数组行）/requestId/retryable，无SQL堆栈服务器路径。400参数；401未认证失效；403可读但禁动作；404不可见或不存在；409版本/状态/唯一竞争/同键异内容；413超限；429带Retry-After；503数据库暂不可用/事务重试耗尽。

### 6.2 身份与资料

| 方法/路径 | 请求白名单/保护 | 拟返回与约束 |
| --- | --- | --- |
| GET /auth/csrf | Web预认证入口 | 短期CSRF上下文，不创建登录会话 |
| POST /auth/register、/native-auth/register | username,name,email,password,requestedDepartmentId；Web需CSRF/Origin，原生拒Cookie | userId/PENDING，不收角色和确认部门 |
| POST /auth/login、/native-auth/login | username,password；入口保护同上 | 状态、mustChangePassword、权限摘要；Web只设Cookie，原生只体返令牌 |
| POST /auth/logout | 当前会话，Cookie需CSRF | 撤当前、重复无业务错误，不用GET退出 |
| GET /profile | 当前本人 | 资料状态及rowVersion，拒绝者另获重申configVersion |
| PATCH /profile | expectedVersion,name,email | 新版本；不收部门/角色 |
| POST /profile/password | oldPassword,newPassword | 撤全部并重新登录；强制改密可访问 |
| POST /profile/confirmation-request | REJECTED；requestedDepartmentId,expectedVersion,expectedConfigVersion | 配置及本人锁后重验资料，清拒因回PENDING，两新版本 |
| GET /departments | 匿名注册可用 | 仅启用部门ID/名称，不暴露成员负责人邮箱内部授权 |

### 6.3 申请、任务及业务载荷

本表变更请求使用UUID请求键；preview-route虽用POST但只读，不列入写入幂等结果，不生成实例编号或修改申请。既有申请携expectedVersion，任务的版本指申请版本。

| 方法/路径 | 请求白名单 | 拟结果/范围 |
| --- | --- | --- |
| POST /applications | type,templateVersionId或sourceApplicationId | 本人草稿id/rowVersion/payload，无编号；创建亦幂等 |
| GET /applications、/applications/{id} | 列表筛选、view=mine/authorized；详情服务端ID | 受权分页/详情，他人草稿始终不可见，无applicantId扩权 |
| PUT /applications/{id}/draft | expectedVersion,title,remark,payload | 仅本人草稿，格式行键合法，新版本/绑定摘要 |
| DELETE /applications/{id} | expectedVersion | 仅本人草稿，删除工作数据/绑定及清理入队同事务，204 |
| POST /applications/{id}/preview-route | expectedVersion；本人已保存草稿 | 配置版本/预计节点/人员；预计路径不锁配置，提交重新解析冻结 |
| POST /applications/{id}/submit | expectedVersion | 重算/解析/冻结，编号/金额/当前节点/新版本及正式行ID |
| POST /applications/{id}/withdraw | expectedVersion,可选opinion | 本人审批中，取消未完成，终态新版本 |
| GET /tasks、/handled | 本人列表筛选，不接其他userId | 当前有效待办/本人有效批准驳回按单已办 |
| POST /tasks/{id}/approve、/reject | expectedVersion,opinion（驳回必填） | 当前本人且非自审，返回状态/版本/当前节点 |
| POST /admin/tasks/{id}/reassign | expectedConfigVersion,expectedVersion,newApproverId,reason；UUID键 | 显式交接验角色部门非自审，两新版本 |

| payload类型 | 可写字段及行定义 |
| --- | --- |
| PURCHASE | reason,expectedDate,project,budget,freightAmount,otherAmount,otherReason,items,quotes,selectedQuoteKey,selectionReason,singleSourceReason,nonLowestReason；items={clientKey,name,specification,quantity,unit,unitPrice}；quotes={clientKey,supplier,quoteDate,total,sourceType,purchaseUrl,basisText}；1—100行/最多10报价 |
| TRAVEL | origin,destination,startAt,endAt,purpose,companions,transport,budget；naturalDays仅服务端算 |
| REIMBURSEMENT | description,relatedApplicationId,independentReason,items；行={clientKey,type,expenseDate,purpose,amount,noReceipt,noReceiptReason}，1—50行；逐票号/文件由绑定保存不随行覆盖 |
| CUSTOM | templateVersionId引用发布定义及formValues，字段/类型/校验来自该版，不允许写系统身份；仅契约，动态执行由学生独立实现 |

### 6.4 文件、通知与统计

| 方法/路径 | 请求白名单 | 拟结果/约束 |
| --- | --- | --- |
| POST /files | multipart文件；账户上传限流 | fileId/原名/类型/大小/摘要，READY提交后才成功、暂未绑，知道ID不授阅读；重传对象绑定仍摘要去重 |
| POST /applications/{id}/bindings | UUID键，expectedVersion,fileId,purpose,draftTargetKey或fieldKey,可选receiptNumber | 本人草稿、归属/用途/容量验，bindingId/新版本 |
| DELETE /applications/{id}/bindings/{bid} | UUID键，expectedVersion | 本单本人草稿解绑，新版本，无引用异步清理 |
| GET /files/{id}/preview、/download | 已绑必须applicationId；临时本人 | 有效绑定/当前单据阅读权，受限预览/文件流 |
| GET /notifications | 本人分页/未读筛选 | items及打开时readBoundary，按delivery_seq倒序 |
| GET /notification-sync | 可选inboxRevision | inboxRevision,notificationChanged,未读数,pendingCount,taskDigest,serverTime；每次查当前任务，不整响应304 |
| POST /notifications/{id}/read | 当前本人通知 | 幂等已读/新摘要 |
| POST /notifications/read-all | 用户绑定readBoundary | 只≤B已读、新摘要，过期409，不扩边界 |
| GET /statistics | scope=PERSONAL/DEPARTMENT/ENTERPRISE、对应部门及提交区间 | 受权数量/分型金额/口径/可否钻取，统计权不含正文 |

两端上传可并行，同一申请保存/绑定/解绑等版本写串行队列，沿上次响应版本；队列清空且选中文件绑定完成才提交。绑定响应丢失复用原键恢复再读草稿/绑定，删行后迟到文件不绑定等清理；本机输入恢复先合并服务端行键和绑定，附件删行及冲突逐项确认，以最新版本保存，不能整体覆盖旧明细。

### 6.5 管理、模板与策略

管理写均携expectedConfigVersion，编辑既有对象另expectedVersion和变更原因；普通管理只版本保护，交接另按上一表幂等。以下模板API同样**仅计划，不生成执行实现**。

| 方法/路径 | 请求白名单 | 拟结果/约束 |
| --- | --- | --- |
| GET /admin/config | 管理身份 | configVersion/有效policyVersionId/允许配置，不返SMTP密钥 |
| GET /admin/users、/admin/departments | 管理分页筛选 | 对象rowVersion/负责人/引用摘要，不能替正文入口 |
| POST /admin/users/{id}/confirm、/reject | 两预期版本；confirm部门departmentId，reject原因reason | 状态/两新版本，拒绝必原因 |
| PATCH /admin/users/{id} | 账户状态或部门、原因及两预期版本 | 先任务/负责人/配置引用检查，不隐式改派 |
| PUT /admin/users/{id}/roles | 角色集合、原因及两预期版本 | 集合diff/两版本，保最后启用管理员 |
| POST /admin/users/{id}/reset-password | 临时密码、原因及两预期版本 | 强制首次改密/撤全会话，临时密码不入日志 |
| POST /admin/departments、PATCH /admin/departments/{id} | 名称/启用/负责人、原因及预期版本 | 新对象/两版本；先交接，引用不物理删 |
| GET /admin/grants、POST /admin/grants | userId,ability,scope,departmentId,validFrom,validUntil,reason及配置版（读取仅筛选） | 期限能力范围校验，保历史；企业二次确认 |
| POST /admin/grants/{id}/revoke | expectedConfigVersion,reason | 提前撤销，不原位覆盖授权历史 |
| GET /templates、/templates/{id}/versions/{vid} | 适用当前用户 | 启用适用发布定义；已有草稿可读引用旧版，停用仍可读本人旧单快照 |
| POST /admin/templates、PUT /admin/templates/{id}/draft | 元信息/适用部门/字段/顺序节点/预期版本 | 配置与对象新版本，不返回执行代码 |
| POST /admin/templates/{id}/publish、/enable、/disable | 预期版本/reason | 发布不可变versionId；启用旧草稿可用旧发布版，停用阻新提交不改旧实例 |
| POST /admin/policy-versions、/admin/policy-versions/{id}/publish | 阈值/主备/部门备选/时长/文件限制及配置版 | 候选先存、发布切指针，同版本人员/阈值冻结 |
| GET /admin/audit-records、/admin/mail-deliveries | 管理分页/操作人对象动作时间等固定筛选 | 脱敏审计/邮件状态及错误，只读 |

模板rule_schema只做字段校验及条件必填，不做显隐、分支或跳节点；其他非附件字段与类型化常量最多8项AND，不嵌套不执行脚本，文本/单选等于不等于，数字金额日期另可大小比较；空引用假，类型错误不隐式转换。字段校验包括长度/上下限/精度/日期范围/单选/附件数。schema_version从1起区别发布版本，旧定义保留解释，不支持版拒绝编辑提交；适用部门按模板当前配置判断。自定义部门负责人沿用主备防自审，指定本人拒绝不替换，同人多节点逐次处理。以上均由学生独立实现。

不提供修改/删除审计、代用户标已读、任意改终态、已提交正文编辑、静默改派或自动迁移旧草稿接口。字段细节落地前与PDF契约和测试数据核对，不能将本表写成“API已可调用”。

## 7 小程序、部署与恢复验证

依据§7.10、第8—9章（第29—35页）。

1. 外部接入门：2026-11-16前记录AppID/主体、HTTPS证书及请求/上传/下载合法域名、体验成员/审核限制、安卓与iOS真机登录和文件传输、SMTP实际手机收信。按平台实际条件核实备案/认证，不以工具关闭校验替真机，不等最后周公开发布。
2. 交互门：320/375/390/414/430宽、130%字体、主触控44×44、正文≥14、边距16；安全区键盘避让，错误文字定位，弹窗焦点。三固定及启用模板全核心流程、拍票/拒相机选择文件、预览返回/上传失败重试。弱网只本账号输入草稿，不离线审批；恢复先读版本人工冲突选择，切号清本地。
3. 同步门：前台10秒摘要、正常变化15秒目标，退避≤60秒恢复立即刷新；待办同数替换也刷新；关闭页面靠邮件，不承诺手机系统强制弹出，不依赖后台计时器。
4. 部署门：Compose 三常驻服务（frontend/backend/mysql）加一次性 migrate 作业、数据库内网、私有命名卷；锁版本，Flyway成功/健康后开放，业务与迁移账号分离。幂等基础三部门五角色不覆盖，独立演示种子含部门员工负责人/财务企业主备/管理员/待确认/三业务及设备借用，密码环境注入且无公网默认演示账号。
5. 恢复门：维护禁止新业务写及上传，停止催办/邮件领取/文件清理，等待在途事务、上传、物理删除及已领取邮件任务结束并回写，不能以租约到期替代任务结束，不能确认时中止备份；数据库/附件/迁移版本/文件摘要同批并完成标记；先校验再恢复，失败保持维护。验收实际读正文/审批历史/通知并下载票据，不能只看容器启动。清理/SMTP中断恢复验证租约及去重。

## 8 开发顺序、测试门禁及交付

| 时间（计划） | 工作与前置门 | 预期出门条件，不代表已通过 |
| --- | --- | --- |
| 2026-11-09—11-16 | 规格/原型、外部接入核实 | 设计提交，AppID/域名/真机/SMTP条件记录 |
| 11-17—11-30 | 工程迁移、身份组织/授权、公共草稿、文件、采购及小程序账户壳 | 采购闭环和确认授权、小程序真机账户路径 |
| 12-01—12-12 | 差旅报销；学生独立实现模板与实例；版本/并发/相同票预警 | T01—T11、T16、T18、T20实测通过方可进入下一门 |
| 12-13—12-20 | 统计、通知邮件催办、小程序全核心 | T12—T14、T17、T19、T21，增强可独立演示 |
| 12-21—12-28 | 权限/日志/恢复回归及性能、真机修复 | T15/T22通过、阻断缺陷关闭，性能有实测 |
| 12-29—2027-01-02 | 验收机空环境复现、手册报告视频打包 | 2027-01-03前交全套，同版本源码标签与视频 |

规格基线已完成，TASK-01 工程骨架已开始；其余业务阶段尚未执行，不把计划日期或工程检查写成业务通过。每功能提交前门禁：契约/需求对应、前后端校验、授权负例、迁移可重复执行、单元/独立MySQL集成、无敏感日志、配置/附属记录原子性；受限模块人工确认独立编码证据。幂等/锁/绑定改动须双客户端及故障回归；阻断并发或催办缺陷时暂停加功能先修申请锁/分配版/唯一键。

验收矩阵沿用[规格追踪](<spec.md>)全部T01—T22。集成必须真实独立MySQL而非内存库替代锁/约束；单元覆盖阈值等值及+0.01、逐行舍入、日期/自然日、授权期限；集成涵草稿映射失败回滚、共享文件清理竞争、成功丢响应重放、撤权等锁、相反收件箱顺序、边界B后到消息、催办改派竞争。T16/T17独立种子及可控时钟，不改生产时间。

性能固定种子10,000单/三部门/四类型/多状态，k6预热2分钟、20并发5分钟，读写分测、各虚拟用户独立单据避免合法冲突冒吞吐；列表详情P95≤1秒、提交审批≤2秒仅验收目标。记录CPU/内存/系统/架构、镜像版本、网络、数据库参数、成功率/P50/P95/最终SQL计划、附件独测。另测20并发提交与管理员发布配置、交接同时进行，记录锁等待、死锁、503及重试结果，区分合法冲突与服务失败。不取消授权过滤换速度。

最终包包括源码/Git过程与标签、迁移初始化、Dockerfile/Compose及变量说明、依赖说明、设计/实验报告、使用手册、逐例实际结果/缺陷及性能记录、操作视频。密钥/数据库卷/上传/构建缓存不入Git。手册覆盖空环境启动、演示初始化、账户管理授权、四类业务、审批附件、备份恢复及错误处理。演示顺序：空环境与确认授权→采购差旅报销→学生独立模板配置/换版→双客户端幂等→真机全核心→关闭小程序邮件、预警催办→统计及重启恢复下载；保留邮件时间编号/节点截止和当前时间，不以原型替实测。
