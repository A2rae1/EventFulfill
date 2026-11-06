# EventFulfill 活动预约与票务履约平台

Java17目标 / Spring Boot3.5.16 / MyBatis-Plus3.5.18 / MySQL8.4.9 / Redis7.4.2 / RocketMQ5.3.2。包含独立业务和RepoLens任务控制面；业务代码由AI按需求实现，没有整体复制教学票务项目。

场次发布→异步受理→容量分配/FIFO候补→免费确认或签名模拟支付→票据→授权核验，取消/超时释放与候补递补形成闭环。另有RBAC、审计、Redis缓存/Lua准入、Outbox租约、MQ幂等/重试/DLQ审计重放、XXL调度补偿、有界批量履约摘要和OTel追踪。

## 运行与验证

在工作区根目录按`docs/INTEGRATION.md`初始化/构建/启动，访问http://127.0.0.1:8090/。`mvn -B -ntp verify`默认H2；真实MySQL/Redis/MQ有独立脚本及条件开关，不混算默认跳过。新MySQL13406，旧学习服务13306不动。接口文档`/swagger-ui/index.html`，鉴权仍生效。

## 关键约束

- 同一场次event_session行锁是状态变更串行点；先锁场次再当前读订单/候补，避免RR旧快照覆盖已分配状态。
- used条件更新、allocation分配/释放和active_claim唯一约束守住容量/重复权益；Redis展示值不能作为最终容量依据。
- 202受理仅表示请求已持久化，HELD/WAITING等是异步结果；释放后先递补既有候补。
- 支付/退款仅模拟，签名/金额/事件ID/状态校验；迟到成功写补偿，不夺回已释放给他人的名额。通知是站内事件/消费记录，不冒充短信邮件服务。
- Outbox同事务、发布租约防陈旧覆盖，发送成功确认丢失允许重投；consumer收据与业务同事务。operator可审计重放原eventId，不能任意改事件。
- 热点场次写吞吐受行锁串行限制；20VU本机k6只证明该配置下样本，不是生产容量。

网关`gateway/`使用Spring Cloud2025.0.3/Gateway/LoadBalancer/Resilience4j，Nacos与双Java实例单独治理组；不是把每个业务表拆成微服务。专项与限制见docs/VERIFICATION.md。
