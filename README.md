# OTA Fleet Orchestrator（设备固件分批升级编排）

本地可运行、可测试的设备固件**分批升级编排**服务。基于 Spring Boot 3 / Java 21，
使用内存存储 + 单把公平锁实现并发安全，无需任何外部依赖即可启动与验证。

能力覆盖：

- 固件版本、升级批次的创建与**分批推进**、暂停 / 恢复；
- 设备注册、心跳、升级进度上报，对**重复 / 乱序上报做幂等处理**；
- **失败阈值判定、自动暂停与回滚**，回滚后设备与批次恢复到一致且可继续操作的状态；
- 并发成功 / 失败上报不会重复计数、重复推进、漏判或状态丢更新；
- 每个非法操作都返回**可区分的错误码（reason）**与人类可读说明；
- 每次状态变化都记录审计事件，可通过批次视图查看"关键输入 → 状态迁移 → 判定依据"。

---

## 1. 快速开始

```bash
# 构建并运行全部测试（17 个用例）
mvn test

# 启动服务（内存存储，重启即清空）
mvn spring-boot:run
# 服务默认监听 8080（application.properties 中为 server.port=0 时随机端口；
# 本地手动验证建议改为固定端口，见第 7 节）
```

### 端到端手动验证脚本

```bash
# 1) 创建固件
curl -s -X POST localhost:8080/api/firmwares -H 'Content-Type: application/json' \
  -d '{"version":"v2.0.0","checksum":"sha256:abc"}'

# 2) 注册 4 台设备
for i in 0 1 2 3; do
  curl -s -X POST localhost:8080/api/devices -H 'Content-Type: application/json' \
    -d "{\"deviceId\":\"dev$i\",\"currentFirmwareVersion\":\"v1.0.0\"}"
done

# 3) 创建两阶段批次（50%/50%），失败率阈值 0.5
curl -s -X POST localhost:8080/api/batches -H 'Content-Type: application/json' -d '{
  "name":"canary-batch",
  "firmwareId":"fw-1",
  "deviceIds":["dev0","dev1","dev2","dev3"],
  "stagePercents":[50,50],
  "failureRateLimit":0.5
}'

# 4) 启动：仅下发第 0 阶段（dev0、dev1）
curl -s -X POST localhost:8080/api/batches/batch-1/start

# 5) 设备上报进度（seq 单调递增）
curl -s -X POST localhost:8080/api/batches/batch-1/devices/dev0/reports \
  -H 'Content-Type: application/json' -d '{"seq":1,"status":"DOWNLOADING"}'
curl -s -X POST localhost:8080/api/batches/batch-1/devices/dev0/reports \
  -H 'Content-Type: application/json' -d '{"seq":2,"status":"SUCCESS"}'
curl -s -X POST localhost:8080/api/batches/batch-1/devices/dev1/reports \
  -H 'Content-Type: application/json' -d '{"seq":1,"status":"SUCCESS"}'
# 阶段 0 全部终态上报且未达失败阈值 -> 自动下发阶段 1

# 6) 暂停 / 恢复
curl -s -X POST localhost:8080/api/batches/batch-1/pause \
  -H 'Content-Type: application/json' -d '{"reason":"manual check"}'
curl -s -X POST localhost:8080/api/batches/batch-1/resume

# 7) 手动回滚
curl -s -X POST localhost:8080/api/batches/batch-1/rollback \
  -H 'Content-Type: application/json' -d '{"reason":"rollback decision"}'

# 8) 查询批次状态（含逐设备状态、阶段进度、审计事件）
curl -s 'localhost:8080/api/batches/batch-1?events=50'
```

---

## 2. 批次状态流转

```
DRAFT ──start──▶ ROLLING ──pause──▶ PAUSED ──resume──▶ ROLLING
                  │  ▲                │                    │
                  │  └──── resume ────┘                    │
                  │                                        │
                  │  当前阶段全部终态上报 + 失败率未达阈值    │
                  ├──────────────▶ 自动下发下一阶段          │
                  │  最后一阶段全部上报                     │
                  └──────────────▶ COMPLETED（终态）         │
                  │  失败率 >= 阈值（任意状态，含 PAUSED）    │
                  └──────────────▶ ROLLING_BACK ──▶ ROLLED_BACK（终态）
                                   ▲
                                   └─ 手动 rollback（DRAFT 之外、非终态时）
```

前置条件（不满足即拒绝，错误码见第 5 节）：

| 操作 | 允许的当前状态 | 其它前置条件 |
| --- | --- | --- |
| `start` | `DRAFT` | 阶段计划合法、设备均已注册 |
| `pause` | `ROLLING` | — |
| `resume` | `PAUSED` | 恢复后若当前阶段已收齐终态上报，会自动尝试推进 |
| `advance`（手动） | `ROLLING` | 当前阶段所有设备已终态上报；失败率未达阈值 |
| `rollback`（手动） | `ROLLING` / `PAUSED` | 已 `COMPLETED` / `ROLLED_BACK` / 回滚中均拒绝 |

**阶段推进规则**：当前阶段所有设备完成 `SUCCESS`/`FAILED` 终态上报后，若全量失败率
未达阈值，自动下发下一阶段；最后一个阶段收齐且未达阈值则 `COMPLETED`。
阶段计划按整数百分比给出，**各百分比之和必须恰好为 100**；设备按 ID 排序后
确定性切分到阶段（余数并入最后阶段），因此同一输入的阶段归属永远一致。

---

## 3. 设备状态机与上报幂等 / 乱序规则

设备在单次批次中的状态：

```
IDLE ──下发──▶ PENDING ──▶ DOWNLOADING ──▶ INSTALLING ──▶ SUCCESS（终态）
                                                  └──────▶ FAILED  （终态）
回滚期间：非 IDLE 状态 ──▶ ROLLING_BACK ──▶ IDLE
（回滚本身失败会记录为 ROLLBACK_FAILED，本次实现中回滚为内存内同步操作）
```

每个上报携带设备侧单调递增的 `seq`，服务端记录已接受的最大序号：

1. **过期请求**：`seq < 已接受最大 seq` → `409 STALE_REQUEST`，拒绝，状态不变；
2. **重复终态上报**：设备已终态，且 `seq` 与终态结果都相同
   → HTTP 200，响应 `duplicate=true`、`stateChanged=false`，**幂等返回，不重复计数、
   不重复触发推进 / 回滚**；
3. **冲突重放**：相同 `seq` 但内容不同，或已终态却上报其它结果
   → `409 ILLEGAL_STATE_TRANSITION`，防止状态被意外回退；
4. **乱序状态**：只允许沿 `PENDING → DOWNLOADING → INSTALLING → SUCCESS/FAILED`
   前进，任何倒退迁移 → `409 ILLEGAL_STATE_TRANSITION`；
5. **阶段未激活**：非当前阶段的设备上报 → `409 STAGE_MISMATCH`；
   尚未下发就上报 → `409 STAGE_NOT_ACTIVE`；
6. **批次暂停**：暂停期间非终态进度（DOWNLOADING/INSTALLING）→ `409 BATCH_PAUSED`；
   **终态结果（SUCCESS/FAILED）仍被接收并计数**，保证阈值不漏判；暂停期间不自动推进，
   统一在 `resume` 后推进；
7. **批次回滚中 / 已终态**：上报 → `ROLLBACK_IN_PROGRESS` / `BATCH_FINISHED`。

心跳天然幂等：重复心跳只刷新 `lastHeartbeatAt`。

---

## 4. 并发规则

- 所有状态变更（注册、批次生命周期、上报判定）都在 `InMemoryStore` 的**同一把公平
  `ReentrantLock`** 临界区内完成；
- 每次终态上报的"**计数 → 失败率判定 → 自动推进 / 自动暂停 + 回滚**"是一个原子临界区：
  - 阶段下发与推进各只会发生一次（有 `dispatched` 标记与状态前置条件保护）；
  - 阈值判定基于临界区内一致的成功 / 失败计数，不会漏判或重复触发回滚；
  - 临界期之后到达的并发请求会以 `BATCH_FINISHED` / `ROLLBACK_IN_PROGRESS` 被拒绝，
    已接受的请求与最终计数严格一一对应（测试中以
    `successCount + failureCount == 被接受请求数` 校验）。

失败率 = `失败数 / 已终态上报数`（全量口径，非单阶段口径），阈值使用
**`failureRate >= failureRateLimit` 即触发**（包含相等）。阈值范围 `(0,1)`。

---

## 5. 回滚策略

触发方式：

- **自动**：任意一次终态上报后失败率达到阈值，无论批次处于 `ROLLING` 还是 `PAUSED`，
  在同一临界区内完成"暂停推进 → 回滚"；
- **手动**：`POST /api/batches/{id}/rollback`（仅 `ROLLING`/`PAUSED` 可触发）。

一致性保证：

1. 批次进入 `ROLLING_BACK`，期间拒绝一切下发与普通上报；
2. **回滚触发时刻对 `reported/success/failure/failureRate` 做快照**，随后设备状态重置，
   快照随批次视图返回并写入审计事件，使"为什么回滚"始终可解释；
3. 逐设备执行回滚：记录 `ROLLBACK_STARTED → ROLLBACK_SUCCEEDED`；
   - 曾成功升级到目标版本的设备：固件版本恢复为**下发时记录的升级前版本**；
   - 失败 / 未完成设备：版本本就未变，仅状态归位；
4. 所有设备最终统一为 `IDLE`，批次进入终态 `ROLLED_BACK`；
5. 回滚后的设备**可以加入新批次继续升级**（测试 `manualRollbackFromPause_thenDevicesContinueInNewBatch` 覆盖）；
6. `ROLLED_BACK` / `COMPLETED` 均为终态：重复回滚、重复推进、上报都会被拒绝并给出
   `BATCH_FINISHED` 等可区分原因。

---

## 6. HTTP 接口与错误码

| 方法 & 路径 | 说明 |
| --- | --- |
| `POST /api/firmwares` | 创建固件版本（重复版本 → `FIRMWARE_ALREADY_EXISTS`） |
| `GET /api/firmwares` / `GET /api/firmwares/{id}` | 查询固件 |
| `DELETE /api/firmwares/{id}` | 删除（被批次引用 → `FIRMWARE_IN_USE`） |
| `POST /api/devices` | 注册设备（重复 ID → `DEVICE_ALREADY_REGISTERED`） |
| `POST /api/devices/{id}/heartbeat` | 心跳（幂等） |
| `GET /api/devices` / `GET /api/devices/{id}` | 查询设备 |
| `POST /api/batches` | 创建批次（校验失败 → `EMPTY_DEVICE_LIST` / `INVALID_STAGE_PLAN` 等） |
| `POST /api/batches/{id}/start|pause|resume|advance|rollback` | 生命周期操作 |
| `POST /api/batches/{id}/devices/{deviceId}/reports` | 进度上报 |
| `GET /api/batches` / `GET /api/batches/{id}?events=N` | 批次状态 + 审计事件 |

错误响应统一为：

```json
{ "time": "...", "reason": "STALE_REQUEST", "message": "设备 x 上报 seq=4 小于已接受 seq=5，过期请求被拒绝" }
```

全部 `reason` 取值见
`src/main/java/.../error/RejectReason.java`，每个枚举值映射确定的 HTTP 状态码
（400 / 404 / 409），调用方可据此区分处理。

---

## 7. 本地验证方法（测试）

```bash
mvn test                      # 全量
mvn test -Dtest=ConcurrentReportTest   # 仅并发用例（建议重复运行验证稳定性）
```

测试位于 `src/test/java/.../tests/`：

| 测试类 | 覆盖场景 |
| --- | --- |
| `NormalRolloutFlowTest` | 三阶段正常发布、阶段自动推进、中间进度不计入终态计数、版本升级、审计事件 |
| `ConcurrentReportTest` | 并发成功不重复下发 / 推进；并发成功+失败混合下阈值只判定一次、回滚只触发一次、计数与接受请求数严格一致、设备最终全部 IDLE |
| `IdempotencyAndRejectTest` | 重复终态幂等、过期 seq、冲突重放、终态不可逆（防回退）、阶段不匹配、暂停期规则、全部生命周期非法操作的**错误原因可区分性** |
| `RollbackTest` | 阈值边界（`>=` 语义）、自动回滚恢复版本与状态、暂停中终态仍计数并触发回滚、手动回滚后设备在新批次继续升级、终态拒绝重复操作 |
| `HeartbeatAndValidationTest` | 心跳幂等、未注册设备、缺字段 / 非法枚举 / 非法 JSON 校验 |

测试日志会通过 `OtaTestClient` 打印**关键请求输入**（`>>> POST ... body=...`）、
**响应与状态**（`<<< 状态码 JSON`），服务端则打印 `REPORT_INPUT` /
`REPORT_TERMINAL ... 判定依据` / `AUTO_ADVANCE` / `ROLLBACK_TRIGGER ... 判定快照`
等日志，完整呈现状态迁移与判定依据。

> 手动联调时把 `src/main/resources/application.properties` 的
> `server.port=0`（随机端口）改为 `server.port=8080` 即可。

---

## 8. 关键类索引

| 类 | 职责 |
| --- | --- |
| `domain/Batch` | 批次状态机、阶段切分、计数与阈值判定、回滚快照 |
| `domain/DeviceAssignment` | 设备在批次中的状态、单调 `seq`、终态去重、回滚事件 |
| `service/ReportService` | 上报幂等 / 乱序 / 阶段规则的全部判定 |
| `service/BatchService` | 批次生命周期编排、自动推进 / 自动回滚临界区逻辑 |
| `store/InMemoryStore` | 内存表 + 全局公平锁（`withLock`） |
| `error/RejectReason` | 可区分错误码及 HTTP 状态映射 |
