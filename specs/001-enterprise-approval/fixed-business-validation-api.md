# 固定表单完整校验与冻结数据契约（TASK-07/08）

## 边界

本模块只实现三种固定表单的语法、完整性、金额和附件检查，以及关联申请摘要读取。它不提交申请，不分配正式编号，不创建流程实例、不写正式明细、不计算审批路线，也不执行审批节点。TASK-09/10 的学生实现应在自己的提交事务中复用校验器，然后自行完成正式行映射与冻结持久化。

草稿允许缺字段，已填写字段仍必须符合类型、长度、枚举与十进制格式。完整校验不是草稿保存的替代接口。预检成功也不是提交成功；申请内容、附件与原申请状态在预检后可能变化，提交时必须重新锁定和检查。

## 只读预检接口

`POST /api/v1/applications/{id}/validate`

要求有效登录、当前启用且无强制改密限制；仅本人草稿可校验。Cookie 客户端遵守现有 Origin/CSRF 协议；Bearer 客户端不得混用会话 Cookie。请求只接受：

```json
{"expectedVersion":"3"}
```

不使用 `Idempotency-Key`，因为接口不执行写入。请求中不能带客户端计算金额或快照来覆盖服务器结果。

成功使用统一 `{data, requestId, serverTime}` 信封，其中 `data` 包含：

- `id`、`rowVersion`：十进制字符串；
- `amount`：服务端重算、两位小数的字符串；
- `naturalDays`：差旅北京自然日数，其他表单为 `null`；
- `formSnapshot`：下面定义的待冻结数据，不代表已经保存到任何流程表。

版本过期返回 409；非本人或不存在的草稿隐藏对象返回 404；字段不合法返回 400 并提供可定位的 `fieldErrors`；关联冻结源缺失或不兼容返回 503。预检在 READ COMMITTED 下读取，并在成功返回前再次检查主表版本和状态；不会加写锁、增加版本、预占编号或写幂等记录。

## 固定字段

采购字段沿用草稿协议。金额必须是字符串且恰有两位小数，数量最多三位小数。每行数量×含税单价先 HALF_UP 到两位，再累加运费及其他费用；最终金额不得超过 10000000.00。预算必须覆盖最终金额，选定的整单报价必须与最终金额相等。2–10 家报价，只有一份报价时必须填写单一来源原因；非最低价选择必须有原因。报价均须有供应商、日期、总价及文字依据或对应附件；线上报价额外要求 HTTP(S) 链接和 JPEG/PNG 截图。服务端不访问这些链接。

差旅：`origin`、`destination`、`startAt`、`endAt`、`purpose`、`transport`、`budget` 为完整表单所需字段。时间必须是带 `Z` 的完整 UTC 时间，开始不能晚于结束；按 `Asia/Shanghai` 转换后做日历日期差加一，同日为一天。`transport` 固定为 `TRAIN/PLANE/CAR/BUS/SHIP/OTHER`，`OTHER` 要求 `transportOtherReason`。`companions` 为可选文字。

报销：需要描述及 1–50 行费用；`type` 固定为 `TRANSPORT/ACCOMMODATION/MEALS/OFFICE/OTHER`，每行有费用日期、用途和正金额。每行必须明确 `noReceipt`。无票时填写原因且不能保留票据绑定；有票时不能填写无票原因，并要求至少一个该行的 `EXPENSE_RECEIPT` 绑定。票据号码属于每个附件绑定，不属于费用行；同一费用行可以有多张票。不关联原申请时必须填写 `independentReason`。

## 供学生提交内核使用的数据

`FixedBusinessValidator.validate(applicantId, type, title, remark, payload, bindings)` 返回 `amount`、`naturalDays` 与新建的 `formSnapshot` JSON 对象。输入附件元数据必须来自已授权的数据库读取，不得从客户端直接构造；调用者仍负责事务锁序、当前权限、版本和附件再次校验。

快照结构固定为：

```json
{
  "schemaVersion": 1,
  "type": "TRAVEL",
  "title": "出差申请",
  "remark": "",
  "payload": {},
  "amount": "20000.00",
  "naturalDays": 2,
  "relatedApplicationSnapshot": null,
  "bindings": []
}
```

`payload` 是校验通过的固定表单副本。完整校验仅接受 `READY`、`integrity_status=OK` 且 `verified_at` 非空的附件；迁移遗留 `UNKNOWN` 对象先完成私有卷核对，已标记 `DAMAGED` 的对象不得作为新的提交依据。绑定摘要保存绑定 ID、对象 ID、用途、稳定行键、票号、原始名称、MIME、字符串字节数、SHA-256 与创建时间；不暴露物理存储路径。调用者应将返回值作为提交时的不可变快照保存，而不是保存请求提供的同名对象。

## 关联申请的只读适配

`FixedBusinessRelatedApplicationSource` 是与学生流程模块的接口。默认适配器先限制本人、`APPROVED` 且 `PURCHASE/TRAVEL`，再读取 `flow_instance.form_snapshot`；预期该表按 `application_id` 可得到唯一快照，快照使用上面的 `schemaVersion: 1` 固定形状。TASK-06/07/08 不创建该表。学生实现采用不同存储结构时，替换此接口的 Bean，不能回退读取可编辑草稿来伪造已冻结正文。

表尚未实现、无快照、多个快照或格式不兼容时均安全失败 503。非本人、未通过或报销类原申请返回字段错误。允许同一已通过申请被重复关联，不占用或扣减预算。

报销输出的 `relatedApplicationSnapshot` 固定原申请 ID、编号、类型、标题，以及：

- 差旅：出发地、目的地、开始/结束时间和预算；
- 采购：项目及预估总额 `expectedTotal`。

这只是冻结摘要，不等于取得原申请正文权限；打开原申请仍须走正文鉴权。

## 验证范围

单元测试、真实 MySQL/JAR 运行结果见后续 TASK-06/07/08 验证记录。测试中显式创建的最小流程快照表仅为隔离夹具，不是生产迁移，也不构成 TASK-09/10 实现或整组 T01–T22 验收通过。
