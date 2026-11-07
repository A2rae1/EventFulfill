# EventFulfill 活动预约与票务履约平台

围绕限额活动预约，完成场次发布、异步分配、FIFO候补、确认与模拟支付、票据签发和入场核验。

## 功能与实现

- MySQL事务、场次行锁、容量条件更新与预约权益唯一约束。
- Redis Cache Aside、TTL、Lua请求准入与Redisson。
- 事务Outbox、RocketMQ消费幂等、重试与事件重放。
- 取消/到期释放、候补递补、XXL-JOB补偿与操作审计。
- JWT/RBAC、按场次核验授权与批量履约摘要。
- Vue页面、JUnit并发测试、OpenTelemetry与k6场景。

Java17、Spring Boot、MyBatis-Plus、MySQL、Redis、RocketMQ；gateway/提供Spring Cloud Gateway、Nacos与Resilience4j配置。

## 模块验证

```powershell
mvn -B -ntp verify
node --test ui-tests/template-expressions.test.mjs
```

应用启动、本地账号和部署配置见仓库根目录README与docs/INTEGRATION.md。页面入口8090，Swagger入口/swagger-ui/index.html。
