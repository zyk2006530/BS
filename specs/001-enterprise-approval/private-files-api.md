# 私有附件接口与运维契约（TASK-06）

## HTTP 接口

所有接口位于 `/api/v1`，使用现有会话/Bearer 鉴权；Cookie 写请求还须遵守 Origin/CSRF 协议。匿名、待激活或强制改密账户不能上传。系统管理员角色本身不授予业务正文或附件读取权。

- `POST /files`：仅一个名为 `file` 的 multipart 文件，成功 201。扩展名只接受 JPG/JPEG、PNG、PDF，并检查声明 MIME 与文件头。返回 `fileId`、`originalName`、`mediaType`、字符串 `byteSize` 和十六进制 SHA-256，使用统一成功信封。不返回物理路径。
- `POST /applications/{id}/bindings`：本人草稿写操作，必须有 UUID `Idempotency-Key`。请求为 `expectedVersion`、`fileId`、`purpose`，以及用途需要的 `draftTargetKey` 或 `fieldKey`、`receiptNumber`。成功返回 `bindingId` 与新 `rowVersion`，均为字符串。
- `DELETE /applications/{id}/bindings/{bindingId}`：请求 `{ "expectedVersion": "3" }`，同样使用 UUID 幂等键；成功返回新版本。只移除该绑定，不直接假定底层对象已无人引用。
- `GET /files/{id}/preview` 与 `GET /files/{id}/download`：未绑定对象仅上传者可读；已绑定对象必须附加 `?applicationId=...`，并在当前权限下通过该申请的正文授权。缺少上下文、无对应绑定或无权时返回 404，不泄漏对象存在性。仅 READY 且字节核验成功时返回文件，响应禁止缓存并设置 `nosniff`。原始名称仅用于安全编码后的展示/下载名称，不参与落盘路径。

`GENERAL` 用于申请级附件；`QUOTE_EVIDENCE` 对应采购 `quotes[].clientKey`；`EXPENSE_RECEIPT` 对应报销 `items[].clientKey`，票号逐个绑定保存。固定表单不得用任意字段键绕开用途限制。

绑定/解绑先锁申请并重查当前权限；成功幂等重放先于过期版本判断，但仍须满足当前授权。相同幂等键改变动作、路径或请求内容返回冲突。整单最多 20 个附件、100 MiB；单文件最多 10 MiB。整单文件 ID 或内容摘要重复均不允许；同一上传者可以把一个对象用于不同的本人申请，清理因此必须重新检查全局引用。

## 上传和磁盘

配置 `app.files.root`（例如命令行 `--app.files.root=...`），默认读取既有 `APP_UPLOAD_DIR`；未设置时为进程目录下的 `private-files`。生产部署继续使用后端独占的私有上传卷，不挂给 Nginx，且不得放到任何静态资源根目录。上传物理键为服务端随机值，不包含用户提供的名称。

上传先短事务创建带 15 分钟租约的 `UPLOADING` 行；接收文件、计算摘要与同卷原子改名都不占长数据库事务。传输总期限 2 分钟；改名完成后用短事务检查原状态与未过期租约，再写 `READY` 并清除租约。没有跨卷复制/非原子改名的降级路径。失败或中断对象由持久清理任务回收，不能被绑定或预览。

每账户每分钟最多 30 次上传尝试，计数保存在 MySQL；无效扩展名/头等被拒绝的尝试也计数。超过限制返回 429 与 `Retry-After`。

Nginx 对精确上传路径单独放行 11 MiB 请求，以容纳 10 MiB 文件和有限 multipart 头，并关闭请求缓冲。其他 JSON API 保留 1 MiB 限制；后端仍自行流式计算真实文件字节数，不信任 Content-Length。数据库业务配置关闭 Boot 的 servlet multipart 自动解析，仅由本模块限头流式解析；Tomcat 单独上传读取超时为 125 秒，避免容器默认 60 秒读取超时抢在应用 120 秒截止前触发错误。不要重新启用 eager multipart 或将容器/代理超时调到应用截止之前。

## 清理与核对

`file_cleanup` 持久保存清理任务、重试次数、下次重试时间与任务领取租约。处理时在对象锁下再次确认没有任何绑定，先提交对象 `DELETING`，再删除磁盘字节，最后短事务提交 `DELETED`。已经缺失的字节视为删除成功；IO 失败保留任务并重试。领取后崩溃由租约到期恢复，不依赖进程内队列。

未绑定 READY 超过 24 小时及过期 UPLOADING 对象会被扫描回收。默认每分钟运行清理，每日核对孤儿文件与 READY 的字节数/SHA-256。核对发现缺失或摘要不一致时标记 `integrity_status=DAMAGED`，保留历史对象和绑定，拒绝新的绑定或把其作为完整表单依据，不能为“修复”而抹掉提交历史。首次迁移的既存对象为 UNKNOWN，需要核对后才可作为已验证依据。

运维可配置 `app.files.cleanup-delay-ms`、`app.files.reconcile-delay-ms`；测试缩短调度间隔只影响扫描频率，不放宽 15 分钟/2 分钟/24 小时业务期限。多实例共享同一私有卷时仍须保留数据库领取租约与对象锁，不得自行用定时脚本直接删盘。

本模块不实现正式行映射、提交冻结或流程节点。后续学生提交内核须在提交事务中重查附件状态、绑定关系与当前权限，再自行写入正式行和不可变快照。
