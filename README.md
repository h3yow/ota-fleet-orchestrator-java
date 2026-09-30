# OTA 设备固件分批升级编排服务

本地可运行、可测试的设备固件分批升级编排能力。基于 Spring Boot 3.4 + Java 21，使用**内存存储 +
显式锁**实现强一致的状态机，保证设备在**并发上报、重复请求、失败回滚**下得到一致且可解释的状态。
无需数据库，启动即用。

## 1. 构建与本地验证

```bash
# 编译
mvn compile

# 运行全部测试（单元 + 并发 + Web 端到端）
mvn test

# 启动服务（默认随机端口，见 application.properties: server.port=0）
mvn spring-boot:run
```

测试会在控制台打印关键请求输入、状态迁移与判定依据，例如：

```
[progress-in]      deviceId=dev-1, reportedStatus=INSTALLING, seq=5, requestId=r-5
[ordering]         seq=5 < lastSeq=10 被拒绝，原因 STALE_SEQUENCE
[progress-apply]   deviceId=dev-1, transition=DOWNLOADING->INSTALLING, success=0, failed=0
[batch-auto-pause] failed=3, dispatched=4, failureRate=75.0% > threshold=50% -> PAUSED(FAILURE_THRESHOLD)
[batch-rollback]   rolledBackDevices=4, batchFinalStatus=ROLLED_BACK
```

## 2. HTTP API 速览

| 方法 & 路径 | 说明 |
| --- | --- |
| `POST /api/firmwares` `{version}` | 创建固件（DRAFT） |
| `POST /api/firmwares/{id}/release` | 发布固件（DRAFT -> RELEASED） |
| `GET  /api/firmwares` | 固件列表 |
| `POST /api/devices` `{deviceId,currentFirmwareVersion}` | 注册设备 |
| `POST /api/devices/{id}/heartbeat` | 设备心跳 |
| `GET  /api/devices` | 设备列表 |
| `POST /api/batches` `{firmwareId,deviceIds?,batchSize,failureThresholdPercent}` | 创建批次（PENDING） |
| `POST /api/batches/{id}/advance` | 分批推进（下发下一批 / 收尾完成） |
| `POST /api/batches/{id}/pause` | 人工暂停 |
| `POST /api/batches/{id}/resume` | 恢复（人工或自动暂停均可） |
| `POST /api/batches/{id}/rollback` | 回滚 |
| `GET  /api/batches/{id}` / `/upgrades` | 批次与设备升级明细 |
| `POST /api/devices/{deviceId}/progress` `{batchId,status,seq,requestId}` | 设备进度上报 |

错误响应统一为：

```json
{ "timestamp": "...", "code": "STALE_SEQUENCE", "message": "...", "rejected": true }
```

HTTP 状态码：参数/重复类 `400`，资源不存在 `404`，前置条件不满足（状态机、过期、在途、终态等）`409`。

## 3. 状态流转

### 3.1 固件

```
DRAFT ──release──> RELEASED（终态）
```

- 版本号必须非空且唯一；只有 `RELEASED` 固件可用于建批次；重复发布被拒绝。

### 3.2 升级批次

```
PENDING ──advance(首次下发)──> ACTIVE ──pause──> PAUSED ──resume──> ACTIVE
                                 │                  │
                                 └────rollback──────┴──> ROLLED_BACK（终态）
ACTIVE ──全部下发且全部终态，再 advance──> COMPLETED（终态）
```

- **分批推进门禁**：上一批仍有在途设备（`PENDING/DOWNLOADING/INSTALLING`）时，`advance` 被拒绝
  （`BATCH_HAS_INFLIGHT_DEVICES`），杜绝跨批重复下发。
- 合格设备快照在创建批次时固化（当前版本 ≠ 目标版本，按 deviceId 排序），每次 `advance`
  从游标切 `batchSize` 台；已全部下发且无在途时，再 `advance` 收尾为 `COMPLETED`。
- 暂停原因分 `MANUAL` 与 `FAILURE_THRESHOLD`，在批次上可查。

### 3.3 单设备升级状态机

```
PENDING -> DOWNLOADING -> INSTALLING -> SUCCESS
    │           │              │
    └───────────┴──────────────┴──> FAILED（任意非终态可上报失败）
                批次 rollback: 任意已下发状态 ──> ROLLED_BACK
```

只允许正向单步迁移（失败可在任意非终态直接上报）；`SUCCESS/FAILED/ROLLED_BACK` 为终态。
任何回退（如已 SUCCESS 再报 FAILED）都以 `ILLEGAL_STATUS_TRANSITION` 拒绝，已推进的状态不会被回退。

## 4. 幂等与并发规则

进度上报请求字段：`{batchId, status, seq, requestId}`。

1. **幂等键 `requestId`**：同一 `(deviceId, requestId)` 的重复请求，直接回放首次处理结果
   （响应 `duplicate=true`），**不重复计数、不重复推进、不重复触发自动暂停**。
   幂等检查与处理在同一把批次+设备锁内原子完成，因此并发的相同请求也只有一个生效。
2. **乱序判定 `seq`**：`seq` 必须严格大于该设备已接受的最大序号，否则以 `STALE_SEQUENCE` 拒绝。
3. **良性重传**：相同状态的重复上报（不同 requestId，例如网络重发 DOWNLOADING）返回当前状态，
   不计数、不报错（`decision=BENIGN_SAME_STATE`）。
4. **同 requestId 跨批次**：以 `DEVICE_NOT_IN_BATCH` 拒绝，防止幂等键串用。
5. **并发控制**：
   - 每个批次一把 `ReentrantLock`，所有"读批次状态 -> 改设备状态 -> 改成败计数 -> 判定阈值 ->
     落幂等记录"都在锁内完成，读-改-写原子，杜绝重复推进、漏判和计数丢更新；
   - 每台设备一把锁保护设备字段；锁顺序固定为 **批次锁 -> 设备锁**，避免死锁；
   - 不同批次互不阻塞。
6. **失败阈值自动暂停**：每当收到被接受的新失败上报，计算
   `失败率 = failedCount / dispatchedCount * 100%`；当失败率**严格大于** `failureThresholdPercent`
   且批次为 ACTIVE 时自动切到 `PAUSED(FAILURE_THRESHOLD)`。该判定在锁内完成，
   高并发下自动暂停**恰好触发一次**（`triggeredAutoPause` 只有一个请求为 true）。
7. 暂停期间批次不再下发新设备，但**在途设备仍可继续上报**；`resume` 后可继续推进。

## 5. 拒绝原因（可区分）

| code | 触发场景 |
| --- | --- |
| `INVALID_REQUEST` | 必填参数缺失/非法（状态枚举、batchSize、阈值范围等） |
| `FIRMWARE_VERSION_DUPLICATED` / `FIRMWARE_ALREADY_RELEASED` / `FIRMWARE_NOT_RELEASED` | 固件版本重复 / 重复发布 / 用未发布固件建批次 |
| `DEVICE_ALREADY_REGISTERED` | 设备重复注册 |
| `FIRMWARE_NOT_FOUND` / `DEVICE_NOT_FOUND` / `BATCH_NOT_FOUND` | 资源不存在 |
| `BATCH_NO_ELIGIBLE_DEVICE` | 没有当前版本可升级的设备 |
| `BATCH_NOT_ACTIVE` / `BATCH_NOT_PAUSED` / `BATCH_NOT_PAUSABLE` | 推进/恢复/暂停的状态前置条件不满足 |
| `BATCH_HAS_INFLIGHT_DEVICES` | 上一批仍有在途设备，不能推进下一批 |
| `BATCH_ALREADY_TERMINAL` / `BATCH_NOT_ROLLBACKABLE` | 终态操作 / 非可回滚状态回滚 |
| `DEVICE_NOT_IN_BATCH` | 设备未被该批次下发，或 requestId 已用于其它批次 |
| `REQUEST_ALREADY_PROCESSED` | 幂等命中（业务侧回放成功，HTTP 200 + duplicate=true） |
| `STALE_SEQUENCE` | seq 小于已处理最大序号的过期请求 |
| `ILLEGAL_STATUS_TRANSITION` | 设备状态机非法跳转/状态倒退 |
| `REPORT_REJECTED_TERMINAL` | 批次/设备已终态（COMPLETED/ROLLED_BACK）后仍上报 |

## 6. 回滚策略

- 只有 `ACTIVE/PAUSED` 的批次可回滚；`COMPLETED/ROLLED_BACK` 拒绝回滚。
- 回滚对批次内**所有已下发设备**统一处理：
  - 设备版本恢复为该设备升级记录中的**升级前版本 `fromVersion`**（无论成功、失败还是在途）；
  - 设备升级记录置 `ROLLED_BACK`，清空设备的 `targetBatchId`；
  - 批次置 `ROLLED_BACK`（终态），此后上报被拒、不能再推进/暂停/恢复。
- 回滚后设备与批次状态一致、设备可被**新的批次重新选中并下发**（旧批次记录保留可审计）。
- 回滚同样在批次锁内逐设备完成，与并发上报互斥，不会出现"回滚与上报交错"的中间态。

## 7. 测试覆盖

`src/test/java/.../tests`：

- `FirmwareLifecycleTest`：固件创建/发布/重复发布/空版本的错误区分。
- `NormalRolloutTest`：分批发布全流程、批次间在途门禁、最终 COMPLETED 与版本更新。
- `IdempotencyAndOrderingTest`：重复 requestId 幂等、seq 过期、非法跳转、成功后失败倒退、未下发设备。
- `FailureThresholdTest`：失败率 50% 不暂停、75% 自动暂停、暂停期在途可上报、恢复后完成。
- `RollbackTest`：成功/失败/在途混合下回滚后版本、记录、批次的一致性，终态拒绝，设备可复用。
- `ConcurrentReportTest`：100 台并发成功计数精确、40×5 并发重复请求仅计数一次、
  60 台并发失败自动暂停恰好一次（无丢更新）。
- `WebApiIntegrationTest`：真实 HTTP 端到端，验证 200/400/404/409 与各 code。
