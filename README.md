# Payment Ledger Reconciliation (Java / Spring Boot)

本地可跑的支付受理、复式记账与对账结算示例工程。所有数据保存在进程内存中，不依赖任何外部服务；金额一律使用最小货币单位（分）的 `long`。

## 运行与验证

```bash
mvn test          # 跑全部单测（日志中带 [CASE]/[PASS] 标记，可看到每个业务用例及结果）
mvn spring-boot:run
```

手工验证（服务启动后）：

```bash
# 1. 创建支付单 -> 授权 -> 部分请款 -> 多次退款
curl -s -X POST localhost:8080/api/payments \
  -H 'Content-Type: application/json' \
  -d '{"idempotencyKey":"c1","merchantId":"m1","amount":10000}'
curl -s -X POST localhost:8080/api/payments/<id>/authorize \
  -H 'Content-Type: application/json' -d '{"idempotencyKey":"a1"}'
curl -s -X POST localhost:8080/api/payments/<id>/capture \
  -H 'Content-Type: application/json' -d '{"idempotencyKey":"cap1","amount":6000}'
curl -s -X POST localhost:8080/api/payments/<id>/refund \
  -H 'Content-Type: application/json' -d '{"idempotencyKey":"r1","amount":2000}'

# 2. 批次结算 + 对账（batchId 只能成功一次）
curl -s -X POST localhost:8080/api/settlements/batch-20261007
curl -s localhost:8080/api/settlements/batch-20261007   # 查看留存的对账报告

# 3. 账本健康检查（借贷守恒）
curl -s localhost:8080/api/ledger/health
curl -s localhost:8080/api/ledger/journal
```

## HTTP 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/payments` | 创建支付单 `{idempotencyKey, merchantId, amount}` |
| POST | `/api/payments/{id}/authorize` | 授权 `{idempotencyKey}` |
| POST | `/api/payments/{id}/capture` | 请款（可部分、可多次）`{idempotencyKey, amount}` |
| POST | `/api/payments/{id}/refund` | 退款（可多次）`{idempotencyKey, amount}` |
| GET  | `/api/payments/{id}` | 查询支付单 |
| POST | `/api/settlements/{batchId}` | 触发批次结算与对账 |
| GET  | `/api/settlements/{batchId}` | 查询留存的对账报告 |
| GET  | `/api/ledger/journal` | 查看全部会计分录 |
| GET  | `/api/ledger/health` | 借贷守恒检查 |

## 状态流转

```
CREATED --authorize--> AUTHORIZED --capture(部分)--> PARTIALLY_CAPTURED --capture(剩余)--> CAPTURED
AUTHORIZED / PARTIALLY_CAPTURED 可继续 capture，直到累计请款 = 订单金额。
CAPTURED / PARTIALLY_CAPTURED --refund--> PARTIALLY_REFUNDED --refund(退清)--> REFUNDED
```

合法路径之外的请求一律被拒绝，且不会留下任何状态或账务变更。

## 拒绝原因（HTTP 响应体 `reason` 字段）

| reason | HTTP | 含义 |
|---|---|---|
| `PAYMENT_NOT_FOUND` | 404 | 支付单不存在 |
| `ILLEGAL_STATE_TRANSITION` | 422 | 非法状态流转（如未授权就请款、重复授权） |
| `CAPTURE_EXCEEDS_AUTHORIZED` | 422 | 累计请款超过授权金额 |
| `REFUND_EXCEEDS_CAPTURED` | 422 | 累计退款超过已请款金额 |
| `INVALID_AMOUNT` | 422 | 金额非正数 |
| `IDEMPOTENCY_KEY_CONFLICT` | 409 | 同一幂等键携带了不同参数 |
| `BATCH_ALREADY_COMPLETED` | 409 | 批次已成功执行过 |
| `BATCH_IN_PROGRESS` | 409 | 同一批次正在并发执行，本次触发被拒绝 |
| `SETTLEMENT_BALANCE_NEGATIVE` | 422 | 结算会导致商户待结算余额为负 |

## 幂等规则

- 每个写操作都带 `idempotencyKey`。首次请求执行并记录「参数指纹 + 受理结果」。
- 同键同参数重放：直接返回首次的受理结果，不重复执行、不重复入账。
- 同键不同参数：拒绝并返回 `IDEMPOTENCY_KEY_CONFLICT`。

## 记账规则（复式记账，每笔资金变动一条平衡分录）

账户：`CHANNEL_CLEARING`（渠道在途，借方）、`MERCHANT_PAYABLE`（商户待结算，贷方，不允许为负）、`MERCHANT_CASH`（已结算出款，借方）、`FEE_REVENUE`（手续费收入，贷方）。手续费为请款额的 1%（最低 1 分），在请款时计提。

| 业务 | 分录 |
|---|---|
| 请款 gross / 手续费 fee | Dr `CHANNEL_CLEARING` gross；Cr `MERCHANT_PAYABLE` (gross-fee)；Cr `FEE_REVENUE` fee |
| 退款 amount | Dr `MERCHANT_PAYABLE` amount；Cr `CHANNEL_CLEARING` amount |
| 批次结算 amount | Dr `MERCHANT_PAYABLE` amount；Cr `MERCHANT_CASH` amount |

不变量：每条分录借贷相等；任意操作序列后全部账户的（借-贷）签名余额之和为 0；商户待结算余额不为负。所有校验先于记账执行，被拒绝的操作不会留下半截账。

## 对账规则

- 渠道对账文件由本地模拟器 `ChannelFileSimulator` 生成（可注入篡改行/幻影行/丢行来模拟渠道差异），不访问任何外部服务。
- 按 `paymentId` 逐笔匹配内部流水（请款-退款的净额与状态）与渠道文件：
  - `AMOUNT_MISMATCH`：双方都有但金额不一致；
  - `MISSING_IN_CHANNEL`：我方单边（内部有、渠道无）；
  - `MISSING_IN_INTERNAL`：渠道单边（渠道有、内部无）；
  - `STATUS_MISMATCH`：金额一致但状态不一致。
- 每个 `batchId` 只能成功结算一次：重复触发返回 `BATCH_ALREADY_COMPLETED`；并发触发时只有一个生效，其余返回 `BATCH_IN_PROGRESS`。
- 对账结果（含全部差异明细）按批次留存，可通过 `GET /api/settlements/{batchId}` 反复查询，结论可复现。

## 测试覆盖

`src/test/java/.../tests/` 下共 18 个用例，跑 `mvn test` 时日志中以 `[CASE]`/`[PASS]` 标注业务场景与结果：

- `payment/PaymentFlowTest`：主流程、部分请款、多次退款、手续费、非法流转、超额请款/退款、非法金额、单不存在；
- `payment/IdempotencyTest`：同键同参重放返回首次结果且不重复入账；同键不同参拒绝；
- `payment/ConcurrencyTest`：并发请款不超授权；并发触发同一批次只有一个生效；
- `ledger/LedgerInvariantTest`：随机 200 步操作（含大量非法操作）后借贷守恒、商户余额不为负、被拒操作无半截账；
- `settlement/ReconciliationTest`：干净批次全匹配、金额不符、双方单边、状态不一致、批次重复执行拒绝、报告可复现。
