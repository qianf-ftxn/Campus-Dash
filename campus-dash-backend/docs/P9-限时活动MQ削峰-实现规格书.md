# P9：限时活动与 RocketMQ 削峰——实现规格书

> 文档性质：可执行需求与工程规格（Implementation Specification）  
> 目标读者：负责实现、测试或审查本功能的开发者与 Agent  
> 编写日期：2026-09-22  
> 当前状态：核心实现、真实RocketMQ验收、故障恢复验证与3000抢500实压均已完成  
> 权威级别：如本文件与 `P9-限时活动MQ削峰设计.md` 的开放讨论存在冲突，以本文件为准。

---

## 0. 使用说明

本文件的目标不是介绍概念，而是让不了解此前讨论的开发者或 Agent 能够直接完成改造。

截至2026-09-22，Schema、Domain、JDBC、Lua、MQ入口/消费者、查询接口、前端轮询、恢复任务、
跑腿容量控制、种子与校验脚本已经实现。后续Agent应先运行 `migrate-p9.sql` 和真实MQ联调，重点补齐
后续改动应沿用现有活动模型；压测证据见`bench/reports/report-P9-20260922.md`。

实施者必须遵守：

1. 先完整阅读本文件，再修改代码；
2. 保留普通任务发布、既有抢单、资金、超时流转、缓存和鉴权行为；
3. 新功能必须遵守现有洋葱依赖方向；
4. 不把 Redis、RocketMQ 或 JdbcTemplate 类型泄漏到 `dash-domain`；
5. 不通过删除测试、放宽数据库约束或吞掉异常让构建变绿；
6. 所有重复消息、重复请求和补偿操作必须可重入；
7. 完成后更新本文件的验收清单、根 README 和设计演进记录；
8. 第一版严格按本规格实现，不擅自加入商品 SKU、拼单、路线规划等额外范围。

### 0.1 相关文档

- `docs/P9-限时活动MQ削峰设计.md`：讨论过程、候选方案和完整背景；
- `docs/P9-限时活动MQ削峰-学习手册.md`：面向学习者的原理说明；
- `docs/架构设计与技术选型.md`：项目总体架构；
- `docs/设计演进记录.md`：已有阶段和踩坑记录；
- `docker/init.sql`：当前数据库真相源；
- `docker/init-mq.sh`：当前 RocketMQ Topic/消费组初始化入口。

---

## 1. 需求概述

### 1.1 业务场景

新增“限时跑腿活动”：

```text
活动名称：午餐限时代购（名称可配置）
活动窗口：由 start_at 和 end_at 决定
参与用户：同一校区约 3000 人在短时间内请求
活动容量：500 个配送服务订单名额
参与限制：同一用户在同一活动只有一次参与机会
成功结果：异步生成一张 slotTotal=1、type=BUY 的 Errand
跑腿分配：任务生成后进入现有任务广场，由跑腿员抢单
跑腿容量：一个跑腿员可接多单，但进行中任务不得超过 K
```

500 表示平台接受的配送服务订单数，不表示 500 名跑腿员，也不表示第三方商家的真实商品库存。

### 1.2 核心目标

1. 增加 RocketMQ 普通消息削峰的真实业务场景；
2. 3000 个瞬时请求不直接同时执行资金和 Errand 创建事务；
3. Redis Lua 快速、原子地完成活动名额、用户和 requestId 裁决；
4. MySQL CAS、唯一索引和 CHECK 约束保证最终不超卖；
5. MQ 重复投递不能重复扣款、重复托管或重复创建 Errand；
6. 暂时性故障可重试，确定性失败可补偿；
7. 同一 Errand 仍只能被一个跑腿员抢中；
8. 同一跑腿员并发抢多张 Errand 时不得突破容量 K；
9. 提供同步与异步压测对比及数据不变式校验。

### 1.3 不在第一版范围内

- 管理第三方商家商品库存；
- 多 SKU、多商品数量扣减；
- 活动管理后台；
- 自动拼单、路线优化、批次配送；
- 严格毫秒级 FIFO；
- 显示实时排队名次；
- 自动根据在线跑腿员计算 quota；
- 将普通 `/api/errands` 改成异步；
- 将跑腿抢单改成 MQ 异步确认；
- 引入新的中间件。

---

## 2. 已冻结的架构决策

### 2.1 普通发布保持同步

现有接口保持不变：

```text
POST /api/errands
```

成功返回仍表示资金托管、Errand 创建和 Redis 名额初始化均已完成。

### 2.2 活动发布使用新入口和异步语义

新增：

```text
POST /api/rush-campaigns/{campaignId}/orders
```

第一版固定采用：

```text
API先发送普通RocketMQ消息
→ 返回PROCESSING
→ Worker消费者执行Redis Lua裁决
→ 通过后执行MySQL事务创建订单
```

选择 MQ-first 的原因：避免第一版出现“Lua 已扣名额但普通消息尚未发出”的 Redis/MQ 双写丢失窗口。

### 2.3 所有请求先入 MQ

3000 个请求均允许先进入 Topic。消费者用有限线程处理：

- Redis Lua 拒绝售罄、重复用户和重复 requestId；
- 只有预占成功请求进入资金与 Errand 重事务；
- 失败结果也可以由消费者受控地写入 `rush_request`，不会形成同步数据库尖峰。

### 2.4 第一版不承诺严格 FIFO

只保证：

- 服务端活动窗口校验；
- 活动容量不超卖；
- 同用户只有一次参与机会；
- 消费者最终给出明确结果。

多消费者下的 Lua 执行先后顺序可能与客户端网络到达时间存在微小差异。文案不得宣称“绝对先到先得”。

### 2.5 活动创建与跑腿抢单分离

```text
活动创建链路：RocketMQ削峰 + 活动Lua + MySQL事务
跑腿抢单链路：现有Sentinel + 抢单Lua + Errand CAS
```

活动成功只负责生成 `PUBLISHED` Errand，不自动分配跑腿员。

### 2.6 MQ不可用时不得同步降级创建

普通超时消息可以退化为数据库扫描，但限时活动削峰不能在 MQ 故障时突然把请求同步打入数据库。

因此：

```text
dash.mq.enabled=false 或 rush Topic不可用
→ 活动提交接口返回 RUSH_QUEUE_UNAVAILABLE
→ 不执行同步资金/Errand创建
```

---

## 3. 端到端流程

### 3.1 提交阶段（在线服务）

```text
1. AuthInterceptor解析当前用户
2. 校验X-Request-Id存在且格式合法
3. 查询活动基本信息
4. 校验campusId、status、startAt、endAt
5. 构造RushOrderMessage，acceptedAt使用服务端时钟
6. 同步发送普通RocketMQ消息并等待Broker确认
7. 可尽力写Redis请求结果缓存为QUEUED（失败不改变消息事实）
8. 返回HTTP 202和PROCESSING
```

在线服务不得执行：

- 活动库存扣减；
- 用户余额扣减；
- 资金流水；
- escrow_order；
- Errand 创建。

### 3.2 消费阶段（Worker）

```text
1. 解析schemaVersion=1消息
2. 校验必填字段和服务端acceptedAt
3. 查询rush_request终态；存在则幂等返回SUCCESS
4. 执行rush_reserve.lua
5. SOLD_OUT/DUPLICATE/INVALID：持久化失败终态，ACK
6. RESERVED/RESERVED_BY_SAME_REQUEST：执行数据库事务
7. 事务内完成活动CAS、请求记录、资金托管、Errand创建、状态日志
8. 提交成功后幂等初始化Errand抢单Redis Key
9. 更新请求结果缓存
10. 发送/广播活动结果事件给在线app进程
11. ACK
```

### 3.3 活动任务进入既有履约流程

```text
Errand PUBLISHED
→ 跑腿员同步抢单
→ LOCKED
→ ACCEPTED
→ PICKED_UP
→ DELIVERED
→ SETTLED/CLOSED
```

现有超时流转、自动结算、争议和资金事件继续复用。

---

## 4. API 规格

### 4.1 活动列表

```http
GET /api/rush-campaigns?campusId=1
Authorization: Bearer <token>
```

响应数据项：

```json
{
  "id": "90001",
  "campusId": "1",
  "name": "午餐限时代购",
  "startAt": "2026-09-22T11:30:00+08:00",
  "endAt": "2026-09-22T11:35:00+08:00",
  "quotaTotal": 500,
  "soldCount": 132,
  "status": "ACTIVE",
  "canSubmit": true
}
```

ID必须通过全局 Jackson 配置序列化为字符串。

### 4.2 活动详情

```http
GET /api/rush-campaigns/{campaignId}
```

不存在返回业务码 `RUSH_CAMPAIGN_NOT_FOUND`。

### 4.3 提交活动订单

```http
POST /api/rush-campaigns/{campaignId}/orders
Authorization: Bearer <token>
X-Request-Id: <8-64字符幂等号>
Content-Type: application/json
```

请求体：

```json
{
  "title": "帮我购买午餐活动套餐",
  "rewardCents": 1500
}
```

校验规则：

- `X-Request-Id` 必填，8～64字符；
- 仅允许字母、数字、`-`、`_`、`:`；
- `title` 非空，去空格后1～64字符；
- `rewardCents` 大于0；
- `campusId` 来自活动，不接受客户端覆盖；
- `publisherId` 来自 `CurrentUser`；
- `ErrandType` 固定为 `BUY`；
- `slotTotal` 固定为1。

成功受理：

```http
HTTP/1.1 202 Accepted
```

```json
{
  "code": "OK",
  "message": "成功",
  "data": {
    "requestId": "rush-90001-1001-0001",
    "status": "PROCESSING"
  }
}
```

如果同一个 `requestId` 再次提交：

- 已有终态时直接返回终态；
- 仍在队列时返回 `PROCESSING`；
- 不生成新的 requestId；
- 允许重复消息，消费端必须幂等。

### 4.4 查询请求结果

```http
GET /api/rush-requests/{requestId}
Authorization: Bearer <token>
```

必须校验 request 的 `userId == CurrentUser.get()`。

处理结果：

```json
{
  "requestId": "rush-90001-1001-0001",
  "status": "SUCCESS",
  "errandId": "7312345678901234567",
  "errorCode": ""
}
```

处理中：

```json
{
  "requestId": "rush-90001-1001-0001",
  "status": "PROCESSING",
  "errandId": "",
  "errorCode": ""
}
```

### 4.5 第一版不提供活动管理API

活动数据通过测试fixture和 `bench/scripts/seed_rush.sql` 创建。管理后台不属于第一版。

---

## 5. 新增错误码和状态

### 5.1 `ErrorCode` 新增

在 `dash-shared/.../ErrorCode.java` 增加：

```text
INVALID_REQUEST_ID          请求幂等号不合法
RUSH_CAMPAIGN_NOT_FOUND     活动不存在
RUSH_NOT_STARTED            活动尚未开始
RUSH_ENDED                  活动已经结束
RUSH_DISABLED               活动已停用
RUSH_SOLD_OUT               活动名额已满
RUSH_DUPLICATE_USER         你已参与该活动
RUSH_REQUEST_NOT_FOUND      活动请求不存在
RUSH_REQUEST_FORBIDDEN      无权查看该活动请求
RUSH_QUEUE_UNAVAILABLE      活动队列暂不可用
RUSH_PROCESS_CONFLICT       活动订单处理冲突
RUNNER_CAPACITY_FULL        跑腿员在途任务数已达上限
```

不要用 `INTERNAL_ERROR` 代替可预期业务结果。

### 5.2 `RushRequestStatus`

领域枚举固定为：

```text
QUEUED
RESERVED
SUCCESS
FAILED_SOLD_OUT
FAILED_DUPLICATE
FAILED_INSUFFICIENT_BALANCE
FAILED_INVALID
DEAD
```

对外可将所有 `FAILED_*` 映射为 `FAILED`，同时返回明确 `errorCode`。

### 5.3 `RushCampaignStatus`

固定为：

```text
ENABLED
DISABLED
```

`NOT_STARTED/ACTIVE/ENDED` 是由 `status + startAt + endAt + 当前服务端时间` 推导的展示状态，不额外依赖定时任务修改数据库。

---

## 6. 数据库变更

### 6.1 修改位置

必须同时更新：

- `docker/init.sql`：全新环境；
- 新增 `docker/migrate-p9.sql`：已有数据库增量迁移；
- `bench/scripts/cleanup.sh`：按测试轮次清理活动数据；
- 必要时扩展 `bench_run_item.entity_type` 使用约定，不增加无必要外键。

### 6.2 `rush_campaign`

```sql
CREATE TABLE IF NOT EXISTS rush_campaign (
  id           BIGINT       NOT NULL,
  campus_id    BIGINT       NOT NULL,
  name         VARCHAR(128) NOT NULL,
  start_at     DATETIME(3)  NOT NULL,
  end_at       DATETIME(3)  NOT NULL,
  quota_total  INT          NOT NULL,
  sold_count   INT          NOT NULL DEFAULT 0,
  status       VARCHAR(16)  NOT NULL COMMENT 'ENABLED/DISABLED',
  version      BIGINT       NOT NULL DEFAULT 0,
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                              ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_campus_time (campus_id, status, start_at),
  CONSTRAINT ck_rush_quota
    CHECK (quota_total > 0 AND sold_count >= 0 AND sold_count <= quota_total),
  CONSTRAINT ck_rush_window CHECK (end_at > start_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 6.3 `rush_request`

```sql
CREATE TABLE IF NOT EXISTS rush_request (
  request_id    VARCHAR(64)  NOT NULL,
  campaign_id   BIGINT       NOT NULL,
  campus_id     BIGINT       NOT NULL,
  user_id       BIGINT       NOT NULL,
  status        VARCHAR(40)  NOT NULL,
  title         VARCHAR(64)  NOT NULL,
  reward_amount BIGINT       NOT NULL,
  errand_id     BIGINT       NULL,
  error_code    VARCHAR(48)  NULL,
  accepted_at   DATETIME(3)  NOT NULL,
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                              ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (request_id),
  UNIQUE KEY uk_rush_campaign_user (campaign_id, user_id),
  UNIQUE KEY uk_rush_errand (errand_id),
  KEY idx_rush_user_time (user_id, created_at),
  KEY idx_rush_status_time (status, updated_at),
  CONSTRAINT ck_rush_reward CHECK (reward_amount > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

第一版的 `uk_rush_campaign_user` 表示每人每活动一次参与机会，而不仅是一次成功机会。

### 6.4 `errand` 增加来源字段

```sql
ALTER TABLE errand
  ADD COLUMN source_type VARCHAR(24) NULL COMMENT 'NORMAL/RUSH_CAMPAIGN',
  ADD COLUMN source_request_id VARCHAR(64) NULL,
  ADD UNIQUE KEY uk_errand_source_request (source_request_id);
```

普通发布写：

```text
source_type = NORMAL
source_request_id = NULL
```

活动发布写：

```text
source_type = RUSH_CAMPAIGN
source_request_id = requestId
```

MySQL唯一索引允许多个NULL，不影响普通任务。

### 6.5 `runner_workload`

```sql
CREATE TABLE IF NOT EXISTS runner_workload (
  runner_id      BIGINT      NOT NULL,
  ongoing_count  INT         NOT NULL DEFAULT 0,
  max_ongoing    INT         NOT NULL DEFAULT 5,
  version        BIGINT      NOT NULL DEFAULT 0,
  updated_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                               ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (runner_id),
  CONSTRAINT ck_runner_workload
    CHECK (ongoing_count >= 0 AND ongoing_count <= max_ongoing)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 6.6 `runner_capacity_assignment`

该表解决重复释放和流转换人：

```sql
CREATE TABLE IF NOT EXISTS runner_capacity_assignment (
  errand_id    BIGINT      NOT NULL,
  round        INT         NOT NULL,
  runner_id    BIGINT      NOT NULL,
  status       VARCHAR(16) NOT NULL COMMENT 'OCCUPIED/RELEASED',
  released_by  VARCHAR(32) NULL,
  created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  released_at  DATETIME(3) NULL,
  PRIMARY KEY (errand_id, round),
  KEY idx_capacity_runner_status (runner_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

占用规则：同一个 `errandId + round` 只能有一条容量分配。释放时先 CAS `OCCUPIED -> RELEASED`，只有成功更新的调用才允许 `ongoing_count - 1`。

---

## 7. Redis Lua规格

### 7.1 新增文件

```text
dash-infrastructure/src/main/resources/lua/rush_reserve.lua
dash-infrastructure/src/main/resources/lua/rush_rollback.lua
```

在 `RedisLuaConfig` 中装配命名 Bean：

```text
rushReserveScript
rushRollbackScript
```

### 7.2 Key约定

所有活动Key必须使用同一个Redis Cluster hash tag：

```text
rush:{campaignId}:stock
rush:{campaignId}:users
rush:{campaignId}:request:{requestId}
```

含义：

- `stock`：剩余活动服务名额；
- `users`：Hash，`userId -> requestId`；
- `request`：Hash，至少包含 `userId/campaignId/status/reservedAt`。

### 7.3 `rush_reserve.lua` 输入

```text
KEYS[1] stockKey
KEYS[2] usersKey
KEYS[3] requestKey

ARGV[1] userId
ARGV[2] requestId
ARGV[3] nowEpochMillis
ARGV[4] resultTtlSeconds
```

活动时间在API和消费者Java代码中校验；Lua只处理原子库存和幂等，不把活动元数据复制进脚本。

### 7.4 返回码

固定：

```text
 1 RESERVED
 2 RESERVED_BY_SAME_REQUEST
 0 SOLD_OUT
-1 DUPLICATE_USER
-2 DUPLICATE_REQUEST_TERMINAL
-3 CAMPAIGN_NOT_INITIALIZED
```

### 7.5 预占原子逻辑

顺序必须是：

1. 若requestKey存在：
   - owner与本次相同且status为`RESERVED`，返回2；
   - 已终态，返回-2；
2. 查询usersHash中的userId：
   - 指向相同requestId，恢复requestKey并返回2；
   - 指向其他requestId，返回-1；
3. stockKey不存在返回-3；
4. stock<=0返回0；
5. `DECR stock`；
6. `HSET users userId requestId`；
7. 写requestHash为RESERVED；
8. 对requestKey设置结果TTL；
9. 返回1。

### 7.6 `rush_rollback.lua`

仅在以下条件同时满足时归还：

```text
users[userId] == requestId
requestKey.userId == userId
requestKey.status == RESERVED
```

满足时：

```text
INCR stock
HDEL users userId
request.status = 指定FAILED状态
设置结果TTL
```

重复补偿返回“已处理”，不得再次增加库存。

### 7.7 初始化

新增 `RushCampaignCachePort.initialize(campaignId, quotaRemaining)`，活动种子/重建任务负责把：

```text
quotaTotal - soldCount
```

写入 Redis。不得直接把 `quotaTotal` 覆盖到已有活动上而忽略数据库成功订单。

---

## 8. RocketMQ规格

### 8.1 Topic和消费组

```text
Topic：rush-order-request
类型：NORMAL
消费组：dash-rush-order-consumer
```

修改 `docker/init-mq.sh`：

```text
创建NORMAL topic rush-order-request
创建消费组 dash-rush-order-consumer
```

不要将其建成 DELAY 或 TRANSACTION 类型。

### 8.2 配置

在线服务 `dash-bootstrap/application.yaml`：

```yaml
dash:
  rush:
    enabled: true
    result-ttl-seconds: 86400
  mq:
    topic:
      rush-order: rush-order-request
```

Worker：

```yaml
dash:
  rush:
    enabled: true
    result-ttl-seconds: 86400
    recovery-interval-ms: 30000
    reserved-timeout-seconds: 120
  mq:
    topic:
      rush-order: rush-order-request
    group:
      rush-order: dash-rush-order-consumer
    consumer:
      rush-order-threads: 8
```

测试配置默认：

```yaml
dash:
  rush:
    enabled: true
  mq:
    enabled: false
```

MQ关闭时Controller测试使用mock port；集成测试直接调用处理UseCase。不要让普通 `mvn test` 强依赖外部Broker。

### 8.3 消息模型

在domain/application可见的纯Java record中定义：

```java
record RushOrderMessage(
    int schemaVersion,
    String requestId,
    long campaignId,
    long campusId,
    long userId,
    Instant acceptedAt,
    String title,
    long rewardCents
) {}
```

`schemaVersion` 第一版固定为1。消费端遇到未知版本返回终态失败或进入死信，不得按旧结构猜测解析。

使用 Jackson 序列化/反序列化，不复制现有消费者里的手写JSON截取方式，因为本消息包含字符串与时间字段。

### 8.4 Producer端口

领域端口：

```java
interface RushOrderQueuePort {
    void enqueue(RushOrderMessage message);
}
```

基础设施实现同步等待 `producer.send(message)` 返回。发送失败抛业务可识别异常，由应用层映射为 `RUSH_QUEUE_UNAVAILABLE`。

可以复用现有普通Producer，但不得每请求创建Producer。

### 8.5 Consumer

新增：

```text
dash-worker/.../RushOrderConsumer.java
```

使用RocketMQ 5 `PushConsumerBuilder.setConsumptionThreadCount(...)`，线程数读取配置，默认8。

消费结果规范：

```text
成功、售罄、重复用户、余额不足、已终态：ConsumeResult.SUCCESS
数据库/Redis暂时故障、未知运行时异常：ConsumeResult.FAILURE
非法schema或无法恢复的毒消息：记录DEAD后SUCCESS，避免无限重试
```

---

## 9. 洋葱模块与文件清单

以下是最低必需文件。实现者可以合理拆分，但不得把职责放错层。

### 9.1 `dash-domain`

```text
campaign/model/RushCampaign.java
campaign/model/RushCampaignStatus.java
campaign/model/RushRequest.java
campaign/model/RushRequestStatus.java
campaign/model/RushReservationOutcome.java
campaign/model/RushOrderMessage.java
campaign/ports/RushCampaignRepository.java
campaign/ports/RushRequestRepository.java
campaign/ports/RushReservationPort.java
campaign/ports/RushOrderQueuePort.java
campaign/ports/RushResultNotifier.java
runner/ports/RunnerWorkloadRepository.java
```

`RushResultNotifier` 不直接依赖WebSocket，用于跨进程结果事件；其基础设施实现可以发普通MQ结果Topic，也可以第一版复用站内通知+轮询。最低要求是查询接口一定可获取最终结果。

### 9.2 `dash-application`

```text
usecase/SubmitRushOrderUseCase.java
usecase/ProcessRushOrderUseCase.java
usecase/RushOrderTransactionalStep.java
usecase/RushReservationRecoveryUseCase.java
usecase/query/QueryRushCampaignUseCase.java
usecase/query/QueryRushRequestUseCase.java
```

必须拆 `RushOrderTransactionalStep`，避免同类自调用导致 `@Transactional` 失效。

### 9.3 `dash-infrastructure`

```text
cache/RedisRushReservationAdapter.java
persistence/JdbcRushCampaignRepository.java
persistence/JdbcRushRequestRepository.java
persistence/JdbcRunnerWorkloadRepository.java
config/RocketMqRushOrderAdapter.java
config/NoopRushOrderQueueAdapter.java
resources/lua/rush_reserve.lua
resources/lua/rush_rollback.lua
```

`NoopRushOrderQueueAdapter` 必须明确失败，不得同步创建订单。

### 9.4 `dash-presentation`

```text
RushCampaignController.java
```

不得把DTO放进domain。可以使用Controller内部record，保持项目现有风格。

### 9.5 `dash-worker`

```text
RushOrderConsumer.java
RushReservationRecoveryJob.java
RushConsistencyCheckJob.java
```

### 9.6 `dash-bench`

```text
RushLoadClient.java
RushLoadClientTest.java
```

### 9.7 Docker和脚本

```text
docker/migrate-p9.sql
bench/scripts/seed_rush.sql
bench/scripts/verify_rush.sql
bench/scripts/cleanup.sh（扩展）
docker/init.sql（扩展）
docker/init-mq.sh（扩展）
```

### 9.8 前端

最低范围：

```text
pages/RushCampaign.tsx
api.ts增加活动API与类型
App.tsx/router.tsx增加路由
ws.ts支持rush.order.result（如实现结果Topic转推送）
```

UI必须显示：

- 活动时间；
- 总名额与已成功数；
- 提交按钮；
- PROCESSING；
- SUCCESS并跳转Errand详情；
- FAILED错误码对应文案；
- WebSocket不可用时轮询。

---

## 10. 数据库事务边界

### 10.1 `RushOrderTransactionalStep.create(...)`

一个本地数据库事务中完成：

1. 幂等查询/插入 `rush_request`；
2. `rush_campaign.sold_count` 条件CAS；
3. 查询用户钱包和托管账户；
4. 条件扣减用户余额；
5. 两条复式资金流水；
6. 托管账户入账；
7. 创建 `escrow_order`；
8. 创建带 `source_request_id` 的 Errand；
9. `DRAFT -> PUBLISHED` CAS；
10. 写 `errand_status_log`；
11. `rush_request` 更新为 SUCCESS并写errandId。

任一步失败全部回滚。

### 10.2 幂等业务号

活动资金业务号固定使用：

```text
rush:escrow:{SHA-256(requestId)的Base64URL摘要}
```

`requestId`允许最长64字符，而`wallet_ledger.biz_no`也是`VARCHAR(64)`，因此不能直接拼接。
摘要方案让完整业务号固定为55字符；相同请求始终得到同一业务号，重复消费仍由唯一索引挡住。

不得只使用新生成的errandId作为幂等依据，因为重复消费前可能还不知道首次生成的errandId。

需要为 `LedgerEntry` 增加可从requestId构造的业务号方法，或在活动事务中明确传入该bizNo。

### 10.3 活动CAS失败

数据库CAS失败时：

- 数据库事务回滚；
- 将请求记录为 `FAILED_SOLD_OUT`（单独小事务或仓储方法）；
- 执行Redis补偿Lua；
- ACK消息。

### 10.4 余额不足

- 数据库事务回滚；
- 持久化 `FAILED_INSUFFICIENT_BALANCE`；
- 补偿Redis名额；
- 通知用户；
- ACK消息。

### 10.5 暂时性技术故障

数据库连接错误、Redis超时等不立即补偿名额，返回消费失败等待重试。恢复任务负责处理超过阈值仍未完成的RESERVED。

---

## 11. Redis与数据库提交后的衔接

数据库事务不能覆盖Redis。实现必须满足以下可恢复性：

### 11.1 数据库成功，Redis结果更新失败

重放消息时先查数据库 `rush_request=SUCCESS`，然后修复Redis结果，ACK。不得再次创建。

### 11.2 数据库成功，Errand抢单Key初始化失败

请求仍是SUCCESS，但任务暂时不可抢。必须：

- 初始化操作可重复；
- `RushReservationRecoveryJob` 或单独修复Job扫描活动SUCCESS任务；
- 根据DB `slot_total - slot_taken`重建Redis slot；
- 布隆过滤器登记可重复。

### 11.3 Redis预占成功，Worker宕机

MQ未ACK会重新投递。Lua返回 `RESERVED_BY_SAME_REQUEST`，消费者继续数据库事务。

### 11.4 Redis预占成功，消息最终进入DEAD

恢复Job检查数据库：

- DB SUCCESS：修复缓存并结束；
- DB无成功事实：补偿Redis，写DEAD和差异记录；
- 无法判断：告警，不得盲目增加库存。

---

## 12. 跑腿抢单与容量改造

### 12.1 同一订单的并发保护保持不变

现有四层仍保留：

```text
Sentinel限流
→ 资格前置
→ Redis grab.lua
→ MySQL casLockForRunner
→ grab_record唯一索引
```

活动生成的 Errand 必须 `slotTotal=1`。

### 12.2 现有在途数量检查只是前置优化

保留 `countOngoingByRunner` 用于友好快速失败，但最终上限必须由数据库事务中的 `runner_workload` CAS保证。

### 12.3 修改 `GrabTransactionalStep`

事务顺序：

```text
1. ensure runner_workload存在（默认maxOngoing取配置）
2. runner_workload CAS +1；失败返回CAPACITY_FULL
3. errand CAS PUBLISHED -> LOCKED；失败抛出/回滚容量
4. 插入runner_capacity_assignment(OCCUPIED)
5. 插入grab_record
6. 写状态日志
7. 提交
```

不能在CAS成功后直接 `return false` 导致事务提交容量+1。任务CAS失败必须使整个事务回滚，或在同一事务中明确抛出受控异常。

`GrabTransactionalStep` 返回值从boolean升级为枚举：

```text
SUCCESS
ERRAND_CONFLICT
CAPACITY_FULL
```

### 12.4 容量释放

新增应用层容量服务/事务步骤，覆盖：

- 超时无候选回退到PUBLISHED；
- 结算到SETTLED；
- 仲裁到SETTLED；
- 仲裁退款到REFUNDED；
- 任何允许的取消终态。

释放算法：

```text
UPDATE runner_capacity_assignment
SET status='RELEASED', released_by=?, released_at=NOW(3)
WHERE errand_id=? AND round=? AND status='OCCUPIED'
```

只有 affectedRows=1 时才：

```text
UPDATE runner_workload
SET ongoing_count=ongoing_count-1, version=version+1
WHERE runner_id=? AND ongoing_count>0
```

两步必须处于同一数据库事务。

### 12.5 超时流转换人

在 `TimeoutTransferStep.transfer(...)` 同一事务内：

1. 释放旧 `errandId + oldRound` assignment；
2. 尝试占用新跑腿员容量；
3. 创建 `errandId + newRound` assignment；
4. CAS `LOCKED -> LOCKED`、修改grabber并round+1；
5. 写状态日志和下一轮本地消息。

新候选容量已满时，不得把任务流转给他。应用层继续轮询下一候选人，且必须限制单次最大跳过数量，避免死循环。

---

## 13. 结果通知

Worker进程没有在线WebSocket Session，不能直接使用内存 `WsSessionRegistry`。

第一版最低要求：

- 最终结果持久化到 `rush_request`；
- 前端按1秒、2秒、4秒、最多5秒间隔轮询；
- 终态停止轮询。

推荐增强：新增普通Topic：

```text
rush-order-result
```

消费组：

```text
dash-rush-order-result-push
```

Worker发送结果，app进程用独立消费组接收并调用扩展后的 `RealtimeNotifier.rushOrderResult(...)`。如果实现结果Topic，必须同时更新 `init-mq.sh` 和配置。

无论是否实现实时推送，轮询查询接口都是最终兜底，WebSocket失败不能改变业务结果。

---

## 14. 恢复与对账

### 14.1 `RushReservationRecoveryJob`

周期：默认30秒。

扫描：

- Redis或DB中超过120秒仍RESERVED/QUEUED的请求；
- DB SUCCESS但Redis不是SUCCESS；
- DB SUCCESS但Errand Redis slot不存在。

动作必须幂等。

### 14.2 `RushConsistencyCheckJob`

至少核对：

```text
DB sold_count == DB SUCCESS rush_request数量
SUCCESS request数量 == RUSH_CAMPAIGN Errand数量
每个SUCCESS都有escrow_order
Redis remaining + Redis reservations 与quota一致
runner_workload快照 == 实际进行中assignment数量
```

差异写入新表或扩展 `recon_diff`。优先复用 `recon_diff`，新增 `check_type`：

```text
RUSH_QUOTA
RUSH_REQUEST_ERRAND
RUNNER_WORKLOAD
```

### 14.3 对账原则

MySQL是最终事实源。自动修复Redis可以，自动篡改资金流水不可以；资金差异只记录并告警。

---

## 15. 测试要求

### 15.1 Domain单测

- 活动窗口推导；
- ENABLED/DISABLED；
- 请求状态终态判断；
- 非法状态转换；
- 消息模型校验。

### 15.2 Lua/Infrastructure测试

- 500库存并发预占不为负；
- 同requestId重试返回SAME_REQUEST；
- 同用户不同requestId被拒绝；
- 补偿一次归还，重复补偿不增加；
- 非持有者不能补偿；
- Redis key均带同一hash tag；
- JDBC CAS最多成功quota次；
- 唯一索引拦截重复用户和重复来源。

### 15.3 Application单测

- Submit只发消息，不写资金/Errand；
- Queue失败映射错误码；
- Process成功；
- 售罄；
- 重复用户；
- 余额不足补偿；
- 暂时性异常不补偿并请求重试；
- SUCCESS消息重放不重复创建；
- DB提交后Redis更新失败可恢复。

### 15.4 Controller测试

- requestId缺失/非法；
- 活动不存在；
- 活动未开始/已结束/停用；
- 202 PROCESSING；
- 只能查询自己的request；
- long ID输出字符串。

### 15.5 Worker测试

- SUCCESS/业务失败返回ConsumeResult.SUCCESS；
- 暂时异常返回FAILURE；
- 未知schema进入DEAD且不无限重试；
- 消费线程数读取配置；
- 重复消息幂等。

### 15.6 集成测试

必须新增：

```text
RushOrderConcurrencyIT
RushOrderIdempotencyIT
RunnerCapacityIT
RushRecoveryIT
```

关键断言：

1. 3000用户、quota=500，成功数严格为500；
2. `sold_count=500`；
3. Redis库存不小于0；
4. 每用户最多一条request；
5. 每request最多一张Errand；
6. 每成功订单恰好一张托管单；
7. 重复消息100次不重复扣款；
8. 两跑腿员抢同一任务只有一人成功；
9. 同一跑腿员并发抢10张任务，成功数不超过K；
10. 所有资金对账不变式通过。

集成测试继续使用 `MiddlewareAvailable`；无MySQL/Redis时跳过。MQ E2E可以单独profile执行，不得让默认测试依赖Broker。

### 15.7 压测

新增 `RushLoadClient`，提供：

```text
--mode sync|async
--requests 3000
--quota 500
--concurrency 3000
--consumer-threads 4|8|16
```

输出：

- API P50/P95/P99；
- 最终完成P50/P95/P99；
- Broker积压峰值；
- 数据库活跃连接峰值；
- 数据库写入峰值；
- 成功/售罄/重复/余额不足；
- CAS冲突；
- 消息重试；
- 对账结果。

---

## 16. 验证脚本

新增 `bench/scripts/verify_rush.sql`，至少执行：

```sql
-- 不超卖
SELECT id, quota_total, sold_count
FROM rush_campaign
WHERE sold_count > quota_total;

-- 每用户最多一次
SELECT campaign_id, user_id, COUNT(*) c
FROM rush_request
GROUP BY campaign_id, user_id
HAVING c > 1;

-- 每请求最多一张Errand
SELECT source_request_id, COUNT(*) c
FROM errand
WHERE source_type = 'RUSH_CAMPAIGN'
GROUP BY source_request_id
HAVING c > 1;

-- SUCCESS必须有Errand
SELECT rr.request_id
FROM rush_request rr
LEFT JOIN errand e ON e.id = rr.errand_id
WHERE rr.status = 'SUCCESS' AND e.id IS NULL;

-- workload与assignment一致
SELECT rw.runner_id, rw.ongoing_count, COUNT(rca.errand_id) actual
FROM runner_workload rw
LEFT JOIN runner_capacity_assignment rca
  ON rca.runner_id = rw.runner_id AND rca.status = 'OCCUPIED'
GROUP BY rw.runner_id, rw.ongoing_count
HAVING rw.ongoing_count <> actual;
```

脚本无异常时输出明确PASS标志，有差异时退出非0或输出可被CI判断的失败数量。

---

## 17. 配置与运行

### 17.1 环境要求

- JDK 21；
- Maven 3.9+；
- MySQL 8；
- Redis 7；
- RocketMQ 5.3.1 Broker/Proxy；
- Node 18+。

### 17.2 Topic初始化

`docker/init-mq.sh` 执行后必须能看到：

```text
rush-order-request           NORMAL
dash-rush-order-consumer     consumer group
```

如实现结果推送，还需要：

```text
rush-order-result
dash-rush-order-result-push
```

### 17.3 构建命令

```bash
cd campus-dash-backend
mvn clean test
mvn install -DskipTests
```

前端：

```bash
cd campus-dash-frontend
npm run build
```

### 17.4 运行顺序

```text
1. MySQL/Redis/RocketMQ
2. migrate-p9.sql或全新init.sql
3. init-mq.sh
4. seed_rush.sql
5. dash-bootstrap
6. dash-worker
7. 前端
```

---

## 18. 可观测性要求

至少以日志和内部统计暴露：

- `rush.submit.accepted`；
- `rush.queue.send.failed`；
- `rush.reserve.success`；
- `rush.reserve.sold_out`；
- `rush.reserve.duplicate_user`；
- `rush.process.success`；
- `rush.process.business_failed`；
- `rush.process.retry`；
- `rush.compensation.success/skip/failure`；
- `rush.recovery.fixed/unresolved`；
- 当前MQ积压和最老消息年龄（可从Broker侧观察）；
- consumer处理耗时；
- runner capacity CAS失败次数。

日志必须包含：

```text
requestId, campaignId, userId, errandId（如有）
```

不得记录Bearer token或敏感凭据。

---

## 19. 安全与参数边界

- 当前用户身份只从鉴权上下文读取；
- 不信任请求体中的userId、campusId、acceptedAt；
- campaignId存在且校区匹配；
- 结果查询验证所有者；
- requestId限制长度和字符集，防Redis Key和日志注入；
- title长度限制；
- rewardCents必须正数并设置合理上限；
- 消费消息再次校验字段，不能信任MQ中一定是合法数据；
- 活动库存初始化和重建使用数据库成功数计算；
- 不通过清空Redis直接“修复”线上活动。

---

## 20. 实施顺序（强制）

### Step 1：Schema与Domain

- 数据表和迁移；
- domain模型、状态、ports；
- ErrorCode；
- ArchUnit通过。

### Step 2：数据库同步基线

- JDBC仓储；
- `RushOrderTransactionalStep`；
- 不使用MQ/Lua的直接应用测试；
- 验证CAS和唯一约束。

### Step 3：Redis Lua

- 预占、补偿脚本；
- Adapter；
- 并发测试和幂等测试。

### Step 4：MQ异步入口

- Topic、Producer、Controller；
- Worker Consumer；
- 查询状态；
- MQ关闭时快速失败。

### Step 5：恢复与结果体验

- Recovery Job；
- 对账；
- 前端轮询；
- 可选结果Topic/WebSocket。

### Step 6：跑腿容量

- workload/assignment；
- 抢单事务；
- 释放和超时流转；
- RunnerCapacityIT。

### Step 7：压测与文档

- 同步/异步对照；
- verify_rush.sql；
- README、设计演进记录、学习手册更新；
- 记录已知边界。

每一步完成后先跑对应测试，不要把所有改动一次性堆到最后调试。

---

## 21. 验收清单

### 功能

- [x] 普通任务同步发布未改变；
- [x] 活动列表和详情可查询；
- [x] 活动订单返回HTTP 202 + PROCESSING；
- [x] 用户能查询最终结果；
- [x] 成功活动请求生成可抢Errand；
- [x] 失败请求展示明确错误码；
- [x] 前端支持处理中和轮询。

### 正确性

- [x] 3000抢500最终严格成功500；
- [x] Redis库存永不小于0；
- [x] DB soldCount不超过quota；
- [x] 同用户同活动只有一次参与；
- [x] 同requestId最多一张Errand；
- [x] 消息重复不重复扣钱；
- [x] 补偿重复不重复加库存；
- [x] 同一Errand最多一个抢中者；
- [x] 跑腿员不超过K个进行中任务；
- [x] 资金借贷平衡和托管闭环通过。

### 可靠性

- [x] MQ不可用不降级同步创建；
- [x] 数据库提交后ACK丢失可幂等恢复；
- [x] Redis结果更新失败可修复；
- [x] Errand抢单Key缺失可重建；
- [x] RESERVED超时有Recovery Job；
- [ ] 死信可追踪；
- [x] WebSocket失败可轮询。

### 工程

- [x] 洋葱依赖规则通过；
- [x] 无每请求创建Producer；
- [x] Consumer线程数可配置；
- [x] 默认测试不依赖RocketMQ；
- [x] `mvn clean test`通过；
- [x] `npm run build`通过；
- [x] SQL迁移和全新初始化都可用；
- [x] init-mq脚本可重复执行；
- [x] 文档与代码一致。

---

## 22. 交付物清单

实现任务最终至少交付：

1. 后端domain/application/infrastructure/presentation/worker代码；
2. 两个Lua脚本；
3. 数据库迁移和init.sql更新；
4. RocketMQ初始化脚本更新；
5. 前端活动页面和异步结果体验；
6. 单元、集成、并发、恢复测试；
7. RushLoadClient和压测报告；
8. verify_rush.sql；
9. README、设计演进记录和学习手册更新；
10. 已知边界与未完成项清单。

完成后，任何人应能用以下一句话验证实现是否符合目标：

> 普通发布仍然同步；限时活动请求先由RocketMQ削峰，再由消费者通过Redis Lua裁决500个名额，并在MySQL中用CAS、唯一索引和本地资金事务完成最终落库；重复消息无副作用，失败可补偿，任务生成后继续使用既有Lua+CAS抢单，同时以数据库容量CAS限制跑腿员多单上限。
