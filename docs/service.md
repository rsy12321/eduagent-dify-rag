# eduagent 阶段 2：Chatflow 服务

## 范围与技术选择

Java 17、Spring Boot 3.5.16、Maven Wrapper 3.3.4 / Maven 3.9.16。入口是 Spring MVC；WebClient 消费 Dify HTTP/SSE；SseEmitter 向客户端推送事件。持久层使用 Spring Data JPA，适合本项目的实体查询和消息/引用事务保存；Flyway 管理结构，Hibernate 仅校验结构。

阶段 2-b 根据用户已完成的真实联调反馈修复代码，本轮仅用 localhost MockWebServer、内存 H2 验证，没有访问 VM 的 Dify 或 MySQL，没有更改 Dify 配置、讲义或人工评测记录。此前实测结论见 `eval/manual-review.md`，本轮不重算历史结果。

## 环境变量

应用不会自动读取 `.env`。`service/.env.example` 仅含占位符，不能直接当作可运行配置使用。不要把密钥、密码写入源码、命令参数或报告。

| 变量 | 默认值或要求 |
| --- | --- |
| DIFY_BASE_URL | `http://192.168.191.7:8090/v1`，不能带账号密码、查询参数或 fragment |
| DIFY_API_KEY | 必填，无默认值；缺失或为空时，在数据库初始化前明确报错 |
| DB_URL | `jdbc:mysql://192.168.191.7:3309/eduagent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai` |
| DB_USER | 必填，由环境提供 |
| DB_PASSWORD | 必填，由环境提供 |
| SERVER_ADDRESS | `127.0.0.1` |
| SERVER_PORT | `8080` |
| DIFY_CONNECT_TIMEOUT | `5s` |
| DIFY_BLOCKING_TIMEOUT | `120s`，同步聊天聚合流式结果的整体请求上限 |
| DIFY_STREAM_TIMEOUT | `180s`，Dify 流的整体上限，不随增量或 ping 重置 |
| EVAL_QUESTIONS_PATH | `../eval/questions.json`，先绑定 String，再按进程工作目录解析为规范化绝对 Path |
| EVAL_REPORTS_DIR | `../eval/reports`，先绑定 String，再按进程工作目录解析为规范化绝对 Path |
| EVAL_INTERVAL | `1s`，上一题完成后到下一题开始前的间隔 |
| CITATION_REFUSAL_PREFIXES | `知识库中没有找到相关内容,知识库中没有足够的相关内容`，逗号分隔的拒答前缀；配置键 `eduagent.citations.refusal-prefixes` |

以下示例在 Bash 中交互读取密钥，输入不会回显，也不会将值写入文件：

```bash
cd service
export DB_USER=eduagent
read -r -s -p 'Dify API Key: ' DIFY_API_KEY
printf '\n'
export DIFY_API_KEY
read -r -s -p 'Database password: ' DB_PASSWORD
printf '\n'
export DB_PASSWORD
```

日志不记录请求头或上游响应原文，Authorization 视为敏感信息而完全省略；配置对象的字符串表示使用 `REDACTED`。HTTP 错误和流式错误只保存固定错误码与安全说明，不保存上游错误正文、异常 cause、SQL 参数或凭据。不要额外启用 HTTP wiretap、请求详细日志或 Hibernate 参数日志。

## 本机运行

本机 Maven 仓库使用 `/Users/rsy/DevData/maven-repository`，Wrapper 缓存使用 `/Users/rsy/DevData/maven-wrapper`。这两个路径只记录在本小节，不写入 POM、Wrapper 属性或 `.mvn/maven.config`。

如使用根目录 README 的变量化命令，先在本机设置：

```bash
export EDUAGENT_MAVEN_WRAPPER=/Users/rsy/DevData/maven-wrapper
export EDUAGENT_MAVEN_REPOSITORY=/Users/rsy/DevData/maven-repository
```

在 `service/` 下运行：

```bash
MAVEN_USER_HOME=/Users/rsy/DevData/maven-wrapper ./mvnw -Dmaven.repo.local=/Users/rsy/DevData/maven-repository -v
MAVEN_USER_HOME=/Users/rsy/DevData/maven-wrapper ./mvnw -Dmaven.repo.local=/Users/rsy/DevData/maven-repository dependency:resolve
MAVEN_USER_HOME=/Users/rsy/DevData/maven-wrapper ./mvnw -Dmaven.repo.local=/Users/rsy/DevData/maven-repository test
MAVEN_USER_HOME=/Users/rsy/DevData/maven-wrapper ./mvnw -Dmaven.repo.local=/Users/rsy/DevData/maven-repository spring-boot:run
```

Maven 自动加载现有 `~/.m2/settings.xml`，无需 `-s`，不得修改它或其他 Maven 镜像/代理配置。若遇到 `maven-default-http-blocker` 或依赖下载失败，应停止并报告原始错误。早期试运行的临时缓存已保留，后续不再使用。

其他环境使用默认 `~/.m2` 即可：在 `service/` 下执行 `./mvnw test` 和 `./mvnw spring-boot:run`。无需复制本机绝对路径。运行测试不需要配置上表中的真实凭据；`application-test.yml` 和动态测试属性会覆盖外部服务地址及数据库设置。

## 接口

所有 curl 示例都只调用本服务。服务启动后再手动运行。`userId` 是调用 Dify 的用户标识，继续同一会话时必须使用同一个值。接口没有登录认证，默认只监听回环地址。

### POST /api/chat

```bash
curl --fail -sS http://127.0.0.1:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"question":"惯性是什么？","userId":"demo-user"}'
```

返回结构：

```json
{
  "answer": "回答文本",
  "conversationId": "Dify 返回的会话 ID",
  "messageId": "Dify 返回的消息 ID",
  "citations": [{"documentName": "03-motion-and-force.md", "segmentPosition": 3, "score": 0.9}],
  "latencyMs": 1234
}
```

追问时在入参添加 `conversationId`。未传或为空表示新会话。对外仍返回同一 JSON 结构，内部改用 Dify streaming 并聚合到 message_end，以相同节点规则识别并保存 route；不把 route 添加到原有响应 DTO。仍使用 DIFY_BLOCKING_TIMEOUT 的整体上限，缺少可识别事件时 route 可空。消息和可展示引用在同一事务提交后才返回成功。

### POST /api/chat/stream

```bash
curl --fail -sS -N http://127.0.0.1:8080/api/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"question":"请出两道浮力判断题并附答案。","userId":"demo-user"}'
```

客户端接收的事件：

```text
event:message
data:{"answer":"一个回答增量"}

event:done
data:{"messageId":"消息 ID","conversationId":"会话 ID","route":"出题","citations":[],"latencyMs":1234}
```

非空 `message` 事件按序拼接；answer 为精确空字符串的增量不转发，空格或换行增量仍保留，避免破坏文本拼接。`message_replace` 被转换为 `replace` 事件，其 `answer` 应整体替换已显示文本。`done` 只在消息与引用事务提交成功后发送。失败时最后发送 `error`，例如 `{"code":"DIFY_STREAM_ERROR","message":"Dify reported a stream error"}`，不发送 `done`。HTTP 流建立后，错误通过 SSE 表达，不能只看 HTTP 200。

按本阶段要求，以 `message_end` 作为业务终点：读取其引用、保存结果、发送 `done`。客户端断开会取消上游订阅，并尽力保存一条 FAILED 记录；已成功保存后才发生的断连不回滚成功记录。连接失败、绝对超时、提前 EOF、Dify `error`、失败节点均保存 FAILED；可能保留此前收到的部分答案。数据库自身不可用时无法保证失败记录落库，会返回持久化错误而不发送成功事件。

### 拒答引用展示规则

对最终 answer 执行 `strip()` 去除首尾空白后匹配配置前缀，不匹配正文中间出现的短语；原始答案文本不变。匹配时，blocking 返回值、SSE done 和新增聊天历史的 citations 都为空数组，且不插入 chat_citation。判定发生在聚合及 message_replace 完成后，不依据中途的文本提前决定。正常答案保留原有引用。

原始检索结果仍在评测的 citations_json 与报告中保留，这些命中不是答案的支持证据，不能把拒答时的检索命中重新展示为用户引用。本规则不改变 out_of_scope 的既有自动拒答判分规则，也不清理历史聊天引用。

### GET /api/conversations/{conversationId}/messages

```bash
curl --fail -sS 'http://127.0.0.1:8080/api/conversations/替换为会话ID/messages'
```

仅查询本地记录，不向 Dify 请求历史。返回数组，包含本地 `id`、Dify `messageId`、用户、问题、答案、路由、延迟、状态、安全错误说明、创建时间和引用（含截断预览）；按 `created_at, id` 升序。未找到返回空数组，不代表 Dify 中不存在该会话。时间字段以 UTC 保存，未附时区的数据库时间也按 UTC 理解。

### POST /api/eval/run

```bash
curl --fail -sS --max-time 4000 -X POST http://127.0.0.1:8080/api/eval/run
```

同步串行执行配置文件中的全部题目；默认文件是现有 20 题。每题都省略 Dify `conversation_id`，同批使用 `eval-{runId}` 用户标识。复用聊天的流式解析和落库逻辑，因此评测也会生成 `chat_message` 记录。相邻题默认间隔 1 秒。同一服务进程只允许运行一个批次，重复提交返回 409。

返回 `runId`、`total`、`routeCorrect`、`refusalCorrect`、`refusalTotal`、`routeAccuracy`、`refusalAccuracy`、`avgLatencyMs`、`maxLatencyMs`、`failedRequests`、`report`。准确率返回 0–1 的数值；没有知识库外题时，拒答准确率返回 null，报告显示 N/A。

统计严格采用任务给定规则：

- 路由命中数：`actual_route == expected_route`；分母是本批全部题目。路由为空计错。
- 拒答命中数：仅 `out_of_scope` 的答案含精确短语“知识库中没有”；分母仅为本批 `out_of_scope` 数量。其他措辞即使合理也可能判 FAIL。
- `qa`、`generate` 的 `auto_check` 始终为 MANUAL，即使路由错误或请求失败也不会冒充自动内容评分。
- 规则未用传输成功与否替代上述判分条件；部分失败答案若含该短语仍会命中字面规则，因此要同时查看失败数、聊天状态和人工判定。
- 延迟包含全部题目的 Dify 调用耗时（包括失败），不包含数据库提交、题间等待或报告写入。平均为算术平均，最大值为单题最大耗时。

`eval_result.citations_json` 保存未过滤的原始 `metadata.retriever_resources` 数组（包含完整 content 和其他原始字段），仅供分析；客户端引用与聊天引用表使用过滤后的列表。历史结果不迁移、不改写。

报告在 `eval/reports/eval-yyyyMMdd-HHmmss.md`，文件名时间为 Asia/Shanghai；含要求的汇总及逐题明细，答案摘要按 Unicode 码点截取 120 字。原始检索命中文档去重，列标题为“检索命中（拒答时不展示给用户）”，表格内容进行换行、竖线和 HTML 转义。完整答案和参考答案在数据库中。报告名碰撞不覆盖；报告写入失败时，数据库结果保留，返回带 runId 的 `REPORT_WRITE_FAILED`，`eval_run.note` 记录该情况。

## 表结构

V1 中所有 MySQL 表使用 InnoDB / utf8mb4 / utf8mb4_unicode_ci；H2 测试通过 Flyway 占位符去除 MySQL 表选项，执行同一份字段、外键及检查约束定义。

| 表 | 字段与关系 |
| --- | --- |
| chat_message | id、conversation_id、dify_message_id、user_id、question、answer、route（可空）、latency_ms、status（SUCCESS/FAILED）、error_msg、created_at；会话/创建时间/id 联合索引 |
| chat_citation | id、chat_message_id（外键）、document_name、segment_position、score、content_preview；预览按 Unicode 码点截断 300 字 |
| eval_run | id、started_at、finished_at、total、route_correct、refusal_correct、avg_latency_ms、note；未完成批次用 note 标记 |
| eval_result | id、run_id（外键）、question_id、type、question、expected_route、actual_route、answer、reference_answer、citations_json、latency_ms、auto_check、manual_verdict；同一 run_id/question_id 唯一 |

`manual_verdict` 初始为 null，本阶段不添加修改它的接口。人工复核可使用已有数据库工具填写约定的 PASS/FAIL。一个外部调用期间不保持数据库事务；每条消息及引用原子保存，评测逐条保存，避免整批长事务。进程被强行终止时 RUNNING 批次可能留下部分结果，不能当作完整评测。

## 冒烟脚本

在工作空间根目录执行：

```bash
SERVICE_BASE_URL=http://127.0.0.1:8080 SMOKE_USER_ID=demo-user bash scripts/smoke.sh
```

需要 curl 和 Python 3，脚本只从环境读取服务地址、用户标识、问题及超时，无密钥。依次调用 blocking、新会话的追问、stream、历史查询、批量评测。SSE 若出现 error 或缺少 done 会以非零状态退出。默认执行评测会触发全部题目的实际 Dify 调用；若仅检查聊天，可设 `SMOKE_RUN_EVAL=0`。可配置 `SMOKE_QUESTION`、`SMOKE_STREAM_QUESTION`、`SMOKE_CHAT_TIMEOUT`、`SMOKE_STREAM_TIMEOUT`、`SMOKE_EVAL_TIMEOUT`。JSON 输出优先使用 `python3 -m json.tool --no-ensure-ascii`，旧 Python 不支持时回退为不加该参数。本轮通过替身 curl 测试完整脚本的两种路径，没有执行真实服务的冒烟请求。

## 待实测核对与已知限制

对照依据：[Dify Send Chat Message](https://docs.dify.ai/en/api-reference/chat-messages/send-chat-message)、[Dify SSE Streaming](https://docs.dify.ai/en/api-reference/guides/streaming)、[官方 OpenAPI schemas](https://docs.dify.ai/en/api-reference/openapi_service.json)。

1. **路由规则及修复后核对。** 实现只读取 `node_started` / `node_finished` 的 `data.node_type=answer` 和 `data.title`，精确匹配“答疑回复”→答疑、“出题回复”→出题；未识别或同时发现两个分支时返回 null，不读取假设性的顶层 title 或 LLM 文本。用户已确认现有流式接口和评测可用；本轮同步接口改为复用此路径，使用模拟事件回归，修复后真实环境由用户复核。
2. **待实测核对：结束事件顺序。** 官方指南说明 Chatflow 通常还有后续 `workflow_finished`。本实现按阶段要求在 `message_end` 落库并结束，不等待后续事件，因而不会捕获该节点之后才出现的路由或失败事件。上线前需核对实际流中 Answer 节点事件是否早于 `message_end`；若不符，应先明确调整完成条件。
3. **待实测核对：引用字段。** 根据用户已验证的 `metadata.retriever_resources[].segment_position` 读取段落位置；官方示例还出现 `position`，它不应被擅自当作段落位置替代。缺少 segment_position 时返回 null；没有引用时返回空数组，不伪造出处。
4. 每个 SSE 流有整体上限，最多 8 个活跃推流任务，无排队，超出返回 503。单个回答内存上限为 1,000,000 个 UTF-16 字符，单个上游事件解码上限 2 MiB。数据库提交和慢客户端写入不包含在上游 180 秒预算中；SseEmitter 的兜底超时为流超时加 10 秒。
5. 服务无登录、鉴权或多租户隔离，不校验查询历史的调用者身份。`userId` 只透传给 Dify；仅适用于当前受控开发环境，默认监听回环地址。历史查询尚无分页。
6. 评测接口同步返回，20 题最坏可能接近一小时；代理和客户端需要足够超时。客户端中断评测 HTTP 请求不会可靠地停止批次，重试前先检查数据库以免重复执行。批次互斥仅限单进程。
7. 不自动重试生成请求，避免重复计费或重复消息；不实现 Dify 远端停止/恢复接口。取消订阅只关闭本地上游连接，不能保证 Dify 后端立即停止计算。JVM 系统解析器用于 DNS，避免依赖特定平台的 Netty 原生 DNS 库。
8. H2 验证了迁移和 JPA 映射；真实 MySQL 字符集、时区、账号权限，以及真实 Dify 模型/节点/引用输出仍需用户验收。外部生成答案、引用片段和评测原文按需求存库，应由用户管理数据库访问权限和保留期限。
