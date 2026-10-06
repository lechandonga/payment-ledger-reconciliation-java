# 支付受理 · 复式记账 · 对账结算（本地一体化）

一个完全本地可跑的 Spring Boot 工程：支付单生命周期、双分录记账、批次对账结算、幂等与并发控制。
数据全部落在本地内嵌 **H2 文件库**（`./data/paymentledger`），不依赖任何外部服务。

## 技术栈

- Spring Boot 3.4 / Java 21（字节码 17，本机 Java 25 可运行）/ Spring Web / Spring JDBC
- H2 内嵌数据库（本地文件，建表脚本 `src/main/resources/schema.sql`，启动自动执行）
- 金额一律使用 `long`，单位：**分**；手续费率 `feeRateBp` 单位为基点（10000 = 100%）

## 本地运行

```bash
mvn spring-boot:run                     # 默认端口 8080
# 端口被占用时：
mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=18080"
```

重置本地数据：删除工程根目录下的 `data/` 后重新启动。

## HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/payments` | 创建支付单（需 `idemKey`） |
| POST | `/api/payments/{paymentNo}/authorize` | 授权（不发生资金变动） |
| POST | `/api/payments/{paymentNo}/captures` | 请款，支持部分、多次（需 `idemKey`） |
| POST | `/api/payments/{paymentNo}/refunds` | 退款，支持多次（需 `idemKey`） |
| GET  | `/api/payments/{paymentNo}` | 查询支付单 |
| POST | `/api/reconciliation/batches` | 用模拟渠道文件执行对账+结算 |
| GET  | `/api/reconciliation/batches/{batchNo}/diffs` | 批次差异留存查询 |
| GET  | `/api/ledger/summary` | 记账守恒指标（余额和、借贷发生额） |

请求示例：

```bash
# 创建（费率 0.60%）
curl -X POST localhost:8080/api/payments -H 'Content-Type: application/json' -d '{
  "idemKey":"K1","merchantId":"M1","channel":"MOCK",
  "amount":10000,"currency":"CNY","feeRateBp":60}'

# 请款 / 退款
curl -X POST localhost:8080/api/payments/PAYxxxx/authorize
curl -X POST localhost:8080/api/payments/PAYxxxx/captures \
  -H 'Content-Type: application/json' -d '{"idemKey":"K2","amount":4000}'
curl -X POST localhost:8080/api/payments/PAYxxxx/refunds \
  -H 'Content-Type: application/json' -d '{"idemKey":"K4","amount":1000}'

# 对账批次（channelEntries 即本地模拟的渠道对账文件；merchantIds 限定本批次范围）
curl -X POST localhost:8080/api/reconciliation/batches -H 'Content-Type: application/json' -d '{
  "batchNo":"B-1","merchantIds":["M1"],
  "channelEntries":[
    {"channelPaymentNo":"C1","paymentNo":"PAYxxxx","amount":2982,"status":"PARTIALLY_REFUNDED"}
  ]}'
```

拒绝响应统一为：`{"accepted":false,"errorCode":"...","message":"..."}`。

## 状态流转

```
CREATED ──authorize──> AUTHORIZED ──capture(部分)──> PARTIALLY_CAPTURED
                          │                              │ capture(剩余)
                          └──────── capture(全额) ───────┴──> CAPTURED
                                                             │
              任意已请款状态（CAPTURED / PARTIALLY_CAPTURED / PARTIALLY_REFUNDED）可退款
   PARTIALLY_CAPTURED ──refund──> PARTIALLY_REFUNDED ──refund(退清)──> REFUNDED
   CAPTURED           ──refund──> PARTIALLY_REFUNDED / REFUNDED
```

- 状态由「已请款 / 已退款」金额事实推导，不存在非法中间态。
- 授权仅是额度确认，不落资金分录；资金从请款开始记账。
- 合法路径：`CREATED→AUTHORIZED→(PARTIALLY_CAPTURED)→CAPTURED→(PARTIALLY_REFUNDED)→REFUNDED`。

## 拒绝原因（可区分的 errorCode）

| errorCode | 触发条件 |
| --- | --- |
| `IDEMPOTENT_PARAM_MISMATCH` | 同一 `idemKey` 重复请求但参数哈希与首次不一致 |
| `ILLEGAL_TRANSITION` | 当前状态不允许该操作（如未授权先请款、已退款再请款等） |
| `INVALID_AMOUNT` | 请款/退款金额 ≤ 0 |
| `CAPTURE_EXCEEDS_AUTH` | 累计请款超过授权金额（剩余额度 = amount − capturedAmount） |
| `REFUND_EXCEEDS_CAPTURED` | 累计退款超过已请款金额（剩余可退 = capturedAmount − refundedAmount） |
| `MERCHANT_BALANCE_INSUFFICIENT` | 退款净额超过商户**未结算**待结算余额；已结算资金不能直接退款，保证商户待结算不为负 |
| `BATCH_CONCURRENT` | 同一批次并发触发，只有一个生效，其余明确拒绝（HTTP 409） |
| `BATCH_INPUT_MISMATCH` | 同批次重复执行但渠道文件内容（哈希）与首次不同 |
| `PAYMENT_NOT_FOUND` | 支付单不存在（HTTP 404） |

## 幂等规则

- 创建、每次请款、每次退款都必须带 `idemKey`。
- 服务端保存 `idemKey → 请求参数 SHA-256 哈希 + 首次响应`。
- 同 key 同参数：直接回放首次受理结果，**不重复执行、不重复记账**。
- 同 key 不同参数：返回 `IDEMPOTENT_PARAM_MISMATCH`，不产生任何副作用。
- 业务被拒绝时不会写入幂等成功记录，调用方可用同 key 修正后重试。

## 复式记账规则

账户（余额借正贷负）：

| 账户 | 含义 |
| --- | --- |
| `CHANNEL_CASH` | 渠道资金（资产） |
| `PROCESSOR_CASH` | 机构自有资金（手续费划转，资产） |
| `FEE_INCOME` | 手续费收入（收入，贷方为负） |
| `PAYABLE_{merchantId}` | 商户待结算（负债，贷方为负；其绝对值不允许为负） |

每笔业务借贷必相等，全部在同一数据库事务内完成，校验失败整体回滚（不留半截账）：

请款 `gross`，手续费 `fee = gross * feeRateBp / 10000`，`net = gross − fee`：

```
借 CHANNEL_CASH          gross
贷 PAYABLE_{商户}         net
贷 FEE_INCOME            fee
```

退款 `gross`，按比例冲回手续费 `feeReversed`：

```
借 PAYABLE_{商户}         gross − feeReversed
借 FEE_INCOME            feeReversed
贷 CHANNEL_CASH          gross
```

- 手续费随退款等比冲回（整数向下取整，末次退款承担舍入差），累计冲回不超过已收手续费。
- 部分请款逐笔记账，多次退款逐笔冲回，不重复扣款、不漏记。
- 退款前校验「未结算净额 ≥ 本次退款净额」，保证 `PAYABLE_{商户}` 不会变成借方（商户待结算不为负）。

结算（批次内对匹配支付单，打商户款净额 + 手续费从渠道资金划转机构）：

```
借 PAYABLE_{商户}         netPayable
贷 CHANNEL_CASH          netPayable
借 PROCESSOR_CASH        feePayable
贷 CHANNEL_CASH          feePayable
```

恒等关系（任意操作序列后成立）：

- 所有账户余额之和恒为 `0`（`GET /api/ledger/summary` 的 `sumBalances`）。
- 借方发生额合计 = 贷方发生额合计（`sumDebits == sumCredits`）。

## 对账匹配规则

批次输入为本地模拟的渠道文件（`channelEntries`：渠道流水号、我方支付单号、金额、状态），
与内部「有未结算资金的支付单」在 `merchantIds` 范围内逐笔核对：

1. 以 `paymentNo` 关联。
2. 金额口径：渠道按**净额**对账，我方期望金额 = `(capturedAmount − refundedAmount) − (feeTotal − feeRefunded)`
   （手续费由我方承担，不下沉给渠道）。
3. 状态口径：渠道状态应等于我方状态（`CAPTURED / PARTIALLY_CAPTURED / PARTIALLY_REFUNDED / REFUNDED`）。
4. 差异分类：

| diffType | 含义 |
| --- | --- |
| `AMOUNT_MISMATCH` | 金额不一致（优先于状态判断） |
| `STATUS_MISMATCH` | 金额一致但状态不一致 |
| `INTERNAL_ONLY` | 我方有未结算单，渠道文件缺失（我方单边） |
| `CHANNEL_ONLY` | 渠道文件有记录，我方无对应待结算单（渠道单边） |

- 差异写入 `recon_diff` 表持久化留存，可经 `GET .../diffs` 查询；批次报告（含输入哈希）存入 `recon_batch.report`。
- 只有**完全匹配**的支付单才在本批次结算；差异单保留待处理，不参与入账。
- 批次结论可复现：相同 `batchNo` + 相同文件重放首次报告，不重复对账、不重复入账；文件被篡改则 `BATCH_INPUT_MISMATCH`。
- 并发互斥：进程内 `IN_FLIGHT` 标记 + 数据库 `recon_batch.batch_no` 唯一键 + 独立事务抢占；
  同批次并发时仅一个生效，其余返回 `BATCH_CONCURRENT`（HTTP 409）。

## 测试

```bash
mvn test
```

日志中按业务分组输出覆盖的业务与每个用例结果（`BUSINESS-TEST` logger，形如
`========== 业务分组X：... ==========` 与 `[用例NN-结果] ... => PASS`）：

- 分组1 支付受理与状态流转：创建/授权/部分请款/多次退款、幂等回放、参数不一致拒绝、
  非法流转、超额请款、超额退款、被拒操作无半截账。
- 分组2 复式记账守恒：多商户、部分请款、多次退款及拒绝操作序列后，余额和恒 0、借贷相等、商户待结算不为负。
- 分组3 对账差异：四类差异识别 + 持久化，仅匹配单结算。
- 分组4 批次幂等：重复执行回放且不重复入账、篡改文件被拒绝。
- 分组5 并发批次：8 线程同批次，成功 1、拒绝 7，无重复结算。
- 分组6 结算后退款：`MERCHANT_BALANCE_INSUFFICIENT`，商户待结算保持 0。

关键类：`service/PaymentService.java`、`service/LedgerService.java`、
`service/ReconciliationService.java`、`service/BatchTxExecutor.java`、
`service/IdempotencyService.java`；测试位于 `src/test/java/.../tests`。
