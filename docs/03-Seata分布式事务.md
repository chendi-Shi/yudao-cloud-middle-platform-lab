# 分布式事务（Seata AT）：接入、原理与选型

> 场景：`system-server` 创建订单（`yudao_seata_order` 库）+ 通过 Feign 调用 `infra-server` 扣减余额（`yudao_seata_account` 库）。
> 要求：两个服务的本地事务要么都提交，要么都回滚。
> 实测时间：2026-11-19，Seata Server 2.6.0（file 存储）+ 客户端 `org.apache.seata:seata-spring-boot-starter:2.6.0`

## 一、全景：一次下单经历了什么

```
浏览器/curl
   │ POST /admin-api/system/seata-demo/place-order?orderId=1002&amount=1000
   ▼
网关 48080（限流 + 内部签名）
   ▼
system-server 48081
   │ ① DemoSeataTradeService.placeOrder  ← @GlobalTransactional（全局事务入口）
   │ ② 向 Seata Server 申请开启全局事务 → 拿到 XID
   │ ③ @DS("seata_order") + @Transactional：写订单，提交本地事务时
   │     - 写 undo_log（before/after image）
   │     - 向 Server 注册分支，拿到全局锁（lockKey = demo_seata_order:1002）
   │ ④ Feign 调用 infra-server，请求头带 TX_XID
   ▼
infra-server 48082
   │ ⑤ Seata HTTP 拦截器从 TX_XID 绑定 XID（同一个全局事务）
   │ ⑥ @DS("seata_account") + @Transactional：扣余额
   │     余额不足 → 抛异常 → 本地事务回滚
   ▼
system-server 收到错误（CommonResult.getCheckedData() 抛出）
   │ ⑦ 全局事务标记为回滚 → 通知 Seata Server
   ▼
Seata Server 18091
   │ ⑧ 二阶段：给已注册的分支（订单）下发 BranchRollback
   ▼
system-server 用 undo_log 的 before image 反向补偿 → 订单行被物理删除
```

## 二、AT 模式到底做了什么

AT 的核心是「**业务无侵入的自动补偿**」，分两个阶段：

**一阶段（业务 SQL 与 undo_log 在同一个本地事务里）**

1. 拦截业务 SQL，解析出表名、主键；
2. 查询**修改前**的镜像（before image）和**修改后**的镜像（after image）；
3. 把镜像写进同库的 `undo_log`；
4. 向 Server 注册分支，申请**全局锁**（锁的是 `表名:主键值`，例如 `demo_seata_order:1002`）；
5. 本地事务提交（此时业务数据对其他事务可见，但全局锁会挡住其他全局事务的写）。

**二阶段**

- 全局提交：异步删除 `undo_log`，释放全局锁（很快，几乎零成本）；
- 全局回滚：用 before image 生成反向 SQL（`UPDATE ... SET 旧值` / `DELETE`），校验 after image 是否被别的事务改过（**脏写校验**），然后执行补偿。

**这就是 AT 的关键取舍**：一阶段就提交了本地事务（性能好），靠全局锁 + undo_log 保证可回滚（隔离性是「读未提交」级别，不是强隔离）。

## 三、为什么用 AT，而不是 TCC / 本地消息表 / 最大努力通知

| 方案 | 一致性 | 性能 | 侵入性 | 适用场景 | 本项目的选择 |
| --- | --- | --- | --- | --- | --- |
| **AT（自动补偿）** | 最终一致（回滚后一致） | 一阶段本地提交，性能接近无事务 | **零业务改造**，加注解即可 | 短事务、纯 DB 操作 | ✅ 采用 |
| TCC | 最终一致（业务自己保证） | 最好（无全局锁） | 高：每个服务要写 Try/Confirm/Cancel 三个接口，还要处理空回滚、悬挂、幂等 | 长事务、跨系统、需要资源预留 | ❌ 改造量太大，校招项目扛不住 |
| 本地消息表 | 最终一致 | 好 | 中：要建消息表 + 定时补偿 + 消费幂等 | 异步场景、可接受延迟 | ❌ 这里是同步调用，用户要立刻知道结果 |
| 最大努力通知 | 弱一致 | 最好 | 低 | 支付回调、对账 | ❌ 资金类场景不能丢 |

**一句话结论**：在「短事务 + 纯 DB + 要快速落地」的约束下，AT 是性价比最高的；如果业务里有跨系统的长流程（比如调用外部支付网关），TCC 才值得。

## 四、接入步骤（本项目实际做了什么）

| 步骤 | 内容 | 位置 |
| --- | --- | --- |
| 1 | 建 2 个演示库 + `undo_log` 表 + 种子数据（用户 1，余额 100） | `script/seata/sql/mysql/demo_seata.sql` |
| 2 | 两个服务加 `seata-spring-boot-starter:2.6.0`（排除传递的 druid 1.2.25） | 两个 server 模块的 `pom.xml` |
| 3 | 开 `dynamic.seata=true` + `seata-mode=AT`，master/slave 设 `seata: false`，新增 `seata_order`/`seata_account` 数据源 | 两个 `application-dev.yaml` |
| 4 | 关掉 `enable-auto-data-source-proxy`（数据源已由 Dynamic Datasource 代理，避免双重代理） | 同上 |
| 5 | 发起方关掉 `client.http.interceptor-enabled`，参与方保持默认 true（接收 TX_XID） | 同上 |
| 6 | 实现订单/账户的 DO、Mapper、Service，全局事务入口 | `.../seatademo/` |
| 7 | 补一个 HTTP 入口（官方材料只给了 Service） | `.../controller/admin/seatademo/DemoSeataTradeController.java` |
| 8 | 启动 Seata Server（file 存储 + file 注册，端口 18091） | `script/seata/server/application.yml` + docker |

## 五、踩过的坑（都是真实的）

### 1. 控制器放错包 → 404

`yudao` 的 URL 前缀是按**包路径**决定的：只有 `controller.admin.**` 下的类才会被加上 `/admin-api` 前缀。我一开始把 Controller 放在 `...module.system.seatademo` 包下，请求 `/admin-api/system/seata-demo/...` 直接 404，日志里是 `No static resource admin-api/...`。

**教训**：框架的「约定优于配置」在 URL 映射上体现得最明显，报 404 时先看包路径，别急着查路由。

### 2. 双重数据源代理

Seata 默认会代理 `DataSource` Bean（`enable-auto-data-source-proxy=true`），但本项目的数据源是 `DynamicRoutingDataSource`，内部还托管了多个物理数据源。如果两个都开，会出现「代理套代理」，分支注册和 undo_log 写入都会异常。

**解法**：关掉 Seata 的自动代理，改用 dynamic-datasource 的 `seata: true`（它会给每个数据源包 `DataSourceProxy`），并且只给**参与全局事务的数据源**加 `seata: true`，主库 `master`/从库 `slave` 保持 `false`——避免所有业务 SQL 都被 AT 拦截，白白增加开销。

### 3. 版本冲突：不能用 Spring Cloud Alibaba BOM 管理的 Seata

SCA BOM 里管理的是 `io.seata` 的旧坐标（1.6.x）或 2.5.0，和 `org.apache.seata:2.6.0` 混用会出现 `io.seata` 与 `org.apache.seata` 两套类并存。**必须显式指定版本，并排除 Seata 传递的 druid**（否则项目的 Druid 会被降级到 1.2.25）。

### 4. 忘了 `getCheckedData()` → 全局事务被提交

Feign 接口返回的是 `CommonResult`，账户服务抛的业务异常会被转换成错误码返回，HTTP 状态仍是 200。如果直接忽略返回值，发起方认为「调用成功」，全局事务就提交了，订单留下、余额没扣。

**解法**：`.getCheckedData()` 强制检查错误码。

## 六、实测证据

### 成功用例（订单 1001，扣 30）

```bash
curl -X POST 'http://127.0.0.1:48080/admin-api/system/seata-demo/place-order?orderId=1001&userId=1&amount=30'
# → {"code":0,"msg":"","data":true}

# 数据库
# demo_seata_order: id=1001, amount=30
# demo_seata_account: balance 100 → 70
```

### 失败用例（订单 1002，扣 1000，余额仅 70）

```bash
curl -X POST '.../place-order?orderId=1002&userId=1&amount=1000'
# → {"code":400,"msg":"用户(1) 的账户不存在或余额不足"}

# 数据库：订单表仍然只有 1001（1002 被回滚），余额仍是 70
# undo_log：两张表都是 0 行（回滚后已清理）
```

### Seata Server 侧的完整生命周期

```
Begin new global transaction applicationId: system-server,
  transactionServiceGroup: yudao_seata_demo_group,
  transactionName: demo-seata-place-order, xid: 172.17.0.3:18091:928587850082775044

BranchRegisterRequest{xid=..., branchType=AT,
  resourceId='jdbc:mysql://127.0.0.1:13306/yudao_seata_order',
  lockKey='demo_seata_order:1002'}

GlobalRollbackRequest{xid=...}
BranchRollbackResponse{..., branchStatus=PhaseTwo_Rollbacked, resultCode=Success}
Rollback global transaction successfully, xid = ...
```

注意 `lockKey='demo_seata_order:1002'`——这就是 AT 的全局锁粒度（表名 + 主键），也是它能防脏写的原因，同时也是「热点行会阻塞」的根源。

## 七、局限（面试被问「AT 有什么坑」时用）

1. **隔离性弱**：AT 是「读未提交」，一阶段后本地事务已提交，其他事务能读到中间状态；强隔离场景要考虑 `@GlobalLock` + `SELECT FOR UPDATE`，或者干脆用 TCC
2. **全局锁阻塞**：同一行被两个全局事务并发修改时，后一个会等待锁；热点账户场景要拆行/分段
3. **不适用于外部系统**：AT 只能回滚数据库，调用了第三方接口（支付、短信）它管不了
4. **长事务会放大锁持有时间**：事务里不要放 RPC 之外的慢操作
5. **本演示的 Server 是 file 单机存储**：生产要用 db/redis 存储 + 集群，否则 Server 挂掉事务状态就丢了

## 八、复现

```bash
# 1. Seata Server
docker run -d --name yudao-seata -p 18091:18091 -p 17091:17091 \
  -v $PWD/script/seata/server/application.yml:/seata-server/resources/application.yml:ro \
  apache/seata-server:2.6.0

# 2. 建库
docker exec -i yudao-cloud-mysql-1 mysql -uroot -pRoot@Yudao2026 < script/seata/sql/mysql/demo_seata.sql

# 3. 重启 system / infra（local-dev.sh 已带 YUDAO_SEATA_* 环境变量）
./local-dev.sh system &
./local-dev.sh infra &

# 4. 验证（先登录拿 token）
curl -X POST 'http://127.0.0.1:48080/admin-api/system/seata-demo/place-order?orderId=1001&userId=1&amount=30' \
  -H "Authorization: Bearer <token>" -H 'tenant-id: 1'
```
