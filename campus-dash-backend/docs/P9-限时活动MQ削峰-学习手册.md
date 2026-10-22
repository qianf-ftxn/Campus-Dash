# CampusDash 限时活动与 MQ 削峰学习手册

> 面向读者：第一次了解本项目、希望学习高并发下单、抢单、MQ削峰和一致性设计的开发者  
> 编写日期：2026-09-22  
> 当前说明：P9 核心代码已于 2026-09-22 落地；本文中“计划新增”用于保留设计阶段语境。
> 当前已实现活动API、MQ生产/消费、Redis Lua、数据库事务、轮询页面、恢复扫描与跑腿容量CAS；
> 同日已完成真实RocketMQ环境下的3000抢500实压：500成功、2500售罄、0错误，详见
> [`bench/reports/report-P9-20260922.md`](../bench/reports/report-P9-20260922.md)。

---

## 1. 先用一句话理解整个项目

CampusDash 是一个校园跑腿市场：

```text
学生发布悬赏任务并托管资金
→ 多个跑腿员抢单
→ 抢中者限时确认
→ 取货、配送、送达
→ 发单人确认后结算给跑腿员和平台
```

它不是以页面数量或CRUD为重点，而是用一条较短的业务链路学习：

- 高并发下如何不超卖；
- 如何保证同一任务只分给一个跑腿员；
- 钱为什么不能只改一个余额字段；
- Redis和数据库如何分工；
- MQ消息为什么会重复；
- 定时业务如何做到及时又不遗漏；
- 异步系统失败后如何恢复。

---

## 2. 项目现有架构

### 2.1 洋葱依赖

```text
前端 React
    ↓ HTTP / WebSocket
presentation：Controller、鉴权、DTO、响应
    ↓
application：发布、抢单、流转、结算等用例编排
    ↓
domain：状态机、领域模型、Port接口
    ↓
shared：Money、Result、ErrorCode、雪花ID

infrastructure：从外侧实现domain定义的Port
bootstrap：组装并启动在线服务
worker：独立执行MQ消费、扫描、补偿、对账
```

运行时看起来是：

```text
UseCase调用Repository接口
→ 实际进入JdbcRepository
→ 访问MySQL
```

但代码依赖方向是：

```text
JdbcRepository依赖domain里的Repository接口
```

这叫依赖倒置。业务规则不知道底层是MySQL、Redis还是RocketMQ。

### 2.2 为什么Worker独立

在线服务关注低延迟请求：

- 发布任务；
- 抢单；
- 确认、取货、送达；
- 查询详情。

Worker关注后台工作：

- 超时流转；
- 自动结算；
- MQ消费；
- 本地消息重发；
- 缓存校验；
- 对账。

两者拆开后，后台扫描和批量任务不会直接拖慢抢单接口。

---

## 3. 项目原来是怎样同步发布任务的

用户调用：

```text
POST /api/errands
```

在线请求线程会完成全部工作：

```text
查询发单人钱包
→ 条件扣减余额
→ 托管账户入账
→ 写两条资金流水
→ 创建托管单
→ 创建DRAFT任务
→ CAS发布为PUBLISHED
→ 写状态日志
→ 初始化Redis抢单名额
→ 返回任务ID
```

接口返回成功时，用户拿到的是最终结果。

### 3.1 同步方式为什么合理

普通校园跑腿请求通常不是同一秒数千人一起发布。同步方式具有明显优点：

- 用户立刻知道成功或失败；
- 没有“处理中”状态；
- 数据库本地事务容易理解；
- 出错可以直接回滚；
- 不需要消息重试和结果查询。

### 3.2 同步方式什么时候出现压力

如果11:30下课，3000人同时发布“帮我带饭”：

```text
3000个HTTP请求
→ 同时竞争数据库连接
→ 同时执行多次钱包、流水、托管和任务写入
→ 连接池等待、事务提交和索引更新形成尖峰
```

这时问题不是单张任务逻辑错误，而是大量正确事务同时到达，数据库瞬时处理不过来。

---

## 4. 为什么增加“限时活动”

P9计划新增一个更自然的峰值场景：

```text
11:30 午餐限时代购开放
3000名学生同时参与
平台只接受500张配送服务订单
每个用户在同一活动只有一次参与机会
```

也可以理解成联名商品代购，但平台控制的是“代购服务名额”，不是商家商品库存。

### 4.1 500名额代表什么

500代表平台在这个时间窗口可以承接500张订单。

它不表示：

- 商家只有500件商品；
- 必须有500名跑腿员；
- 每个跑腿员只能送一单。

一个合理的运力解释是：

```text
100名跑腿员
× 每人最多5个进行中任务
= 500个配送服务容量
```

第一版先固定500，未来才考虑根据真实在线运力动态计算。

---

## 5. MQ削峰到底是什么

没有MQ：

```text
3000个请求
→ 3000个请求直接尝试执行数据库业务
```

有MQ：

```text
3000个请求
→ RocketMQ暂存
→ 例如8个消费者平稳处理
→ 数据库以可控速度完成写入
```

MQ不会减少总写入数，而是改变写入的时间分布。

可以把它想象成食堂窗口前的蛇形排队区：

```text
没有排队区：所有人同时挤到窗口
有排队区：人仍然一样多，但窗口一次只服务有限人数
```

### 5.1 限流和削峰的区别

Sentinel限流：

```text
超过阈值的请求直接失败
```

MQ削峰：

```text
请求被接受，但稍后处理
```

因此：

| 能力 | Sentinel | RocketMQ |
|---|---|---|
| 主要动作 | 拒绝/快速失败 | 排队/延后处理 |
| 是否保存请求 | 通常不保存 | 保存到队列 |
| 用户结果 | 立即失败 | PROCESSING后出最终结果 |
| 适合场景 | 无意义的过量抢单 | 大量有效写请求 |

### 5.2 为什么原有抢单没用MQ削峰

一张任务只有一个名额时：

```text
2000人抢1单
→ Redis Lua后通常只有1人进入数据库
```

Lua已经过滤了绝大多数失败请求，数据库只有极少量有效写入。再加MQ收益不大，却会让跑腿员先看到“抢单处理中”。

限时活动不同：即使只接受500个订单，仍有500次资金和任务重事务需要完成，所以MQ有明显价值。

---

## 6. 计划中的异步活动流程

### 6.1 在线请求阶段

```text
用户提交活动订单
→ 校验登录、参数、活动时间
→ 将请求发送到rush-order-request Topic
→ Broker确认接收
→ 返回PROCESSING
```

此时还没有完成：

- 扣款；
- 托管；
- Errand创建；
- 活动最终成功。

`PROCESSING`的准确含义是：

> 系统已经可靠地受理请求，最终结果稍后产生。

### 6.2 Worker处理阶段

```text
RocketMQ按有限并发投递
→ Redis Lua裁决活动名额和幂等
→ 成功者执行数据库资金事务
→ 创建Errand
→ 写SUCCESS
→ 用户通过轮询或WebSocket拿到结果
```

### 6.3 为什么第一版MQ在Lua前

如果先Lua再发送普通MQ：

```text
Lua已经扣掉名额
→ 应用突然宕机
→ MQ消息没有发出去
→ 名额可能永久丢失
```

第一版让消息先进入MQ。消费者即使在Lua后宕机，消息没有ACK就会重投，同一requestId可以继续完成数据库操作。

代价是：所有用户最开始都看到 `PROCESSING`，售罄结果不会在API线程内立即返回。

### 6.4 未来为什么可能改成Lua在前

如果希望2500个失败用户立刻看到“售罄”，可以让API先执行Lua，只把500个成功预占者送入MQ。

但必须增加：

- RocketMQ事务消息；或
- Redis Stream可靠转发；或
- PENDING扫描和重发补偿；或
- 数据库Outbox。

这是后续增强，不属于第一版。

---

## 7. Redis Lua在活动里做什么

Lua要把以下操作放进一次不可打断的执行：

```text
检查requestId
检查用户是否参加过
检查剩余名额
扣减名额
记录userId对应requestId
记录请求已RESERVED
```

如果不用Lua，而是Java分开执行：

```text
GET stock
判断stock > 0
DECR stock
```

两个线程可能都先读到1，然后都认为自己能成功。

Lua的价值不是“代码写在Redis里更高级”，而是让检查和修改成为一个原子动作。

### 7.1 Lua要同时处理两种重复

同requestId重复：

```text
网络超时后，客户端用同一个requestId重试
```

同用户不同requestId重复：

```text
用户换一个requestId再次参与同一活动
```

第一种是请求幂等，第二种是业务唯一。两种都要拦截。

### 7.2 为什么Lua不能作为最终保证

Redis可能出现：

- 主从切换；
- 数据误删；
- Key过期；
- 人工重建错误；
- 缓存与数据库暂时不一致。

所以最终仍由MySQL保证：

```text
sold_count不能超过quota_total
同一campaignId + userId只能一条
同一requestId只能一条
同一requestId只能生成一张Errand
```

核心原则：

> Redis负责性能，MySQL负责最终正确。

---

## 8. CAS是什么，什么时候使用

CAS可以理解为：

> 只有我以为的旧状态仍然成立时，才允许我修改。

活动数量更新：

```sql
UPDATE rush_campaign
SET sold_count = sold_count + 1
WHERE id = ?
  AND sold_count < quota_total;
```

抢单状态更新：

```sql
UPDATE errand
SET status = 'LOCKED',
    grabber_id = ?,
    version = version + 1
WHERE id = ?
  AND status = 'PUBLISHED'
  AND version = ?;
```

钱包扣款：

```sql
UPDATE wallet_account
SET available = available - ?
WHERE id = ?
  AND available >= ?;
```

它们的共同点是：更新能否发生取决于当前状态或数量。

### 8.1 不是所有数据库写都要CAS

资金流水通常是追加：

```sql
INSERT INTO wallet_ledger (...)
```

它更依赖唯一 `biz_no` 防止重复，不需要修改旧版本。

状态日志也是追加数据，需要和状态CAS放在同一事务，但自己不需要CAS。

### 8.2 CAS不能替代事务

活动订单成功需要同时完成：

```text
活动sold_count +1
用户扣款
托管账户入账
两条资金流水
托管单
Errand
状态日志
请求SUCCESS
```

其中任何一步失败，其他步骤都应该回滚。因此它们必须处在同一个MySQL本地事务中。

---

## 9. 异步系统为什么需要三层幂等

### 9.1 请求幂等

同一个 `requestId` 重试，不能重复占名额。

### 9.2 业务幂等

同一用户换requestId，也不能绕过“一人一次参与”。

### 9.3 消费幂等

RocketMQ可能把同一消息投递多次。数据库已经成功时，再次消费必须直接识别成功事实，不能再次扣款。

对应的约束是：

```text
requestId唯一
campaignId + userId唯一
Errand.sourceRequestId唯一
wallet_ledger.bizNo唯一
escrow_order.errandId唯一
```

这说明“消息不重复”不是正确性的前提。系统应该假设消息一定可能重复。

---

## 10. 常见故障如何恢复

### 10.1 MQ发送失败

API不返回PROCESSING，返回队列暂不可用。不能偷偷同步执行创建，否则高峰时数据库保护会失效。

### 10.2 MQ发送成功，但HTTP响应丢失

客户端用相同requestId重试。即使再次发送一条相同消息，消费者也不会创建两次。

### 10.3 Lua预占成功，Worker宕机

消息未ACK，RocketMQ重新投递。Lua识别这是同一requestId的已有预占，返回“可以继续处理”，而不是再次扣库存。

### 10.4 数据库暂时不可用

消息返回失败并重试，不立刻归还活动名额。否则当前请求和其他请求可能同时使用同一个名额。

### 10.5 用户余额不足

这是确定性业务失败：

```text
数据库事务回滚
→ 补偿Lua归还名额
→ 请求标记FAILED_INSUFFICIENT_BALANCE
→ MQ消息ACK
```

### 10.6 数据库提交成功，ACK前宕机

RocketMQ重新投递。消费者根据requestId查到数据库SUCCESS：

```text
不再次扣款
不再次创建Errand
修复Redis结果
ACK
```

### 10.7 数据库成功，Redis结果更新失败

数据库是事实源。查询和恢复任务可以根据数据库重新写入Redis结果。

### 10.8 WebSocket推送失败

业务已经成功，推送只是体验层。前端继续轮询结果接口。

---

## 11. 同一订单如何防止两个跑腿员同时抢中

这部分是项目已有能力，活动生成的任务继续复用。

假设任务名额为1：

```text
Redis slot = 1
status = PUBLISHED
version = 0
```

跑腿员A和B同时抢：

### 第一层：Sentinel

过热请求快速失败，保护Redis和数据库。这一层主要保护系统，不负责最终正确性。

### 第二层：资格校验

检查信用分和当前在途任务数，明显不合格的人不进入Lua。

### 第三层：Redis抢单Lua

Lua串行执行：

```text
A把slot从1减到0
B随后看到0
```

大部分竞争在Redis结束。

### 第四层：MySQL CAS

即使Redis异常，让A、B都到了数据库：

```text
A先把PUBLISHED/version0改成LOCKED/version1
B的status/version条件不再成立，affectedRows=0
```

### 第五层：唯一索引和CHECK

```text
同一轮同一seq只能一条grab_record
同一轮同一runner只能一条grab_record
slot_taken不能超过slot_total
```

因此最终只能一人成功。

### Redis成功、数据库失败

应用执行补偿Lua：归还slot并移除占位。否则名额会永久泄漏。

---

## 12. 跑腿员为什么可以多单，但仍需要容量控制

跑腿员去同一食堂一次买多份饭很合理，因此不能要求“跑腿员一人一单”。

正确规则是：

```text
一张订单同一时间一个跑腿员
一个跑腿员可以有多张订单
但同时进行中的订单不能超过K
```

### 12.1 原有检查的竞态

原来是先查询：

```text
countOngoingByRunner < 5 ?
```

跑腿员已有4单，同时抢两张不同订单：

```text
A请求看到4
B请求也看到4
A成功后5
B成功后6
```

所以查询只能作为前置快速失败，不能成为硬约束。

### 12.2 计划新增的最终容量CAS

```sql
UPDATE runner_workload
SET ongoing_count = ongoing_count + 1
WHERE runner_id = ?
  AND ongoing_count < max_ongoing;
```

它要和Errand抢单CAS、grab_record、状态日志放在同一个事务里。

### 12.3 为什么还需要capacity assignment

仅有计数器很难防止重复释放。例如结算消息和补偿任务都执行一次减1，可能把计数减小两次。

因此计划记录：

```text
errandId + round + runnerId + OCCUPIED/RELEASED
```

只有 assignment 第一次从OCCUPIED变为RELEASED时，才允许工作量减1。

### 12.4 哪些状态占用容量

占用：

```text
LOCKED、ACCEPTED、PICKED_UP、DELIVERED、DISPUTED
```

释放：

```text
回退PUBLISHED、SETTLED、REFUNDED、CANCELLED、CLOSED
```

超时从A流转给B时，要在一个事务中释放A并占用B。

---

## 13. 新功能与原有RocketMQ用法的关系

项目已有RocketMQ用途：

| Topic/用途 | 类型 | 解决的问题 |
|---|---|---|
| 确认超时流转 | 定时消息 | 5分钟后处理未确认任务 |
| 自动结算 | 定时消息 | 送达后长期未确认自动结算 |
| 缓存延迟双删 | 定时消息 | 清理并发回填的旧缓存 |
| 资金事件 | 事务消息 | 结算/退款后生成通知 |

P9计划增加：

| Topic/用途 | 类型 | 解决的问题 |
|---|---|---|
| `rush-order-request` | 普通消息 | 大量活动订单创建削峰 |

这样项目可以同时解释三类消息：

```text
普通消息：现在要做，但要排队慢慢做
定时消息：未来某个时间再做
事务消息：本地事务成功后消息才对消费者可见
```

---

## 14. 为什么MQ仍然不是最终正确性的来源

RocketMQ能保证消息存储和重试，但它不知道：

- 用户是否余额充足；
- 活动数据库名额是否还有；
- 同一用户是否已经成功；
- Errand是否创建过；
- 钱是否重复扣过。

这些都要由业务代码和数据库约束判断。

可以记住：

```text
MQ保证“事情会被尝试处理”
幂等保证“重复尝试没有副作用”
数据库约束保证“最终业务事实正确”
补偿与对账保证“异常后能够发现和恢复”
```

---

## 15. 状态机学习

### 15.1 活动请求状态

```text
QUEUED
  ↓
RESERVED
  ├─→ SUCCESS
  ├─→ FAILED_INSUFFICIENT_BALANCE
  ├─→ FAILED_INVALID
  └─→ DEAD

QUEUED也可能直接：
  ├─→ FAILED_SOLD_OUT
  └─→ FAILED_DUPLICATE
```

`PROCESSING`是对外统一展示，可以对应内部的 `QUEUED/RESERVED`。

### 15.2 Errand状态不改变

活动成功生成Errand后，仍使用原有状态机：

```text
DRAFT
→ PUBLISHED
→ LOCKED
→ ACCEPTED
→ PICKED_UP
→ DELIVERED
→ SETTLED
→ CLOSED
```

争议和退款分支保持不变。

新功能不应该再复制一套Errand履约状态机。

---

## 16. 数据库约束为什么重要

应用代码可能有Bug，Redis可能不一致，消息可能重复。数据库约束是最后一道不能被轻易绕过的保险。

计划中的关键约束：

```text
CHECK sold_count <= quota_total
UNIQUE campaign_id + user_id
PRIMARY KEY request_id
UNIQUE errand.source_request_id
UNIQUE wallet ledger biz_no/account/direction
UNIQUE escrow_order.errand_id
CHECK runner ongoing_count <= max_ongoing
PRIMARY KEY runner assignment errand_id + round
```

约束失败不是需要吞掉的“数据库异常”，而是系统发现重复或并发冲突的信号。应用要把它转换成明确的幂等或业务结果。

---

## 17. 如何验证系统真的正确

不能只看接口有没有返回200，也不能只看QPS。

### 17.1 活动不变式

```text
成功数 <= 500
sold_count == SUCCESS请求数
每用户最多一次参与
每requestId最多一张Errand
Redis库存不为负
失败补偿后库存守恒
```

### 17.2 资金不变式

```text
借方总额 == 贷方总额
余额不为负
一张成功Errand一张托管单
同一requestId不重复扣钱
```

### 17.3 跑腿不变式

```text
同一任务同一轮最多一个抢中者
slotTaken <= slotTotal
runner ongoingCount <= maxOngoing
workload快照 == OCCUPIED assignment数量
```

### 17.4 可靠性不变式

```text
重复消息无副作用
数据库成功后ACK丢失仍可恢复
Redis结果丢失可从DB重建
WebSocket失败不丢最终结果
DEAD请求可追踪
```

---

## 18. 同步与异步应该怎样比较

需要做两组压测：

### 同步组

```text
3000个请求直接执行发布事务
```

关注：

- HTTP P99；
- Hikari等待；
- 数据库连接峰值；
- 超时和错误；
- 数据库写入尖峰。

### 异步组

```text
3000个请求快速进入MQ
8个消费者处理
```

关注：

- API受理P99；
- 最终完成P99；
- MQ积压峰值和消化时间；
- 数据库连接峰值；
- 成功/失败正确性；
- 补偿和重试。

异步组的API可能非常快，但用户拿到最终成功需要等待。不能只拿“受理耗时”冒充“订单完成耗时”。

---

## 19. 这个改动的重点、难点和亮点

### 19.1 重点

- 正确区分受理成功和业务成功；
- MQ削峰保护数据库；
- Lua完成高性能原子预占；
- MySQL完成最终裁决；
- 普通发布保持同步，不全盘异步化；
- 跑腿抢单和活动下单使用不同并发方案。

### 19.2 难点

- Redis、MQ、MySQL没有一个共同本地事务；
- 消息重复和ACK丢失；
- 技术失败与业务失败的重试策略不同；
- 补偿必须幂等；
- 数据库成功后缓存/推送失败的恢复；
- 跑腿容量在流转、结算、退款时正确释放；
- 活动结束时队列中合法请求仍要完成。

### 19.3 亮点

- 同一项目展示普通、定时、事务三类RocketMQ消息；
- 用真实业务解释为什么需要MQ；
- Lua、CAS、唯一索引、事务各司其职；
- 有故障恢复、对账和压测，不只画正常时序图；
- 保留同步与异步两种方案并提供数据对照；
- 修复原有跑腿容量“先查后改”的竞态。

---

## 20. 常见理解误区

### “用了Lua就绝对不会超卖”

Lua只保证一次Redis执行原子，数据库仍要CAS和约束兜底。

### “用了MQ数据库压力就消失了”

总写入仍然存在，只是被摊平。

### “MQ不会重复消费”

业务必须按至少一次投递设计。

### “所有数据库写都要CAS”

竞争状态和数量需要CAS；追加流水主要用事务和唯一键。

### “CAS可以代替事务”

CAS保护单个条件更新，多表共同成功仍要本地事务。

### “500张订单必须500个跑腿员”

跑腿员可以多单，但有容量上限。

### “WebSocket推送成功才算业务成功”

数据库是事实源，推送失败可轮询。

### “异步接口返回OK就表示订单创建完成”

第一响应只是PROCESSING，最终看查询结果。

---

## 21. 推荐阅读代码顺序

在P9编码前，先阅读现有代码：

1. `dash-domain/errand/model/ErrandStatus.java`：任务状态机；
2. `dash-application/usecase/PublishErrandUseCase.java`：同步资金托管和发布；
3. `dash-application/usecase/GrabErrandUseCase.java`：抢单四层防护；
4. `dash-infrastructure/resources/lua/grab.lua`：Redis原子占位；
5. `dash-infrastructure/persistence/JdbcErrandRepository.java`：任务CAS；
6. `dash-application/usecase/GrabTransactionalStep.java`：CAS和记录的事务；
7. `dash-application/usecase/TimeoutTransferUseCase.java`：重复消息幂等；
8. `dash-worker/TimeoutTransferConsumer.java`：ACK与重试分类；
9. `dash-infrastructure/config/RocketMqFundEventAdapter.java`：事务消息；
10. `docker/init.sql`：最终数据库约束。

然后按P9新链路阅读：

```text
RushCampaignController
→ SubmitRushOrderUseCase
→ RushOrderQueuePort/Adapter
→ RushOrderConsumer
→ ProcessRushOrderUseCase
→ RedisRushReservationAdapter
→ RushOrderTransactionalStep
→ JdbcRush*Repository
```

---

## 22. 推荐动手实验

### 实验一：只使用数据库CAS

3000并发抢500名额，确认不超卖，但观察数据库连接和CAS冲突。

### 实验二：增加Lua，不加MQ

观察失败请求不再执行数据库重业务，但500个成功事务仍在短时间到达。

### 实验三：增加MQ，限制8消费者

观察数据库峰值下降、MQ出现短暂积压、最终成功数仍为500。

### 实验四：重复投递同一消息100次

确认只创建一张Errand、只扣一次钱。

### 实验五：数据库提交后模拟消费者宕机

确认重投后识别SUCCESS并ACK。

### 实验六：同一跑腿员并发抢10单

设置K=5，确认最终最多成功5单。

这些实验比单纯展示代码更能说明设计的必要性。

---

## 23. 面试表达参考

### 为什么普通发布不用MQ，活动发布使用MQ

> 普通校园任务流量平稳，同步事务能给用户明确最终结果；限时活动在固定时间产生大量有效订单，每个成功订单都包含资金托管和多表写入，所以用RocketMQ将峰值摊平。我们没有把所有接口异步化，而是按业务流量和用户语义选择方案。

### 为什么MQ前面/后面还要Lua

> MQ负责排队，不负责库存和业务唯一；Lua在Redis中原子完成库存、用户和requestId裁决，避免大量失败请求进入重事务。

### 为什么Lua后还要数据库CAS

> Redis负责性能但不是最终事实源。MySQL通过条件更新、唯一索引和CHECK约束保证即使缓存错误也不会最终超卖。

### 如何解决消息重复

> requestId是请求幂等键，campaignId+userId保证业务一人一次，Errand来源和资金bizNo都有唯一约束。数据库成功后即使ACK丢失，重投也只会恢复缓存和返回成功，不会重复扣款。

### 跑腿为什么不是一人一单

> 同路线多单配送符合真实业务，所以约束是一张订单一个跑腿员、一个跑腿员最多K个进行中订单。最终上限使用数据库容量CAS，而不是先查询再判断。

---

## 24. 术语表

| 术语 | 含义 |
|---|---|
| 削峰 | 把瞬时请求分散到更长时间处理 |
| Lua原子脚本 | Redis中一次不可被其他命令插入的多步骤操作 |
| CAS | 只有旧条件仍成立才更新 |
| 幂等 | 同一操作执行多次与执行一次效果相同 |
| 业务唯一 | 例如同一用户同一活动只能参与一次 |
| Outbox | 在本地数据库记录待发送消息，再异步投递 |
| 至少一次投递 | 消息不会轻易丢，但可能重复 |
| 补偿 | 某一步成功、后续失败时撤销或修正前一步 |
| 对账 | 比较不同数据源，发现不一致 |
| 死信 | 多次消费失败后进入专门队列的消息 |
| P99 | 99%的请求耗时不超过该值 |
| 背压 | 下游处理不过来时限制上游处理速度 |

---

## 25. 最终心智模型

把整个P9记成两条链路即可。

### 活动下单

```text
3000个学生
→ RocketMQ排队削峰
→ Redis Lua选出最多500个有效请求
→ MySQL CAS和唯一索引最终确认
→ 本地事务完成资金托管与Errand创建
→ 轮询/WebSocket返回最终结果
```

### 跑腿抢单

```text
多个跑腿员争抢一张Errand
→ Sentinel保护入口
→ Redis Lua原子占位
→ MySQL状态CAS最终裁决
→ 唯一索引兜底
→ runner_workload CAS限制一个跑腿员最多K单
```

对应的一句话原则是：

> MQ负责把事情排队，Lua负责快速原子裁决，CAS和数据库约束负责最终正确，事务负责多表共同成功，幂等负责重复执行无副作用，补偿和对账负责异常后的恢复。
