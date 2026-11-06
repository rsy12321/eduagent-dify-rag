# eduagent Service

Java 17、Spring Boot 3.5.x、Spring MVC + WebClient + SseEmitter；用本地数据库记录 Dify Chatflow 对话和评测结果。

持久层选择 **Spring Data JPA**：本任务以简单实体读写和事务为主，JPA 的关联级联能将消息与引用一起提交，减少手工维护外键的代码。Flyway V1 是表结构的唯一管理入口，Hibernate 只做 `validate`，不自动建表或改表。测试使用 H2 MySQL 模式执行相同的 V1 迁移。

从 `service/` 启动，环境变量、接口、表结构、评测口径、本机 Maven 缓存规则及限制见 [docs/service.md](../docs/service.md)。`.env.example` 仅是变量清单，应用不会自动加载它。

四个接口：`POST /api/chat`、`POST /api/chat/stream`、`GET /api/conversations/{conversationId}/messages`、`POST /api/eval/run`。

测试不需要 Dify Key 或 MySQL 账号；测试配置使用显式虚拟凭据、localhost MockWebServer 和 H2。测试报告位于 `target/surefire-reports/`，模拟评测报告位于 `target/eval-test-*/reports/`，不写入真实评测报告目录。
