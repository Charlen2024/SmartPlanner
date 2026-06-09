# SmartPlanner（智慧学习助手）

SmartPlanner 是一个面向个人学习场景的微服务应用：从“目标 → 任务拆解 → 排进日程 → 打卡反馈 → 画像建议”，形成闭环。项目包含两个层面：

- **原理篇**：系统架构、模块职责、数据流与关键算法/约束（排程、RAG、去重、鉴权、异步任务）
- **使用说明书**：如何启动、如何配置、如何调用接口、如何排障、如何二次开发

---

## 目录

- 1. 总览（你能用它做什么）
- 2. 架构与依赖（服务拓扑、端口、组件）
- 3. 统一约定（接口前缀、Result<T>、鉴权、用户上下文）
- 4. 核心数据流（同步请求、异步任务、通知推送）
- 5. LLM 接入原理（DashScope/Spring AI Alibaba、超时与重试）
- 6. 目标拆解（goal-service：AI 拆解、幂等、降级任务）
- 7. 智能排程（schedule-engine：空闲时间、排程、候选方案、日计划 job）
- 8. 资源检索与 RAG（resource-search：ES 检索 + LLM 建议 + 爬虫；user-service：RedisStack 向量检索 + task 推荐 + Agent 复盘）
- 9. 打卡与画像（punch-service + user-service：习惯/洞察/画像）
- 10. 通知系统（RabbitMQ + SSE）
- 11. 运行与部署（Docker Compose / 本地开发）
- 12. API 使用手册（curl 示例）
- 13. 常见问题与排障（401/timeout/ES/重复/构建）
- 14. 安全与生产注意事项

---

## 1. 总览（你能用它做什么）

**面向用户的能力**

- 创建目标并由 AI 拆解为可执行任务（异步，不阻塞请求）
- 导入课表并自动计算空闲时间
- 将待办任务自动排进空闲时段（支持候选方案、确认/拒绝）
- 资源库检索：resource-search 提供 ES 候选检索 + 候选去重 +（可选）LLM 建议
- 任务→课程资源推荐：user-service 聚合（RedisStack 向量检索 + 缓存 + 在线检索兜底），并在排程结果处自动展示
- 打卡记录与习惯画像：近 7 天准时率、完成率、平均延迟、连续打卡等
- 通知推送：排程完成/资源推荐完成等，通过 SSE 实时推送

---

## 2. 架构与依赖（服务拓扑、端口、组件）

### 2.1 服务与端口

| 服务 | 端口 | 说明 |
|---|---:|---|
| web-front | 5175 | Vue3 + Vite + Vuetify，Nginx 静态托管 |
| gateway-service | 8088 | Spring Cloud Gateway，统一入口 `/api/**` |
| user-service | 8080 | 认证 + 用户域接口聚合（对其它服务 OpenFeign 调用入口） |
| agent-service | 8086 | AI Agent 对话、RAG 索引、智能提醒、用户画像分析 |
| goal-service | 8081 | 目标/任务、AI 拆解（MQ 异步） |
| schedule-engine | 8082 | 排程与日计划（支持 job 形式异步执行） |
| resource-search | 8083 | 资源库管理、ES 检索、RAG 增强、去重、资源推荐 job |
| punch-service | 8084 | 打卡记录、习惯数据 |
| admin-server | 9090 | Spring Boot Admin 监控面板（健康、指标、日志、线程、环境变量） |

### 2.2 基础设施

| 组件 | 端口 | 说明 |
|---|---:|---|
| Nacos | 8848 | 服务注册/发现 |
| MySQL | 3306 | 多库（`sp_user/sp_goal/sp_schedule/sp_resource/sp_punch`） |
| RedisStack | 6379 | 缓存/限流 + 向量检索（RedisVectorStore） |
| RabbitMQ | 5672 / 15672 | 异步任务与通知 |
| Elasticsearch | 9201 | 资源检索索引（容器内 9200 映射到宿主 9201） |
| Adminer | 8085 | 轻量数据库管理（~500KB 单文件，类 phpMyAdmin） |
### 2.3 架构拓扑图

```mermaid
graph TB
  Web["web-front :5175"]
  GW["gateway-service :8088"]
  US["user-service :8080"]
  AG["agent-service :8086"]
  GS["goal-service :8081"]
  SE["schedule-engine :8082"]
  RS["resource-search :8083"]
  PS["punch-service :8084"]
  Admin["admin-server :9090"]
  Nacos["Nacos :8848"]
  MySQL[("MySQL :3306")]
  Redis[("Redis :6379")]
  MQ[("RabbitMQ :5672")]
  ES[("Elasticsearch :9201")]

  Web -->|api| GW
  GW -->|lb| US
  GW -->|lb| AG
  US -->|Feign| AG
  US -->|Feign| GS
  US -->|Feign| SE
  US -->|Feign| RS
  US -->|Feign| PS

  GW --> Redis
  US --> Redis
  AG --> Redis
  GS --> Redis
  PS --> Redis

  US -.-> Nacos
  AG -.-> Nacos
  GS -.-> Nacos
  SE -.-> Nacos
  RS -.-> Nacos
  PS -.-> Nacos
  GW -.-> Nacos
  Admin -.-> Nacos

  GS --> MySQL
  SE --> MySQL
  RS --> MySQL
  PS --> MySQL
  US --> MySQL

  GS --> MQ
  SE --> MQ
  RS --> MQ
  US --> MQ
  PS --> MQ
  MQ --> GS
  MQ --> US
  MQ --> RS

  RS --> ES
```

---

## 3. 统一约定（接口前缀、Result<T>、鉴权、用户上下文）

### 3.1 接口前缀与路由

前端默认通过网关访问：

`/api/** -> gateway-service:8088 -> (Nacos) user-service（或 agent-service，匹配 /api/agent/**）-> (Feign) 其它服务`

### 3.2 统一响应体 Result<T>

后端接口统一使用 `Result<T>` 包装，常见字段为：

- `code`：200 表示成功
- `message`：错误信息/提示
- `data`：业务数据

### 3.3 鉴权模型（JWT + 网关透传用户上下文）

认证由 `user-service` 提供 `/api/auth/*`。登录后前端保存：

- `accessToken`：用于请求 `Authorization: Bearer ...`
- `refreshToken`：用于刷新 token

**JWT 令牌结构（HS256 签名）**

访问令牌和刷新令牌均包含以下 claims：

| Claim | 类型 | 说明 |
|-------|------|------|
| `sub` | String | 用户名 |
| `iss` | String | 签发者（`security.jwt.issuer`，默认 `http://sp`） |
| `iat` | Instant | 签发时间 |
| `exp` | Instant | 过期时间 |
| `typ` | String | `"access"` 或 `"refresh"` |
| `userId` | Number | 用户数据库 ID |
| `roles` | List\<String\> | 用户角色列表 |

令牌签发由 `JwtTokenService` 通过 Spring Security 的 `JwtEncoder` + `JwsHeader.with(MacAlgorithm.HS256)` 完成，验证由 `JwtDecoder` 处理。user-service 中提供了 `JwtUtils` 工具类（`getUserId` / `getUsername` / `getRoles` / `getStringClaim`），供各 Controller 统一获取 claims，避免手动类型转换。

网关会在用户携带 JWT 时，把 `userId/username/roles` 透传为请求头（见 [UserContextForwardFilter.java](file:///c:/Users/%E5%88%98%E8%B6%85/Documents/SmartPlanner/gateway-service/src/main/java/com/chao/gateway/filter/UserContextForwardFilter.java)）：

- `X-User-Id`
- `X-Username`
- `X-Roles`

**安全过滤链架构（双重链，防止过期 token 阻塞登录）**

gateway、user-service、agent-service 均采用双 `SecurityFilterChain` 设计：
- **链 0（高优先级）**：匹配公开路径（`/api/auth/**`、`/actuator/**` 等，agent-service 额外包含 `/api/agent/portrait/**`、`/api/agent/tasks/**`、`/api/agent/schedule/**` 供内部 Feign 调用），不配置 OAuth2 Resource Server，直接放行
- **链 1（低优先级）**：匹配其余所有路径，进行 JWT 校验

这样即使用户浏览器缓存了过期 token 后访问登录页，也不会被 `BearerTokenAuthenticationFilter` 拦截。agent-service 的 JWT 解码依赖 `spring.security.oauth2.resourceserver.jwt.secret-key=${JWT_SECRET:}`，与 user-service 保持一致。统一异常处理器 `GlobalExceptionHandler`（位于 common 模块，通过 `scanBasePackages = "com.chao"` 被所有服务共享）提供分层异常处理：`AuthenticationException` → 401 "用户名或密码错误"，`IllegalArgumentException` 等 → 400，其余 → 500 "服务异常"。

### 3.4 网关过滤链（可选 API Key、限流、用户上下文）

- `ApiKeyAuthFilter`：如果配置了 `gateway.auth.api-key`，要求请求头 `X-API-KEY`（见 [ApiKeyAuthFilter.java](file:///c:/Users/%E5%88%98%E8%B6%85/Documents/SmartPlanner/gateway-service/src/main/java/com/chao/gateway/filter/ApiKeyAuthFilter.java)）
- `UserContextForwardFilter`：从 JWT 解析并透传用户信息（见上）
- `RedisRateLimitFilter`：对 `/api/**`（排除 `/api/auth/**`）做 Redis 计数限流（见 [RedisRateLimitFilter.java](file:///c:/Users/%E5%88%98%E8%B6%85/Documents/SmartPlanner/gateway-service/src/main/java/com/chao/gateway/filter/RedisRateLimitFilter.java)）
  - 说明：对流式接口（`/api/agent/chat/stream`）如果需要加禁缓冲响应头，必须通过 `beforeCommit` 设置，避免“响应已提交后再改 header”导致连接被关闭，从而出现断流/一次性返回的错觉。

---

## 4. 核心数据流（同步请求、异步任务、通知推送）

### 4.1 同步请求流（典型）

```text
web-front -> gateway-service (/api/**) -> user-service (/api/user/**)
  -> 通过 OpenFeign 调用 goal/schedule/resource/punch
  -> 返回 Result<T>
```

### 4.2 异步任务与通知（RabbitMQ）

公共交换机/队列定义在 [RabbitMqConfig.java](file:///c:/Users/%E5%88%98%E8%B6%85/Documents/SmartPlanner/common/src/main/java/com/chao/common/config/RabbitMqConfig.java)：

- 目标拆解：
  - exchange：`goal.exchange`
  - queue：`goal.ai.queue`
  - routingKey：`goal.ai.route`
- 通知：
  - exchange：`notification.exchange`
  - queue：`user.notification.queue`
  - routingKey：`notification.route`
- 资源推荐 job（为避免前端 15s 超时而新增）：
  - exchange：`resource.exchange`
  - queue：`resource.advice.queue`
  - routingKey：`resource.advice.route`

通知消费由 `user-service` 完成，并通过 SSE 推给前端（见 [NotificationService.java](file:///c:/Users/%E5%88%98%E8%B6%85/Documents/SmartPlanner/user-service/src/main/java/com/chao/user/service/NotificationService.java) 与 [NotificationController.java](file:///c:/Users/%E5%88%98%E8%B6%85/Documents/SmartPlanner/user-service/src/main/java/com/chao/user/controller/NotificationController.java)）。

---

## 5. LLM 接入原理（DashScope / Spring AI Alibaba）

### 5.1 统一实现：OpenAiCompatClient -> Spring AI Alibaba

项目统一通过 `common` 的 `OpenAiCompatClient.complete(prompt)` 发起模型调用，底层只保留 Spring AI Alibaba（DashScope/Qwen）实现，不再保留双实现/fallback。

### 5.2 关键配置（Docker Compose 已注入）

`.env`：

- `AI_DASHSCOPE_API_KEY`：DashScope API Key
- `MODEL`：模型名（默认 `qwen-max`）

服务侧（compose 环境变量）：

- `SPRING_AI_DASHSCOPE_READ_TIMEOUT=180`：HTTP read-timeout
- `SPRING_AI_RETRY_MAX_ATTEMPTS=1`：关闭重试放大等待
- `SMARTPLANNER_AI_SCHEDULE_TIMEOUT_SECONDS=170`：排程业务层 AI 超时（schedule-engine）
- `SMARTPLANNER_AI_ADVICE_TIMEOUT_SECONDS`：资源建议生成超时（resource-search，默认 90）

### 5.3 超时的分层（你排查 timeout 时要看哪一层）

- **HTTP 层 read-timeout**：DashScope 调用本身读超时
- **Spring AI retry**：重试会把等待放大（本项目默认关）
- **业务层 orTimeout**：对某些 AI 环节（排程、RAG/建议）进行业务超时封顶

---

## 6. 目标拆解（goal-service：AI 拆解、幂等、降级任务）

### 6.1 入口接口

用户侧（推荐经网关/聚合调用）：

- `POST /api/user/goals/ai`（user-service）→ 转发到 goal-service 的 `POST /api/goals`

goal-service 直接接口见 [GoalController.java](file:///c:/Users/%E5%88%98%E8%B6%85/Documents/SmartPlanner/goal-service/src/main/java/com/chao/goal/controller/GoalController.java)：

- `POST /api/goals?userId=...` + body 为目标描述：创建目标并触发 AI 拆解
- `GET /api/goals?userId=...`：目标列表
- `GET /api/goals/{goalId}/tasks?userId=...`：任务列表
- `GET /api/goals/pending-tasks?userId=...`：待办任务（已过滤降级任务）

### 6.2 原理：为什么要用 MQ 异步拆解

目标拆解是典型“慢任务”（模型调用 + JSON 解析 + 递归写库）。同步等待容易造成：

- 前端超时
- 线程占用
- 多次重试导致重复写入

因此创建目标后，拆解通过 MQ 异步执行，完成后发通知。

---

## 7. 智能排程（schedule-engine：空闲时间、排程、候选方案、日计划 job）

### 7.1 课表导入与空闲时间

schedule-engine 直接接口（见 [ScheduleController.java](file:///c:/Users/%E5%88%98%E8%B6%85/Documents/SmartPlanner/schedule-engine/src/main/java/com/chao/schedule/controller/ScheduleController.java)）：

- `POST /api/schedule/import?userId=...&firstWeekMonday=YYYY-MM-DD`：导入课表文件（支持 iCalendar `.ics`、CSV、Excel `.xlsx/.xls`，自动识别中英文表头、节数/时间格式、周数范围，解析 RRULE/BYDAY 循环规则）
- `GET /api/schedule/free-time?userId=...&date=YYYY-MM-DD&firstWeekMonday=YYYY-MM-DD`：计算当天空闲时段（按周数过滤课程，识别课程间碎片时间，填补时间空窗；若周过滤清空全天课程则自动回退为不过滤）
- `PUT /api/schedule/first-week-monday?userId=...&firstWeekMonday=YYYY-MM-DD`：同步第一周周一配置（与 user-service 的 AppUser 保持一致）

用户侧聚合接口（user-service）：

- `POST /api/user/schedule/import`
- `GET /api/user/schedule/free-time`
- `PUT /api/user/schedule/first-week-monday`：设置/清除第一周周一，用于计算当前教学周数，按周范围过滤课程

### 7.1.1 周过滤原理

CSV/Excel 课表支持「周数」列（如 `1-16`、`12-14`、`1-16双`、`17`），导入后课程携带 `weekStart`/`weekEnd`/`weekType` 字段。闲置时间计算时，系统根据「第一周周一」(`firstWeekMonday`) 计算当前日期所属周数：

```
weekNumber = floor(daysBetween / 7) + 1
```

然后只保留 `weekNumber` 在课程周数范围内的课程。若用户未设置 `firstWeekMonday`，则不进行周过滤（全部课程生效）。若周过滤后当天课程为空但课表非空，系统自动回退为不过滤，防止配置错误导致全天误判为空闲。

### 7.2 智能排程（统一在「目标」页发起）

排程算法由 AI 驱动（DashScope），结合任务优先级（priority）、预估耗时（estimatedMinutes）以及用户画像数据（userProfile）。默认节奏为 45 分钟学习 + 10 分钟休息、每天深度任务（≥60min）不超过 3 个、总学习时长不超过 240 分钟。当用户有足够打卡数据后，系统会自动根据 `procrastinationIndex`、`focusDurationAvg` 等指标动态调整这些参数（详见 9.5 节）。AI 不可用时自动降级为规则排程（优先级降序 + 耗时降序贪心填充，规则排程同样感知用户画像偏好）。

- 推荐：`POST /api/user/schedule/daily-plan/jobs`：以 job 形式启动排程（支持指定 goalId/taskIds），完成后通过 SSE 通知
- 结果：`GET /api/user/schedule/task-schedules?from=&to=`：查询排程结果
- 兼容：`POST /api/user/schedule/auto`：历史接口（不再作为前端主流程）

### 7.3 候选方案与确认（PlanCandidate）

排程模块支持生成“候选方案”，由用户确认/拒绝：

- `POST /api/user/schedule/plan-candidates`
- `POST /api/user/schedule/plan-candidates/{candidateId}/decision?accept=true|false`
- `GET /api/user/schedule/plan-candidates?date=YYYY-MM-DD`

### 7.4 日计划 job（避免同步等待）

日计划 commit 支持 job 形式（in-process 异步），见 [DailyPlanJobService.java](file:///c:/Users/%E5%88%98%E8%B6%85/Documents/SmartPlanner/schedule-engine/src/main/java/com/chao/schedule/service/DailyPlanJobService.java)：

- `POST /api/user/schedule/daily-plan/jobs`：返回 `jobId`
- `GET  /api/user/schedule/daily-plan/jobs/{jobId}`：查询 `RUNNING/DONE/FAILED`（主要用于排障）

前端主流程不轮询 job 状态：启动后页面静止，等待 SSE 通知 `SCHEDULE_DONE / SCHEDULE_FAILED` 后自动刷新。

---

## 8. 资源检索与 RAG（resource-search：ES 检索 + LLM 建议 + 爬虫；agent-service：RedisStack 向量检索 + Agent 复盘）

### 8.1 resource-search：资源库 + ES 向量语义检索

MySQL：`sp_resource.course_resources`
Elasticsearch：kNN 向量检索（`text-embedding-v2`，1536维）+ multiMatch 文本检索降级，容器内 9200 对外映射 9201

补充：resource-search 内置 Bilibili 定时爬虫（HTTP 抓取 + 重试 + 去重 + 多层质量过滤），用于持续补全 `course_resources`：

- 开关：`smartplanner.crawler.bilibili.enabled`（默认 true）
- 种子主题：18 个（Java/Spring Boot/Python/Vue/数据结构/算法/计算机网络/操作系统/数据库/机器学习/前端/Linux/Go/Rust/分布式/微服务/设计模式/计算机组成原理）
- 动态主题：从已有资源和用户目标中自动扩展
- 每主题抓取：8 条结果（可配 `per-topic-limit`），含 UP主、播放量、时长、简介摘要
- 多查询词扩展：CJK 主题自动拼接后缀（`教程`/`入门`/`基础`），多个查询词独立请求 B站 API（间隔 400ms），扩充候选池后统一质量过滤
- 主题间延迟：800ms（可配 `topic-delay-ms`），避免被 B 站限流
- 去重：URL 归一化 + DB 已有判断
- 三层质量过滤：
  - **标题门禁**（`isValidTitle`）：过滤纯哈希值（`HEX_HASH` 32位+）、哈希后缀（`_16位hex`）、纯数字、% 开头、无 CJK 的过长英文标题
  - **内容相关性**（`isContentRelevantToTopic`）：bigram 相似度 + CJK 字符匹配，中文阈值 0.06、非中文阈值 0.10
  - **入库写 embedding**：仅通过质量过滤的资源才写入 DB 并生成 1536 维向量存入 ES
- 统计追踪：lastRunTime / lastRunTopicsCount / lastRunNewCount / totalCrawled / consecutiveFailures / consecutiveZeroNew
- 实现入口：`ResourceService.scheduledBilibiliCrawl()`

**SBA 爬虫管理（Actuator 端点）**：

通过 Spring Boot Admin 或直接调用 Actuator 接口管理爬虫：

| 操作 | 方法 | 路径 | 说明 |
|------|------|------|------|
| 查看状态 | GET | `/actuator/crawler` | 返回 enabled/running/paused/totalCrawled/lastRunTime 等统计 |
| 手动触发 | POST | `/actuator/crawler` `{"action":"trigger"}` | 立即触发一次爬取（不等待定时器） |
| 暂停爬虫 | POST | `/actuator/crawler/pause` | 暂停后续定时爬取，不影响正在运行的任务 |
| 恢复爬虫 | POST | `/actuator/crawler/resume` | 恢复定时爬取 |

**健康指示器**（显示在 SBA 面板 Wallboard）：

| 状态 | 条件 |
|------|------|
| 🟢 UP | 正常运行 |
| 🟡 OUT_OF_SERVICE | 连续 2 次爬取零新增（可能主题已覆盖全面） |
| 🔴 DOWN | 连续 3 次爬取失败（网络/API 异常） |
| ⚪ UNKNOWN | 爬虫已禁用 |

**目标驱动的即时爬取**：

用户提交 Goal 后，系统自动触发该主题的 B 站爬虫，无需等待 6 小时定时器：

- 触发链路：提交 Goal → MQ `goal.ai.queue` → `GoalAiWorker` 拆解任务 → Feign `POST /api/resources/crawl` → `crawlTopicAsync()` 异步爬取
- 每次爬取指定主题 3 条结果，结果即时写入 DB/ES
- 失败不影响主流程（独立 try-catch + CompletableFuture）
- 与定时爬虫互斥：`crawlerRunning` AtomicBoolean 防止并发

**健康检查准确性**：

| 场景 | 判定逻辑 | 健康状态 |
|------|----------|----------|
| 新增 > 0 | 正常 | 🟢 UP |
| 新增 = 0，无主题报错 | 可能资源已覆盖 | 🟡 OUT_OF_SERVICE |
| 新增 = 0，部分/全部主题报错 | 实际失败 | 🔴 DOWN（consecutiveFailures++） |
| 顶层异常 | 严重失败 | 🔴 DOWN |

### 8.2 resource-search：排程 × RAG 自动联动

排程完成后（DailyPlanJobService），系统自动提取已排程任务标题作为检索主题，调用 ResourceAdviceJobService 异步触发 RAG 资源推荐：

- 触发链路：排程完成 → 提取 taskTitle（去重限 5 个）→ Feign 调用 resource-search → RabbitMQ 异步 job
- 用户收到 SCHEDULE_DONE 通知时，资源推荐已并行启动，无需额外操作
- 去重：同一标题只触发一次推荐任务，避免重复

### 8.3 resource-search：检索流程（从快到慢、逐级退化）

resource-search 的 `searchResourcesWithAdvice(topic)` 大致策略：

1) ES kNN 向量语义检索：DashScope `text-embedding-v2`（1536 维）生成查询向量 → ES `dense_vector` cosine 相似度匹配，通过 `RestClient` 发送原生 kNN 查询并手动解析 JSON 响应（避免 ES Java Client 版本兼容问题），响应排除了 embedding 字段减少传输体积
2) ES 文本检索降级：kNN 失败时自动 fallback 到 `NativeQuery` multiMatch + BestFields + OR（title^3, topic^2, contentSummary）
3) DB 候选：按 topic/relatedTopics 查询
4) 快速结果：规则过滤 + 去重（无需 LLM）
5) 候选增强：将候选组织成上下文，让 LLM 输出建议与资源
6) 终极兜底（`defaultResources`）：以上全失败时返回国内平台搜索链接（B站/慕课网/知乎/GitHub）

对应实现集中在 [ResourceService.java](file:///c:/Users/%E5%88%98%E8%B6%85/Documents/SmartPlanner/resource-search/src/main/java/com/chao/resource/service/ResourceService.java)。

另外一个更快的接口 `searchResources(topic)` 用于”只要资源列表，不要建议文本”的场景：

- 先 ES kNN 向量语义检索（优先）→ 失败时降级 multiMatch 文本检索 → 返回匹配结果
- 嵌入生成：`buildEmbeddingText(topic, title, summary)` 拼接文本 → `embeddingModel.embed()` 生成 1536 维向量
- 写入路径：爬虫入库时 `generateAndSetEmbedding(doc)` 自动生成向量，失败不阻塞写入
- 兜底平台仅限国内：B站、慕课网、知乎、GitHub（不含 Google/Coursera/edX/Medium）

### 8.4 resource-search：去重原理（重点解决 B 站 BV 分 P / 标题噪声）

- URL canonicalize：尤其 bilibili，将 `?p=` 等 query 归一到主视频 URL
- URL 集合去重：相同 canonical URL 只保留一条
- 标题归一 key 去重：清理噪声后生成 key
- 近似重复 base 去重：防止同一系列标题微小差异刷屏

### 8.5 resource-search：异步资源建议 job（避免前端 15s 超时）

用户侧接口（经 user-service）：

- `POST /api/user/resources/search/advice/jobs`：启动推荐任务，返回 `jobId`
- `GET  /api/user/resources/search/advice/jobs/{jobId}`：轮询结果

resource-search 直接接口见 [ResourceController.java](file:///c:/Users/%E5%88%98%E8%B6%85/Documents/SmartPlanner/resource-search/src/main/java/com/chao/resource/controller/ResourceController.java)。

### 8.6 agent-service：向量库（RedisStack）与索引策略

agent-service 使用 Spring AI 的 `RedisVectorStore`（DashScope embedding）作为向量检索底座，存储两类数据：

**课程索引**（全局共享，`type=course`，无 `userId` 字段）

- 管理方：`AgentRagIndexer`（由每日凌晨 3 点定时任务触发）
- 懒初始化：首次向量检索时从 resource-search 拉取资源列表（最多 800 条），分批写入（每批 20 条，`VectorStoreUtils.addDocsInBatches`）
- 刷新周期：Redis 标记 `sp:rag:indexed:courses`，TTL 1 天，过期后下次查询自动重建
- 向量检索时通过 `filterExpression(type=course)` 命中

**用户索引**（按用户隔离，`type=goal/task/journal/punch`，含 `userId` 字段）

- 管理方：`AgentRagIndexer.ensureUserRagIndexed(userId)`
- 数据源：用户的目标、待办任务、随笔、打卡记录
- 触发时机：Agent 调用 `searchPersonalData` 时懒初始化；每日凌晨 3 点全量预索引
- 去重：Redis 标记 `sp:rag:indexed:u:{userId}`（TTL 3 天）+ 进程内 `ConcurrentHashMap`
- 索引重建前先调用 `VectorStoreUtils.deleteByUserId(vs, userId)` 清理该用户旧文档，防止已删除数据残留

**Agent 混合检索（searchPersonalData）**

- 过滤条件：`userId == X OR type == course`，确保私人数据按用户隔离，课程资源全局共享
- 向量检索不足时降级为关键词兜底（`goalClient.listJournals` 字符串匹配 + `resourceClient.searchOnlineCourses`）

**公共工具**

- `VectorStoreUtils`（`user-service/.../util/VectorStoreUtils.java`）：`addDocsInBatches(vs, docs, batchSize)` 分批写入 + `deleteByUserId(vs, userId)` 按 userId 清理

### 8.7 user-service：任务 → 课程资源推荐（在线检索 + 缓存 + 兜底）

- 批量接口：`POST /api/user/tasks/resources`（入参 taskIds，返回 taskId -> resources[]）
- 核心策略：
  - 调用 `resource-search` 在线检索获取课程资源
  - 缓存：按 taskId 缓存推荐结果（TTL 2 天）
  - 强制刷新：传 `{ "refresh": true }` 可跳过缓存重新检索

对应实现见 [UserController.java](file:///c:/Users/%E5%88%98%E8%B6%85/Documents/SmartPlanner/user-service/src/main/java/com/chao/user/controller/UserController.java)。

### 8.8 web-front：Agent 窗口关怀推送（登录即显示）

Agent 浮窗不再展示“学习建议/关怀文案”，只保留对话能力；关怀消息以“通知推送”的形式出现（右上角 toast + 铃铛列表）。

- 触发：用户登录后，前端会建立 SSE：`GET /api/user/notifications/stream`，后端在连接建立时推送 1 条 `AGENT_REMINDER`（`trigger=login_care`，含天气上下文）。通过 sessionKey 去重防止 SSE 重连导致重复推送；SSE 保活心跳每 30s 一次防止 nginx 空闲超时断开
- 文案：由 user-service 使用 spring-ai-alibaba（DashScope）基于用户的连续打卡/今日计划/最近心情/天气生成（倾向约 50 字、含人文关怀与”最小动作”建议）；失败自动降级为带数据点的兜底文案
- 前端展示：见 [DefaultLayout.vue](file:///c:/Users/%E5%88%98%E8%B6%85/Documents/SmartPlanner/web-front/src/layouts/DefaultLayout.vue) 与 [notify.js](file:///c:/Users/%E5%88%98%E8%B6%85/Documents/SmartPlanner/web-front/src/stores/notify.js)


### 8.9 Agent：9 个激活 + 4 个禁用的 ToolCallback 驱动的智能对话（agent-service）

Agent 基于 Spring AI Alibaba ReactAgent + RedisSaver 实现多轮对话，通过 @Tool 方法获取真实数据，不走硬编码短路。当前共 13 个方法定义，其中 9 个激活、4 个写操作已禁用（仅保留方法供内部使用）：

Agent 逻辑位于独立的 `agent-service`（端口 8086），通过网关路由 `/api/agent/**` 访问。user-service 通过 `AgentAdviceClient` 和 `AgentPortraitClient`（Feign）调用 agent-service 的任务建议和画像分析能力。

**数据查询工具（8 个激活 + 1 个禁用）**

- listGoals() — 查询用户的目标列表
- listPendingTasks() — 查询目标级待办任务定义（goal tasks，不含排程时间）。注意：此工具不返回排程信息，问【今天有什么任务】时必须用 listTaskSchedules
- listGoalTasks(goalId) — 查询某个目标下的任务列表
- listTodaySchedules() — 查询今天排程（自动查今天，禁止用结果编造）
- listTaskSchedules(from, to) — 查询已排程任务列表（task_schedule，含具体开始/结束时间）。用户问【今天有什么任务/我的日程/排程/今天做什么】时使用
- listPunchRecords(taskId, from, to) — 查询打卡记录（含 createdAt、taskTitle、durationText），用于本周/最近打卡总结、判断任务是否完成。taskTitle 优先取打卡时快照，其次查 goal_tasks，再查排程表
- listRecentJournals(days, goalId, limit) — 查询最近 N 天随笔列表（含心情 mood），用于复盘/总结/情绪分析
- getWeather(location?) — 查询指定城市实时天气（温度、天气状况、体感温度、湿度、风速风向、能见度、气压、最高/最低气温）。若不指定城市，自动使用用户在仪表盘选择的城市
- ~~getDailyPlanJobStatus(jobId)~~ — 已禁用

**写入与操作工具（3 个已禁用）**

- ~~createJournal(content, goalId, mood)~~ — 创建随笔/日记记录（已禁用）
- ~~addTask(goalId, title, description, priority, estimatedMinutes)~~ — 为目标添加任务，模糊去重（已禁用）
- ~~startDailyPlanJob(date, mode, goalId, taskIds)~~ — 触发日计划排程 job（已禁用）

**检索工具（1 个）**

- searchPersonalData(query, topK) — 混合检索（RedisStack 向量检索 + 关键词兜底），用 `userId == X OR type == course` 过滤向量库，确保私人数据隔离的同时保留全局课程资源可检索；不足时降级为关键词匹配 + 在线检索；课程结果自动匹配用户目标关键词，标注 `matchesYourGoal` 和 `matchingGoalKeyword` 辅助 LLM 优先推荐相关资源

**Agent 行为约束（防止编造/跑偏）**

- 涉及【我有哪些任务/排程/是否完成/天气】等事实类问题，必须先调工具确认，严禁编造
- 输出使用基本 Markdown 格式（## 标题、- 列表、**加粗**），前端用 marked.js 渲染。支持多步工具调用（如先调 listPunchRecords 再调 listRecentJournals 后汇总输出），系统提示词内置 5 个工具使用示例（今日日程/周总结/课表查询/资料搜索/目标任务）引导模型行为。回复使用 taskTitle（任务名）而非 taskId（任务编号）
- 用户问【你是谁/你叫什么】时，返回固定自我介绍，不走模型生成
- 列出任务时必须同时关注用户问到的其他方面（如心情），不能只答任务列表
- 建议用户操作时在末尾追加跳转链接（跳转: /path），白名单：/、/plan、/goals、/journals、/schedule、/resources、/punch、/profile、/games/2048
- 随笔复盘/总结必须基于随笔数据：优先用检索工具或随笔列表工具拿到原文片段作为依据

### 8.10 Agent：真流式输出（端到端不缓冲）

Agent 流式接口走 `text/plain` 分块输出，链路上任何一层缓冲/压缩/连接提前关闭都会让前端“看起来像一次性返回”。

- 后端：`agent-service` 使用 `StreamingResponseBody` 边写边 flush（接口：`POST /api/agent/chat/stream`）
- 网关：如需补充禁缓冲 header，必须在 `beforeCommit` 阶段设置（见 3.4 说明）
- 前端：`fetch + ReadableStream.getReader()` 持续读取并更新消息文本（见 [assistant.js](file:///c:/Users/%E5%88%98%E8%B6%85/Documents/SmartPlanner/web-front/src/stores/assistant.js)）
- 反代：Nginx 需对该路径关闭 buffering，并建议禁用上游压缩（见 [nginx.conf](file:///c:/Users/%E5%88%98%E8%B6%85/Documents/SmartPlanner/web-front/nginx.conf)）
- 前端渲染：AI 消息通过 marked.js 转 HTML 后用 v-html 渲染，支持 Markdown 链接 `[页面名](/路径)` 的 SPA 内跳转。末尾 `跳转: /path` 会被提取为导航 chip 按钮。

### 8.11 Agent 内部架构

**组件关系**

```
AgentChatService (chat / chatStream / buildAgent)
  ├── AgentUserContext (ThreadLocal<Long> — 请求级用户上下文)
  ├── SmartPlannerTools (@Component 单例，9 个激活 @Tool + 4 个禁用写操作)
  │     └── safeList() — 统一 Feign Result.code 校验
  ├── MessageTrimmingHook (BEFORE_MODEL，MAX_MESSAGES=24，工具消息对齐)
  ├── ReactAgent (per-user ConcurrentHashMap 缓存，RedisSaver 持久化)
  └── AgentRagIndexer (每日凌晨 3 点全量索引 + searchPersonalData 懒索引)
```

**用户上下文传递**：`AgentUserContext` 基于 `ThreadLocal<Long>` 实现，但 `ReactAgent` 内部通过 graph executor 执行工具调用，可能与主调线程不在同一线程。为此，`buildAgent()` 使用 `UserContextToolCallback` 包裹每个 `ToolCallback`——包裹器在构造时捕获 userId，每次 `call()` 前重新设置 `AgentUserContext`、执行后清理，确保无论工具在哪个线程执行，都能获取正确的用户上下文。

**Agent 构建**：`buildAgent(userId)` 共享单例 `SmartPlannerTools`，通过 `MethodToolCallbackProvider` 生成原始回调后，逐条包装为 `UserContextToolCallback`（捕获 userId），再传入 `ReactAgent.builder()`。Agent 实例按 userId 缓存（`ConcurrentHashMap`），超过 1000 条目时清除半数。

---

## 9. 打卡与画像（punch-service + user-service：习惯/洞察/画像）

### 9.1 打卡接口（punch-service）

直接接口见 [PunchController.java](file:///c:/Users/%E5%88%98%E8%B6%85/Documents/SmartPlanner/punch-service/src/main/java/com/chao/punch/controller/PunchController.java)：

- `POST /api/punch/submit`：提交打卡（可带 evidence 截图文件、taskTitle 任务名快照、durationSeconds 学习时长、startedAt/endedAt 时间戳）。提交后自动更新连续打卡天数（Redis 缓存）和习惯指标
- `GET  /api/punch/records`：查询打卡记录（含 taskTitle 字段，支持按 taskId 和日期范围过滤）
- `DELETE /api/punch/records/{recordId}`：删除打卡记录
- `GET  /api/punch/streak`：连续打卡天数（基于数据库日历去重计算，最长回溯 60 天）
- `GET /api/punch/habits`：读取习惯画像（morningPersonScore、focusDurationAvg、procrastinationIndex）
- `PUT /api/punch/habits`：更新习惯画像字段（由画像分析流程调用）

### 9.2 画像与洞察（user-service）

见 [InfoController.java](file:///c:/Users/%E5%88%98%E8%B6%85/Documents/SmartPlanner/user-service/src/main/java/com/chao/user/controller/InfoController.java)：

- `GET  /api/user/insights`：近 7 天洞察（准时率、平均延迟、完成率等）
- `GET  /api/user/portrait`：画像汇总（habits + insights + recommendation + tips）。当画像数据过期/为空时会自动触发一次 AI 分析来补齐建议与推荐参数。
- `POST /api/user/portrait/recompute`：重新计算画像（强制走 AI 分析，返回 recommendation + tips，并回写 habits 的画像字段）。响应新增 `computation` 字段（Map），包含 8 项指标的计算明细（公式、输入值、结果），前端"计算明细"面板可直接渲染，便于用户理解每项指标如何得出
- `GET  /api/user/weather?location=城市名`：天气查询（wttr.in，支持中文/英文城市名；若不传 location 则使用用户保存的城市偏好）。响应使用 `WttrResponse` DTO 反序列化 wttr.in JSON，提取温度/体感温度/风速/湿度/天气描述，英文天气描述自动翻译为中文。前端仪表盘城市选择器通过 `PUT /api/user/weather-location?location=城市名` 保存城市到 Redis（缓存 365 天），Agent 天气 Tool 和仪表盘天气卡片均自动读取

### 9.3 习惯指标计算原理（user-service）

系统维护三个量化指标，存储在 `punch-service` 的 `user_habits` 表中（[UserHabit.java](file:///c:/Users/%E5%88%98%E8%B6%85/Documents/SmartPlanner/punch-service/src/main/java/com/chao/punch/entity/UserHabit.java)），由 `user-service` 的 `InfoController` 负责计算并通过 Feign 回写：

**晨间偏好分（morningPersonScore，0-100）**

```
score = punchRatio × 60 + scheduleRatio × 40
```
- 统计 10:00 前打卡的比例（权重 60%）和 10:00 前排程的比例（权重 40%），映射到 0-100 分

**平均专注时长（focusDurationAvg，0-180 分钟）**

- 从打卡记录中提取实际学习时长（`durationSeconds`），取均值
- 无打卡数据时，以降级方式用已完成排程的 `endTime - startTime` 估算
- 上限 180 分钟

**拖延指数（procrastinationIndex，0-1，越高越拖延）**

```
当 matchedPunchCount < 3（冷启动）:
  P = 0.3 + (1 - completionRate) × 0.7   （上限 0.85）

当 matchedPunchCount ≥ 3（数据充足）:
  P = delayScore × 0.45 + (1 - onTimeRate) × 0.35 + (1 - completionRate) × 0.20
  其中 delayScore = min(1.0, avgDelayMinutes / 180)
```

三个维度权重：延迟程度 45%、不准时率 35%、未完成率 20%。冷启动时用完成率估算，避免数据不足导致的极端值。

### 9.4 习惯数据的双向更新

**方式一：画像计算触发（全量分析）**

调用 `/api/user/portrait` 或 `/api/user/portrait/recompute` 时，`InfoController` 拉取近 7 天打卡记录 + 排程数据，完整计算三项指标，通过 `PunchClient.updateHabits()` 写回 `user_habits`。同时调用 `UserPortraitAiService` 让 AI 基于打卡/排程/随笔片段生成个性化建议（`recommendation` + `tips`）。

**方式二：打卡自动增量更新（轻量微调）**

每次提交打卡时，`PunchService.autoUpdateHabit()` 进行指数移动平均微调：
- morningPersonScore：上午打卡 +2，深夜打卡 -2
- focusDurationAvg：`newFocus = oldFocus × 0.8 + sample × 0.2`
- procrastinationIndex：深夜打卡 +0.02，上午打卡 -0.01

### 9.5 画像反馈排程（闭环已实现）

画像系统生成的 `SchedulePreferenceDto`（`focusMinutes`、`breakMinutes`、`maxDailyMinutes`、`procrastinationIndex`）现在会**自动注入到排程请求中**，形成"分析 → 反馈排程 → 调整权重"的闭环。

**数据通路**

```
user_habits → user-service (buildSchedulePreference) → request.preference → schedule-engine
```

`UserController` 在以下三个排程端点中自动注入偏好数据：
- `POST /api/user/schedule/daily-plan/commit`
- `POST /api/user/schedule/daily-plan/jobs`
- `POST /api/user/schedule/plan-candidates`

**排程策略适配（AI 模式）**

`SchedulePreferenceDto` 以 `userProfile` JSON 字段注入 AI 提示词，模型根据画像调整策略：

- `procrastinationIndex > 0.6`：用户容易拖延 → 减少任务数、多留缓冲、优先排短任务建立成就感
- `procrastinationIndex < 0.3`：用户自律性强 → 可适度紧凑安排
- `focusMinutes`：控制单次学习会话时长
- `breakMinutes`：任务间隔休息时间
- `maxDailyMinutes`：当日学习总时长上限

**排程策略适配（规则降级模式）**

AI 不可用时，规则排程同样感知画像：

- `focusMinutes` 替代硬编码的 45 分钟会话
- `breakMinutes` 替代硬编码的 10 分钟休息间隔
- `maxDailyMinutes` 替代硬编码的 240 分钟日上限
- `procrastinationIndex > 0.6`：深度任务上限从 3 降为 1，最小可排时段放宽到 20 分钟
- `procrastinationIndex < 0.3`：保持正常的深度任务上限 3

**默认值（新用户 / 无数据时的兜底）**

所有偏好参数均可为 null，`schedule-engine` 的 `resolvePreference()` 会自动填充默认值：focusMinutes=45, breakMinutes=10, maxDailyMinutes=240, procrastinationIndex=0.3。行为与改动前完全兼容。

---

## 10. 通知系统（RabbitMQ + SSE）

RabbitMQ 的 exchange/queue/binding 由 common 模块的 [RabbitMqConfig.java](file:///c:/Users/%E5%88%98%E8%B6%85/Documents/SmartPlanner/common/src/main/java/com/chao/common/config/RabbitMqConfig.java) 声明，相关服务需确保能扫描到 `com.chao.common.config`。

### 10.1 SSE 订阅

前端通过 SSE 订阅通知流：

- `GET /api/user/notifications/stream`

后端收到 MQ 通知后，通过 SSE emitter 推送给对应 userId（见 [NotificationController.java](file:///c:/Users/%E5%88%98%E8%B6%85/Documents/SmartPlanner/user-service/src/main/java/com/chao/user/controller/NotificationController.java)）。

SSE 连接维护：后端每 30s 发送一次 heartbeat ping 保持连接活跃；Nginx 为该端点单独配置 `proxy_read_timeout 600s`（见 [nginx.conf](file:///c:/Users/%E5%88%98%E8%B6%85/Documents/SmartPlanner/web-front/nginx.conf)）。

### 10.2 常见通知类型

当前代码中常见：

- `GOAL_TASK_READY`
- `SCHEDULE_DONE / SCHEDULE_FAILED`
- `RESOURCE_ADVICE_DONE / RESOURCE_ADVICE_FAILED`
- `AGENT_REMINDER / AGENT_BADGE`：Agent 感知提醒与成就（右上角通知）

`AGENT_REMINDER / AGENT_BADGE` 的 data 结构（SSE 的 JSON）：

- `content`：展示给用户的一句话提醒
- `payload.nav`：建议跳转页面（如 `/punch`、`/goals`、`/journals`）
- `payload.level`：提示级别（`info`/`warning`/`success`）
- `payload.data`：感知数据快照（如今日已完成/未完成、连续天数、完成时间分布等）

### 10.3 Agent 感知提醒（右上角）

该模块的目标是：基于真实业务数据做“提醒/鼓励/纠偏”，并通过 SSE 推到前端右上角（铃铛列表 + toast）。

- 文案生成方式：
  - 触发服务只负责计算“触发原因 + 感知数据”，并把 `payload.ai.userPrompt` 随通知一起发到 MQ；触发服务不内置关怀话术，`content` 只放触发/数据快照（用于极端情况下可观测）
  - user-service 在消费通知时，通过 spring-ai-alibaba（DashScope）生成最终提醒文案（SSE 下发给前端的是生成后的结果）
  - 质量保护：若模型输出过于泛化/未引用任何数据点/过短/缺少关怀语气/与数据明显矛盾（如把待完成说成已完成），会触发重试与纠偏；仍不达标时才降级为”基于数据的兜底结构”。登录关怀只发送单条消息，避免重复刷屏
  - 防刷屏：定时感知提醒默认按 Redis 去重（每条规则每天最多 1 次）；当 Redis 异常时会自动降级为进程内去重，避免同一条提醒被一分钟一次”疯狂重复”推送。登录关怀按 sessionKey（JWT 签名）去重，通过 Set 追踪每个用户所有已发送过的 sessionKey，避免 SSE 重连或 JWT 刷新导致重复欢迎
  - 二次去重：user-service 在消费 MQ 后、推送 SSE 前，会对 `AGENT_REMINDER/AGENT_BADGE` 做短窗口去重（同一用户同一内容 2 分钟内只推一次），用于抵御上游重复投递/重试造成的刷屏；前端铃铛列表也会做短窗口去重防线，避免 UI 被相同内容淹没

- 数据来源（实时拉取，避免编造）：
  - 打卡：`/api/user/punch/records`、`/api/user/punch/streak`
  - 今日计划：`/api/user/schedule/task-schedules`
  - 随笔：`/api/user/journals`（通过 goal-service 聚合）
- 触发策略（示例规则）：
  - 用户登录建立 SSE：推送 1 条”登录关怀”；文案由模型基于连续天数/待完成数/下一项任务/心情/天气生成，含人文关怀与最小动作建议（跳转 `/schedule` 或 `/goals`）。通过 sessionKey 去重防止 SSE 重连导致重复推送
  - 用户新增任务后：欢迎提醒（文案由模型基于任务/计划数据生成，避免固定模板）（跳转 `/goals`）
  - 随笔/心情命中消极词：安慰提醒（文案由模型基于触发原因与数据生成，避免固定模板）（跳转 `/journals`）
  - 晚上 9 点未完成 ≥ 2 个：提醒用户剩余未完成项（尽量带任务名）（跳转 `/punch`）
  - 连续 7 天且当天计划全部完成：发放成就类提醒（跳转 `/punch`）
  - 同一任务在近 7 天出现“结束时间已过但仍未完成”≥ 3 次：提示用户是否需要拆分/降低门槛（跳转 `/goals`）
  - 同一任务连续 3 天未完成：关怀式纠偏，询问“难度/状态/时间是否需要调整”（跳转 `/goals`）
  - 连续 3 天任务完成率 < 30%：同上（跳转 `/goals`）
  - 连续 2 天游打卡：同上（跳转 `/goals`）
  - 3 天没写随笔：引导记录近况/心情（跳转 `/journals`）
  - 随笔/心情出现“焦虑/压力”等关键词：轻量安慰 + 给一个最小动作建议（跳转 `/journals`）
  - 连续一周随笔都很短：引导写更具体的一件事/一个情绪（跳转 `/journals`）

实现位置：

- 后端：agent-service 的 [AgentReminderService.java](file:///c:/Users/%E5%88%98%E8%B6%85/Documents/SmartPlanner/agent-service/src/main/java/com/chao/agent/service/AgentReminderService.java)（定时评估 + 去重推送）
- 后端：goal-service 的 [GoalService.java](file:///c:/Users/%E5%88%98%E8%B6%85/Documents/SmartPlanner/goal-service/src/main/java/com/chao/goal/service/GoalService.java)（新增任务/新增随笔时即时推送提醒）
- 前端：web-front 的 [DefaultLayout.vue](file:///c:/Users/%E5%88%98%E8%B6%85/Documents/SmartPlanner/web-front/src/layouts/DefaultLayout.vue) 与 [notify.js](file:///c:/Users/%E5%88%98%E8%B6%85/Documents/SmartPlanner/web-front/src/stores/notify.js)（铃铛列表 + 未读数 + 点击跳转）

验证方式（推荐）：

1) 前端登录后保持页面在线（会建立 `GET /api/user/notifications/stream` 的 SSE 连接）
   - 登录建立 SSE 后应收到 1 条 `AGENT_REMINDER`（登录关怀，含天气上下文）
2) 在「目标」页手动新增一个任务：应立刻收到欢迎提醒（右上角铃铛 + toast）
3) 在「随笔」页写一条包含“崩溃/压力/焦虑/没动力”等词的随笔：应立刻收到安慰提醒
4) 排障：
   - RabbitMQ：确认 `notification.exchange` 存在，且 `user.notification.queue` 有 consumer
   - user-service：日志中会出现 NotificationService “收到通知并推送”记录

---

## 11. 运行与部署（Docker Compose / 本地开发）

### 11.1 Docker Compose 一键启动

1) 配置 `.env`

- `AI_DASHSCOPE_API_KEY=REPLACE_WITH_YOUR_DASHSCOPE_API_KEY`
- `MODEL=qwen-max`

2) 启动

```bash
docker compose up -d --build
```

3) 访问

- 前端：http://localhost:5175
- 网关（API）：http://localhost:8088
- RabbitMQ 管理台：http://localhost:15672（默认账号 `sp` / `sp123`）
- Nacos：http://localhost:8848
- Elasticsearch：http://localhost:9201
- Spring Boot Admin：http://localhost:9090
- Adminer：http://localhost:8085（系统 MySQL，账号 root / 密码 root）

### 11.2 本地开发（不走容器）

后端：JDK 17 + Maven

```bash
mvn -DskipTests package
```

前端：Node.js（以 web-front 的 package.json 为准）

```bash
cd web-front
npm install
npm run dev
```

---

## 12. API 使用手册（curl 示例）

### 12.1 注册/登录

```bash
curl -X POST http://localhost:8088/api/auth/register ^
  -H "Content-Type: application/json" ^
  -d "{\"username\":\"demo\",\"password\":\"demo123\"}"
```

```bash
curl -X POST http://localhost:8088/api/auth/login ^
  -H "Content-Type: application/json" ^
  -d "{\"username\":\"demo\",\"password\":\"demo123\"}"
```

返回 `data.accessToken` 后，用它调用后续接口：

```bash
curl -X GET http://localhost:8088/api/auth/me ^
  -H "Authorization: Bearer {accessToken}"
```

### 12.2 导入课表（建议先导入，以便空闲时间/排程）

说明：新用户会在前端「学习计划（/plan）」页完成课表提交；目标拆解本身不强依赖课表，但后续排程/空闲时间计算需要课表数据。

```bash
curl -X POST "http://localhost:8088/api/user/schedule/import" ^
  -H "Authorization: Bearer {accessToken}" ^
  -F "file=@test-data/schedule.csv"
```

### 12.3 创建目标并触发 AI 拆解（异步）

```bash
curl -X POST http://localhost:8088/api/user/goals/ai ^
  -H "Authorization: Bearer {accessToken}" ^
  -H "Content-Type: text/plain" ^
  --data "我想系统学习计算机组成原理，目标是 4 周内完成一轮学习并能做题。"
```

### 12.4 启动目标排程（异步 job）

说明：排程统一在「目标（/goals）」页发起。接口返回 `jobId` 后，前端会等待 SSE 通知 `SCHEDULE_DONE / SCHEDULE_FAILED` 自动刷新。

```bash
curl -X POST "http://localhost:8088/api/user/schedule/daily-plan/jobs" ^
  -H "Authorization: Bearer {accessToken}" ^
  -H "Content-Type: application/json" ^
  -d "{\"date\":\"2026-05-23\",\"mode\":\"merge\",\"goalId\":1,\"taskIds\":null}"
```

（历史接口，不推荐作为主流程）

```bash
curl -X POST "http://localhost:8088/api/user/schedule/auto" ^
  -H "Authorization: Bearer {accessToken}"
```

### 12.5 资源推荐（异步 job，避免 15s 超时）

> 排程完成后系统自动为已排程任务触发 RAG 资源推荐，无需手动调用。以下接口用于手动触发或查看状态。
>
> **Windows curl 注意**：CMD/PowerShell 中 curl 对中文字符的编码处理可能不一致，导致 JSON 解析失败（`Invalid UTF-8`）。推荐使用 Git Bash 的 curl，或通过 `printf` + `--data-binary` 管道方式发送中文 JSON body：
>
> ```bash
> printf '{"topic":"计算机组成原理"}' | curl -X POST "http://localhost:8088/api/user/resources/search/advice/jobs" \
>   -H "Authorization: Bearer {accessToken}" \
>   -H "Content-Type: application/json; charset=UTF-8" \
>   --data-binary @-
> ```

```bash
curl -X POST "http://localhost:8088/api/user/resources/search/advice/jobs" ^
  -H "Authorization: Bearer {accessToken}" ^
  -H "Content-Type: application/json" ^
  -d "{\"topic\":\"计算机组成原理\"}"
```

轮询：

```bash
curl -X GET "http://localhost:8088/api/user/resources/search/advice/jobs/{jobId}" ^
  -H "Authorization: Bearer {accessToken}"
```

### 12.6 任务 → 课程资源推荐（RAG + 缓存，批量）

```bash
curl -X POST "http://localhost:8088/api/user/tasks/resources" ^
  -H "Authorization: Bearer {accessToken}" ^
  -H "Content-Type: application/json" ^
  -d "{\"taskIds\":[1,2,3],\"topK\":3}"
```

### 12.7 Agent 窗口关怀推送（登录即显示）

说明：无需主动调用接口。用户登录后建立 SSE 连接，后端会立即推送一条 `AGENT_REMINDER`（`trigger=login_care`），前端在 Agent 窗口顶部展示该关怀消息。

### 12.8 Agent 对话（流式输出）

```bash
curl -N -X POST "http://localhost:8088/api/agent/chat/stream" ^
  -H "Authorization: Bearer {accessToken}" ^
  -H "Content-Type: text/plain" ^
  --data "帮我总结最近一周随笔里反复出现的学习问题，并给我一个今天能做的改进动作"
```

跳转按钮用法：

- 你可以直接对 Agent 说：打开画像/去日程/进入2048/查询天气。回答末尾出现 `跳转: /path` 时，前端会展示可点击的”跳转按钮”。

流式排查建议（当你体感“不是流式/中途断流”时）：

- 先在对话框发送：`流式测试`（服务端会按固定节奏持续输出，便于判断是链路缓冲还是模型本身不产出增量）
- 如果“流式测试”能增量显示，但正常问答不能：说明模型事件未产出有效增量，需要继续适配流式事件类型/抽取字段
- 如果“流式测试”也不增量：优先排查 Nginx 缓冲、gzip、浏览器缓存、以及网关是否有异常导致连接关闭

---

## 13. 常见问题与排障

### 13.1 401（未授权）

- 原因：未登录/`accessToken` 过期/刷新失败
- 处理：重新登录；前端会尝试用 refreshToken 自动刷新

### 13.2 前端 `timeout of 15000ms exceeded`

- 原因：长耗时任务（尤其 RAG）被同步请求卡住
- 处理：资源推荐已改为 job 异步；如果你仍看到 15s 超时，优先确认前端是否加载到最新构建包（Ctrl+F5）

### 13.3 “建议生成超时或暂不可用…”

- 含义：模型调用失败/超时/输出不合规被丢弃，系统返回兜底文案
- 排查：
  - `AI_DASHSCOPE_API_KEY` 是否正确
  - `SPRING_AI_DASHSCOPE_READ_TIMEOUT` 是否足够
  - `SMARTPLANNER_AI_ADVICE_TIMEOUT_SECONDS` 是否需要增大

### 13.4 Docker 拉取镜像 401 / 网络问题

某些 Dockerfile 或 compose 镜像源可能受网络影响；如果遇到 401，可将基础镜像源替换为可用镜像源后重新 build。

### 13.5 ES 端口被 Cpolar 隧道占用（Windows）

**症状**：`curl http://localhost:9200` 返回 Cpolar 登录页（而非 ES 集群信息），`/_cat/indices` 返回 404。

**原因**：Cpolar 内网穿透工具默认占用 9200 端口，与 ES 的 WSL 端口转发冲突。

**处理**：
- 方法一：关闭 Cpolar 或修改其监听端口
- 方法二：通过 Docker Compose 映射的 9201 端口访问 ES（`http://localhost:9201`）
- 方法三：服务内部通过 Docker 网络（`http://elasticsearch:9200`）访问 ES，不受宿主机端口冲突影响。直接使用 resource-search 的 API（`http://localhost:8083/api/resources/search`）即可正常检索

### 13.6 旧爬虫垃圾数据清理

**症状**：kNN 向量搜索返回不相关内容（如"机器学习"搜出 CPU 评测、汽车评测、电竞桌）。

**原因**：入库质量过滤（`isContentRelevantToTopic`）仅对 `saveIfNew()` 的新数据生效，MySQL 中已存在的旧垃圾数据未被清理。

**处理步骤**：
1. 通过 Adminer（http://localhost:8085）或 MySQL 客户端连接 `sp_resource` 库
2. 查看问题主题的数据：`SELECT id, title, content_summary FROM course_resources WHERE topic = '机器学习';`
3. 删除明显不相关的记录：`DELETE FROM course_resources WHERE topic = '机器学习' AND (title LIKE '%E5%' OR title LIKE '%CPU%' OR title LIKE '%奥迪%');`（根据实际情况调整条件）
4. 重启 resource-search 服务，触发 `reindex-on-startup` 自动重建 ES 索引
5. 验证：`curl "http://localhost:8083/api/resources/search?topic=机器学习"` 确认结果质量

**预防**：确保 `smartplanner.crawler.quality-filter.enabled=true`（默认开启），新爬取数据入库前会经过 bigram 相似度 + 中文字符匹配过滤。

### 13.7 Agent 流式不生效 / 一次性显示 / 断流

按优先级从高到低排查：

1) 前端是否真的在增量更新：硬刷新（Ctrl+F5）确保加载到最新 web-front 构建；Pinia 的消息对象必须是响应式引用（见 [assistant.js](file:///c:/Users/%E5%88%98%E8%B6%85/Documents/SmartPlanner/web-front/src/stores/assistant.js)）
2) 反向代理是否缓冲：Nginx 对 `location = /api/agent/chat/stream` 关闭 `proxy_buffering` / `proxy_request_buffering`，并建议禁用 `Accept-Encoding`（见 [nginx.conf](file:///c:/Users/%E5%88%98%E8%B6%85/Documents/SmartPlanner/web-front/nginx.conf)）
3) 网关是否在响应已提交后改 header：会触发 reactor 异常并关闭连接（见 3.4 说明与 [RedisRateLimitFilter.java](file:///c:/Users/%E5%88%98%E8%B6%85/Documents/SmartPlanner/gateway-service/src/main/java/com/chao/gateway/filter/RedisRateLimitFilter.java)）
4) 用 `curl -N` 直连验证：分别打到 `http://localhost:8088/...`（网关）与 `http://localhost:5175/...`（前端 Nginx）对比，确认是哪一层聚合/断流

---

## 14. 安全与生产注意事项

- 不要把真实的 `AI_DASHSCOPE_API_KEY`、JWT 密钥等敏感信息提交到仓库
- 生产环境务必替换 compose 中的 `JWT_SECRET`、演示账号密码等默认配置
- 生产环境建议启用网关 `gateway.auth.api-key` 并完善跨域/限流策略

---

## 15. 性能优化与功能增强记录（2026-06-08 ~ 2026-06-09）

### 15.1 统一 HTTP 客户端

**问题**：3 处天气查询（InfoController、NotificationController、AgentChatService）各自用 `HttpURLConnection` 发起 HTTP 请求，无连接池、无超时配置，阻塞时可能无限等待。

**修改**：
- 新增 `WeatherClient`（`common/src/main/java/com/chao/common/util/WeatherClient.java`），统一封装 wttr.in 天气查询，含中英文天气映射
- 新增 `WeatherData` DTO（`common/src/main/java/com/chao/common/dto/WeatherData.java`），统一天气响应字段
- 新增 `HttpClientConfig`（`common/src/main/java/com/chao/common/config/HttpClientConfig.java`），提供 `externalRestTemplate` Bean（连接超时 5s，读超时 15s）
- `ResourceService.httpGetTextWithUA()` 改用 `RestTemplate.exchange()` 替代 `HttpURLConnection`

### 15.2 修复 @Async 自调用

**问题**：`ScheduleService.smartScheduleAsync()` 标注 `@Async` 但通过 `this.smartScheduleAsync()` 自调用，Spring AOP 代理不拦截内部调用，导致异步注解失效，排程任务阻塞调用线程。

**修改**：改用 `CompletableFuture.runAsync(() -> smartSchedule(userId))`，确保异步执行。（`schedule-engine/.../service/ScheduleService.java`）

### 15.3 Agent 用户上下文重构

**初始问题**：`AgentChatService` 使用 `ThreadLocal<Long>` 存储当前 userId，但 Spring Boot 默认使用线程池处理请求，ThreadLocal 未及时清理会导致后续请求读到错误 userId、且无法被 GC。

**修改**：移除未受管理的 `ThreadLocal`，改为方法参数显式传递 userId。（`user-service/.../service/AgentChatService.java`）

**2026-06-08 重构**：随 agent-service 独立拆分，`SmartPlannerTools` 需感知当前用户才能调用各 Feign 客户端。引入受管理的 `AgentUserContext`（`agent-service/.../util/AgentUserContext.java`）：
- 基于 `ThreadLocal<Long>` 实现，在 `chat()` 方法中通过 `try/finally` 设置与清理，在 `chatStream()` 中通过 `doFinally` 保证 Reactive 流结束后清理
- `AgentChatService` 不再为每个用户 `new SmartPlannerTools(...)`，改为注入单例 `@Component`
- 消除了每次 agent 构建时重复创建工具实例的开销，同时保证用户隔离正确性

### 15.5 重构 Docker 构建

**问题**：7 个 Java 服务各有独立 Dockerfile（共 ~196 行），内容 95% 相同（仅服务名、端口、Maven/JVM 参数不同），维护成本高。

**修改**：
- 7 个 `{service}/Dockerfile` 合并为根目录 1 个参数化 `Dockerfile`（36 行），通过 ARG（`SERVICE_NAME`、`SERVICE_PORT`、`MAVEN_OPTS`、`JVM_OPTS`）区分服务
- `docker-compose.yml` 用 YAML anchors（`*env-nacos`、`*env-rabbitmq`、`*env-ai`）去重环境变量

### 15.6 统一 TaskExecutor Bean 与启动修复

**问题**：`DailyPlanJobService` 和 `ResourceAdviceJobService` 注入 `@Qualifier("applicationTaskExecutor")`，但 `TaskExecutorConfig` 只定义了 `aiTaskExecutor`，导致 Spring 启动失败。

**修改**：
- `TaskExecutorConfig` 提取 `createExecutor(threadNamePrefix)` 工厂方法，新增 `applicationTaskExecutor` bean（线程前缀 `app-job-`）
- 两个 Bean 各一行，消除重复代码

### 15.7 Agent 修复——修复 userId 错串

**问题**：`AgentChatService.ensureAgent()` 将 Agent 存为单例 `volatile ReactAgent agent`，`userIdSupplier` 永远返回第一个登录用户的 ID，后续用户调用所有 Tool 都查到别人的数据。

**修改**：改为 `ConcurrentHashMap<Long, ReactAgent> agents`，每个用户独立 Agent 实例。

### 15.8 Agent 修复——LLM 日期幻觉

**问题**：LLM 不知道当前日期，调用 `listClasses` 时传入 `date=2023-10-05`（随机编造），导致课表查询返回错误结果或空。

**修改**：每次 `chat/chatStream` 请求自动在用户消息前注入 `[今天是2026-06-08，星期一]`（`todayPrefix()` 方法），LLM 据此传入正确 date 参数。

### 15.9 Agent 新增——课表查询 Tool

**问题**：Agent 缺少课表查询能力，用户问"今天有什么课"时无法回答。

**修改**：
- `SmartPlannerTools` 新增 `listClasses(dayOfWeek?, date?, firstWeekMonday?)` Tool，调用 `scheduleClient.listClasses`
- Tool 返回格式化字符串（`"共3门课：\n1. 课程名 周一 08:00-09:40 地点\n..."`），LLM 直接呈现不解释
- `ScheduleService.listClassSchedules` 优化：`date` 传入时自动推导 `dayOfWeek`，`firstWeekMonday` 为空时自动从 `UserScheduleConfig` 查询
- 系统提示词明确区分"排程任务"（`listTodaySchedules`）与"学校课程"（`listClasses`）

### 15.10 修复 punch_records 缺列

**问题**：`PunchRecord` 实体映射 `task_title` 列，但 `init.sql` 建表时未包含此列，导致 punch-service 查询抛出 `Unknown column 'task_title'`。

**修改**：`sql/init.sql` 新增 `task_title VARCHAR(500)` 列。

### 15.12 修复 agent-service 启动失败与 502

**问题 1 — FeignClient Bean 冲突**：`AgentAdviceClient` 和 `AgentPortraitClient` 均使用 `@FeignClient(name = "agent-service")` 且无 `contextId`，导致 `FeignClientSpecification` Bean 同名注册失败。

**修改**：两个 Feign 客户端分别添加 `contextId = "agent-advice"` 和 `contextId = "agent-portrait"`（`common/.../client/AgentAdviceClient.java`、`AgentPortraitClient.java`）。

**问题 2 — agent-service 缺少安全配置**：agent-service 依赖 `spring-boot-starter-oauth2-resource-server` 但无 `SecurityConfig`，Spring Security 默认要求所有请求认证（浏览器弹密码框），且缺少 `spring.security.oauth2.resourceserver.jwt.secret-key` 配置导致 JWT 解码器无法创建。

**修改**：
- 新增 `SecurityConfig`（`agent-service/.../config/SecurityConfig.java`）：双链设计，内部 Feign 调用路径（`/api/agent/portrait/**`、`/api/agent/tasks/**`、`/api/agent/schedule/**`）放行，其余路径 JWT 认证；显式定义 `JwtDecoder` Bean（`NimbusJwtDecoder.withSecretKey`）避免自动配置在 secret 为空时跳过创建
- `application.yml` 新增 `spring.security.oauth2.resourceserver.jwt.secret-key: ${JWT_SECRET:}`

**问题**：LLM 调用（最长 180s 超时）与用户 CRUD 请求共享 Tomcat 线程池，高并发 Agent 对话可能阻塞登录/注册等轻量请求。

**修改**：
- 新增 `agent-service`（端口 8086），独立部署 AI Agent 对话、RAG 索引、智能提醒、用户画像分析
- user-service 通过 `AgentPortraitClient`、`AgentAdviceClient`（Feign）调用 agent-service
- Agent 流式接口路径改为 `/api/agent/chat/stream`（网关 `Path=/api/agent/**` 路由到 agent-service）
- user-service 移除 VectorStore、DashScope API、EmbeddingModel 等 AI 基础设施依赖，专注用户认证与接口聚合

### 15.13 Agent 构建质量全面优化（2026-06-08）

**系统提示词重写（P0）**：移除「每次只能调用 1 个工具」的矛盾约束（与「先调 A 再调 B」的多步指引冲突），新增 5 个工具使用示例（今日日程/周总结/课表查询/资料搜索/目标任务），引导模型在合适的场景下进行多步工具调用。

**流式输出优化（P0+P1）**：
- `sanitizeStreamChunk` 移除 `chunk.replace("```", "")`——不再删除所有反引号，保留代码块和行内代码的正确渲染
- 流式去重算法从基于 85% 模糊匹配的脆弱实现简化为纯 `startsWith` 检测，消除边界情况下的吞字/重复问题

**SmartPlannerTools 单例化（P1）**：`SmartPlannerTools` 从每次构建 Agent 时 `new` 创建改为 Spring `@Component` 单例 Bean，通过 `AgentUserContext`（受管理的 ThreadLocal）注入用户上下文。9 个构造函数参数通过标准 DI 注入，消除了 agent 重建时的对象分配开销，同时保证用户隔离正确性。

**Feign 调用结果校验（P1）**：新增 `safeList(Result<List<T>>)` 静态辅助方法统一检查 `res.code == 200`，所有 10+ 处工具方法的 Feign 调用结果均通过该方法获取数据，避免在远程调用失败时静默返回空列表。

**课程检索个性化（P2）**：`searchPersonalData` 的关键词兜底检索新增目标关键词匹配——拉取用户的目标标题并提取关键词，课程结果命中时标注 `matchesYourGoal: true` 和 `matchingGoalKeyword`，辅助 LLM 优先推荐与用户学习目标相关的课程资源。

**关键 Bug 修复（P0）**：
- `SecurityConfig`：JWT 密钥为空或长度不足 32 字节时启动即抛 `IllegalStateException`（明确错误信息），避免运行时静默失败
- `AgentAiConfig`：`JedisPooled` 构造函数补充 Redis 密码参数（与原 `RedissonClient` 配置对齐），修复有密码的 Redis 环境下向量库连接失败

**ThreadLocal 跨线程传播修复（P0）**：`AgentUserContext` 基于 `ThreadLocal` 存储 userId，但 `ReactAgent` 内部 graph executor 在不同线程上执行工具调用，导致 `requireUserId()` 抛出 `IllegalStateException`（LLM 回显"提示 userId not set"）。修复方案：`buildAgent()` 新增 `UserContextToolCallback` 包裹器类，在构造时捕获 userId，每次 `call()` / `call(toolInput, toolContext)` 前重新设置 ThreadLocal、执行后清理，确保工具调用无论在线程池中哪个线程运行都能获取正确的用户上下文。

### 15.14 前端 UI 优化（2026-06-09）

**排程天数选择移除**：`GoalsView.vue` 移除「排程天数」下拉选择器（1/3/7 天），排程请求固定 `days=1`。因后端 `commitDailyPlan()` 从未读取 `request.getDays()` 字段，多天排程功能实际无效。

**日期选择器统一为 Vuetify 风格**：三个页面（`GoalsView.vue`、`PlanView.vue`、`ScheduleView.vue`）中 `v-text-field type="date"` 替换为 `v-date-input`。`VDateInput` 是 Vuetify 3.7 Labs 组件，需从 `vuetify/labs/VDateInput` 单独导入并注册到 `createVuetify` 的 components 中，提供 Material Design 风格的日历面板（支持深/浅色主题）。全局设置 `locale: 'zhHans'` 使日历面板显示中文月份，`VDateInput` 添加 `rounded: 'lg'` 默认圆角。

**侧边栏收起模式重构**：`DefaultLayout.vue` 菜单收起（rail 76px）时不再使用 `v-list-item` 的 `prepend-icon`（受限于 `v-list-item__spacer` 和 `v-list-item__content` 内部弹性布局导致图标不居中），改用纯 `v-btn` 图标按钮 + `flexbox` 居中（`flex-direction: column; align-items: center`），展开/收起两种状态独立渲染。顶部增加 84px 灯泡品牌图标容器与展开模式 SmartPlanner 卡片等高对齐，菜单项切换时不再跳动。汉堡按钮收起时容器设为 76px 宽、图标居中，与下方菜单图标垂直对齐。

**画像计算明细**：`ProfileView.vue` 新增可折叠「计算明细」面板（默认收起），展示 8 项指标（准时率、平均延迟、完成率、连续打卡、晨型倾向、平均专注时长、拖延指数、排程推荐）的计算公式、原始输入值和最终结果。后端 `UserInsightDto` 新增 `onTimeCount`、`lateCount`、`totalSchedules` 中间字段，`UserPortraitDto` 新增 `computation` Map（`LinkedHashMap`），`InfoController.buildComputation()` 统一构建计算详情。点击「重新分析」后动态更新。

### 15.15 Agent 基础设施升级（2026-06-09）

#### 15.15.1 消息管理升级：Summarization Hook 替代硬截断

**问题**：`MessageTrimmingHook` 在消息超过 24 条时直接丢弃旧消息（仅保留第一条），导致长期对话中丢失上下文。

**修改**：引入 Spring AI Alibaba 1.1 内置 `SummarizationHook`（`com.alibaba.cloud.ai.graph.agent.hook.summarization`），当上下文 token 超限时自动将历史消息压缩为摘要，保留在 System Message 中，而非简单丢弃。

- 配置：`messagesToKeep(8)` 保留最近 8 条完整消息，其余压缩；`keepFirstUserMessage(true)` 始终保留用户最初的问题
- 新增 `ModelCallLimitHook`：`threadLimit(10)` 单次对话最多 10 轮模型调用，`runLimit(20)` 总计 20 次，防止 Agent 循环失控
- 删除自定义 `MessageTrimmingHook.java`（约 105 行），替换为框架标准组件

#### 15.15.2 LLM 调用可观测性（OpenTelemetry）

**问题**：所有 LLM 调用的延迟、token 消耗、调用频率完全不可见，排查问题只能看日志。

**修改**：Spring AI 1.1.2 已内置 `spring-ai-autoconfigure-model-chat-observation` 自动配置，仅需添加依赖即可。

- `agent-service/pom.xml` 新增 `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp`，通过 Micrometer Observation API 自动记录每次 ChatClient/ChatModel 调用
- 无需代码改动，Spring AI 自动为所有 LLM 调用创建 Observation（span），记录延迟、模型名、token 使用量
- 新增 `spring.ai.chat.client.observations.include-prompt=true` 配置项，可选记录完整 prompt 内容（仅限开发环境）

#### 15.15.3 Agent 系统提示词增强

**问题**：Agent 在多步工具调用场景下偶尔出现调用顺序错误（例如先查随笔再查打卡，而非先打卡后随笔）。

**修改**：系统提示词新增「工具调用规划」指引，要求 Agent 在调用多个工具前先在 `thought` 中列出执行计划（1→2→3），再按计划执行。利用 Spring AI Alibaba ReactAgent 内置的 ReAct 推理循环（Thought→Action→Observation），无需额外 Hook。

#### 15.15.4 画像计算明细准确性修复

**问题**：`buildComputation()` 返回给前端的计算明细存在数据不一致——
- 平均延迟：只显示 `lateCount`，缺少 `totalDelayMinutes`，无法验证 `总延迟÷迟到次数`
- 完成率：`doneCount` 由 `completionRate × totalSchedules` 反推，存在舍入误差
- 晨型/专注/拖延：输入显示本地公式参数，但结果可能已被 AI 微调，链条对不上

**修改**：
- `UserInsightDto` 新增 `totalDelayMinutes`、`doneCount` 字段，`computeInsights()` 直接记录原始值
- `buildComputation()` 补全输入数据；AI 微调过的指标在 formula 中标注 `→ AI微调`，并在 inputs 中附加 `localResult` 展示本地原始值
- 前端 `ProfileView.vue` 新增 `totalDelayMinutes`、`localResult` 的中文标签和提示

### 15.16 Agent 对话增强（2026-06-09）

四项增强覆盖预热、工具可视化、动态上下文、工具缓存，后端 agent-service 与前端的 SSE 流处理均已实现。

#### 15.16.1 Agent 预热

**问题**：首个用户首次打开对话时，`ReactAgent` 需完整构建（创建 RedisSaver、加载 Hook、初始化工具回调），耗时 2-5 秒，体感卡顿。

**修改**：
- `AgentChatService.warmup(userId)`：通过 `CompletableFuture.runAsync(..., aiTaskExecutor)` 异步预构建 Agent，完成后缓存至 `ConcurrentHashMap`
- `AgentController` 新增 `POST /api/agent/warmup` 端点（需要 JWT 认证）
- 前端 `assistant.js` 在 `openChat()` 首次调用时 fire-and-forget 触发 `/api/agent/warmup`，不阻塞 UI

#### 15.16.2 工具调用可视化

**问题**：Agent 调用工具（如查询今日排程、拉取打卡记录）期间前端无任何反馈，用户只能等待文本出现，不清楚系统在做什么。

**修改**——后端标记：
- `AgentChatService.chatStream()` 的 `raw.handle()` 中检测 `OutputType.AGENT_TOOL_STREAMING`，emit `__SP_TOOL:CALL:工具信息__`
- `OutputType.AGENT_TOOL_FINISHED` 时 emit `__SP_TOOL:DONE__`
- `extractToolInfo()` 从 `StreamingOutput.chunk()` / `.message().getText()` 提取工具名

**修改**——前端拦截：
- `assistant.js` 新增 `toolStatus` 状态字段
- SSE 处理循环 `processSSE()` 中检查 chunk 前缀：`__SP_TOOL:CALL:` 开头的设置 `toolStatus`，`__SP_TOOL:DONE__` 清除 `toolStatus`，均不拼入显示文本
- `TOOL_FRIENDLY_NAME` 映射表将 10 个工具名转为中文描述（如 `listTodaySchedules` → "正在查询今日排程"），`mapToolName()` 解析原始工具信息（取第一个词作为工具名）并查表返回友好文案
- `finally` 块中同时清理 `toolStatus` 和 `chatLoading`

**修改**——前端 UI：
- `DefaultLayout.vue` 消息列表底部新增工具进度指示器：`v-progress-circular`（14px 旋转动画）+ 中文提示文字，仅在 `toolStatus` 非空时显示，固定在 AI 气泡下方，透明度 0.75 保持低调

#### 15.16.3 动态系统提示词

**问题**：Agent 的系统提示词是写死的，不知道用户当前状态（连续打卡天数、今日待完成数、本周完成率），导致回答空洞、缺少针对性。

**修改**：
- `AgentChatService` 注入 `PunchClient` + `ScheduleClient`
- 新增 `buildStatusPrefix(userId)`：每次 chat/chatStream 调用前实时查询：
  - 连续打卡天数（`punchClient.getStreak`）
  - 今日待完成排程数（`scheduleClient.listTaskSchedules` 当天，status != 1 计数）
  - 本周完成率（近 7 天排程中已完成的比例）
- 格式：`[当前状态] 连续打卡5天 | 今日待完成3项 | 本周完成率60%\n\n`
- 拼接在用户问题之前，模型可据此给出个性化建议。异常静默忽略，不阻塞对话

#### 15.16.4 工具结果缓存

**问题**：单次 Agent 对话中工具可能被重复调用（如 LLM 先调 `listTodaySchedules` 获取上下文，回答追问时又调一次），导致不必要的 Feign 远程调用延迟。

**修改**：
- `AgentChatService` 新增 `ThreadLocal<Map<String, String>> toolCache`，请求级别缓存
- `UserContextToolCallback` 改为内部类（非静态），新增 `callWithCache(toolInput)` 方法：以 `工具名:输入参数` 为 key，优先读缓存、缓存未命中时执行并写入
- `chat()` 在 `finally` 中清理，`chatStream()` 在 `doFinally` 中清理，确保不跨请求泄漏
- 典型场景收益：周总结场景（先调打卡再调随笔），追问"再详细说说"时跳过 2 次 Feign 调用

### 15.17 Agent 流式输出优化（2026-06-09）

#### 15.17.1 Markdown 实时渲染

**问题**：流式输出过程中只更新纯文本 `aiMsg.text`，`aiMsg.html` 在流结束后才设置，导致 `**加粗**`、代码块等格式化标记在流式过程中以原始语法显示，体验割裂。

**修改**：
- `assistant.js` 的 `flush()` 同步更新 `aiMsg.html = renderAiHtml(buf)`，利用模板 `v-html="m.html || m.text"` 实时渲染
- 新增未闭合格式标记平衡逻辑：流式 chunk 边界可能导致 `**`（bold）、`*`（italic）、`` ` ``（行内代码）被截断，渲染前检测奇数个标记并移除末尾未闭合的那个，下一 chunk 到达后完整格式正常渲染

#### 15.17.2 工具调用内联显示

**问题**：工具调用进度指示器（带转圈的 `v-progress-circular`）显示在消息列表底部，不够直观且转圈动画多余。

**修改**：
- 移除 `DefaultLayout.vue` 中底部独立进度条
- `processSSE()` 检测到 `__SP_TOOL:CALL:` 时，向 `buf` 插入带样式的 HTML 行：蓝色左边框 + 脉冲圆点 + 中文工具名
- 检测到 `__SP_TOOL:DONE__` 时直接移除该行，不留残留
- CSS 新增 `.sp-tool-call` 样式（左侧 3px 主色边框 + 浅色背景 + `sp-pulse` 圆点动画）
- `TOOL_FRIENDLY_NAME` 映射 10 个工具名到中文描述（如 `listTodaySchedules` → "正在查询今日排程"）

#### 15.17.3 原始 JSON 工具调用过滤

**问题**：qwen-max 模型在 ReAct 框架下偶发将工具调用以原始 JSON 文本输出（如 `{"name": "listTodaySchedules", "arguments": {}}`），而非通过框架正常调用，导致用户看到乱码。

**修改**：
- **后端**：`AgentChatService` 系统提示词新增 `严禁在回复文本中输出任何 JSON 格式的工具调用（如 {"name": "..."}），工具调用由系统内部处理`
- **前端兜底**：`processSSE()` 中 `chunk` 匹配正则 `/^\{"name"\s*:\s*"\w+"\s*,\s*"arguments"\s*:/` 时直接丢弃，不拼入显示文本

#### 15.17.4 代码块自动换行

**问题**：Agent 生成代码时 `<pre><code>` 默认 `white-space: pre`，长代码行产生水平滚动条。

**修改**：`DefaultLayout.vue` 新增 `<pre>` 和 `<code>` 样式：
- `white-space: pre-wrap` — 保留缩进同时允许自动换行
- `word-break: break-word` — 长单词/路径可断行
- `max-width: 100%` — 不超出气泡容器
- `overflow-x: auto` — 极窄屏兜底滚动条
- 添加内边距和圆角，深色/浅色主题下均可读

### 15.18 Agent 预热时机优化（2026-06-09）

**问题**：Agent 预热在 `openChat()`（用户点击打开聊天窗口时）触发，若用户快速发送第一条消息，Agent 可能尚未构建完成，首次对话仍需等待 2-5 秒。

**修改**：
- 预热调用从 `openChat()` 移至 `init()`（页面加载、用户登录后立即触发）
- 从登录到用户输入第一条消息通常有数秒到数十秒间隔，Agent 大概率已就绪
- `assistant.js` 中 `openChat()` 恢复为简单的状态切换，不再包含预热逻辑

### 15.19 目标拆解动画面板（2026-06-09）

**问题**：用户提交目标 AI 拆解后，仅有一条 toast 提示"AI任务拆解已完成"，等待过程无视觉反馈。

**修改**——后端：
- `GoalAiWorker.handleGoalAiTask()` 新增两个中间通知：LLM 调用前发送 `GOAL_DECOMPOSE_STARTED`，解析任务后发送 `GOAL_DECOMPOSE_PROGRESS`（含任务标题列表）
- 新增 `sendDecomposeProgress()` 辅助方法，通过现有 RabbitMQ → SSE 管道推送
- 通知 payload 包含 `goal`、`taskCount`、`taskTitles` 字段

**修改**——前端：
- 新建 `stores/decompose.js`（Pinia store）：管理五阶段流水线状态（intent → llm → saving → resources → done），任务列表自动逐条揭示（220ms 间隔），`onTasksGenerated()` 立即设任务数据 + 800ms/1600ms 分阶段推进动画，`onAllDone()` 取消未完成计时器并快速收尾（400ms × 2），5 秒自动消失
- 新建 `components/DecomposePanel.vue`：玻璃拟态浮动面板（右上角 top:80px），五阶段纵向步骤条 —— pending（灰色节点）、active（主色节点 + 发光脉冲动画 + "进行中"标签）、done（绿色对勾），节点间连接线随进度变色，任务列表在生成阶段逐条从右侧滑入（TransitionGroup），完成后显示任务总数 + 关闭按钮
- `DefaultLayout.vue`：新增 `GOAL_DECOMPOSE_STARTED`、`GOAL_DECOMPOSE_PROGRESS`、`GOAL_DECOMPOSE_SAVING` SSE 监听器，`GOAL_TASK_READY` 调用 `decompose.onAllDone()`
- `PlanView.vue`：`createGoalByAi()` 成功后调用 `decompose.start(goalText)`
- `GoalsView.vue`：`regenerateTasksForGoal()` 调用前触发 `decompose.start(goalTitle)`

**Bug 修复**——后端：
- `GOAL_DECOMPOSE_PROGRESS` 的 `taskTitles` 计算挪到兜底逻辑之后，修复 AI 返回结果被 `sanitizeTasks` 全过滤后 PROGRESS 事件携带 `taskCount=0` 的问题

### 15.20 PlanView 向导加载性能优化（2026-06-09）

**问题**：PlanView 学习计划页 `initWizard()` 串行调用 `fetchMe` → `dashboard` → `goals` → `tasks`，其中 `/user/dashboard` 跨 5 个微服务（goal/schedule/punch/resource），且 `buildGoalProgress` 对每个 goal 串行查询 tasks（N 个 goal = N 次网络往返）。整个页面被 `initializing` loading 条阻塞直到所有 API 返回。

**修改**——前端：
- `initializing = false` 立即执行：wizard 页面秒开，不再等待任何数据
- 删除 `api.get('/user/dashboard')` 调用：该接口在 PlanView 中从未被读取（`dashboard.value` 仅赋值无消费）
- Wizard 步骤从 localStorage 直接恢复（零延迟）
- goals 列表用 `.then()` 异步加载，到达后自动填充当前 goal 和 tasks
- `pollTasksUntilReady` 从 `await` 改为 fire-and-forget，后台轮询不阻塞页面渲染
- 跳过 `auth.fetchMe()`（`auth.me` 登录时已设置）

**修改**——后端：
- `UserService.buildGoalProgress()`：每个 goal 的任务查询从串行改为 `CompletableFuture.supplyAsync` 并行（N 个 goal = 1 次网络往返）

### 15.21 ES 向量语义检索 + 入库质量过滤（2026-06-09）

**问题**：`searchFromEs()` 使用 `multiMatch` 纯文本匹配（BM25），无法语义关联（"机器学习"搜不到"深度学习"）。B站爬虫无内容过滤，"机器学习"15条全是汽车评测。AI 生成资源写入 DB（`persistAiResources` / `upsertFromModel`）污染数据库。

**修改**（第一轮）：
- **新增 `ResourceAiConfig`**（`resource-search/.../config/ResourceAiConfig.java`）：创建 `DashScopeApi` + `EmbeddingModel`（`text-embedding-v2`，1536维）Bean
- **`CourseResourceDocument` 加 `dense_vector` 字段**：`float[] embedding`，1536维，`@JsonInclude(NON_NULL)`
- **`searchFromEs()` 重写为 kNN 向量检索优先**
- **写入路径自动生成 embedding**：`upsertToEs()` / `saveIfNew()` 写 ES 前生成向量
- **`ResourceSearchIndexInitializer` 索引迁移**：启动时检测并重建含 `dense_vector` 的索引
- **移除 AI→DB 写入**：`persistAiResources()` / `upsertFromModel()` 已删除
- **入库质量过滤**：bigram 相似度 + CJK 字符匹配，可配开关

**修改**（第二轮——kNN 修复 + 质量增强）：
- **kNN 反序列化修复**：ES Java Client 8.10 的 `SearchResponse<CourseResourceDocument>` 无法解码 kNN 响应（`Failed to decode response`，状态码 200）。改用 `org.elasticsearch.client.RestClient` 发送原生 kNN JSON 请求，通过 `ObjectMapper` 手动解析 `_source`，`_source` 过滤排除 embedding 字段减少传输体积
- **标题质量门禁**（`isValidTitle`）：正则匹配纯哈希值（`HEX_HASH` 32位+）、哈希后缀（`HEX_HASH_SUFFIX` `_16位hex`）、纯数字、%开头、CJK主题下无汉字的过长英文标题
- **多查询词扩展**（`buildSearchQueries`）：CJK 主题自动拼接后缀（`教程`/`入门`/`基础`，`bilibili.query-suffixes` 可配），多个查询词独立请求 B站 API（间隔 400ms），扩充候选池后统一质量过滤
- **相似度阈值收紧**：CJK 0.03→0.06，非 CJK 0.08→0.10，减少误判通过
- **兜底平台仅限国内**：`defaultResources()` 和 AI prompt 移除 Google/Coursera/edX/Medium，替换为 B站/慕课网/知乎/GitHub 搜索链接
- **每主题抓取量**：从 3 条提升至 8 条（`per-topic-limit`），配合多查询词扩展确保质量过滤后仍有足够候选

**测试结果**（2026-06-09）：
- kNN 语义搜索正常："深度学习" → "Python零基础教程…AI人工智能必备" ✅，"Java" → 19条 Java 相关资源 ✅
- 质量过滤器实战验证：
  - 标题门禁成功拦截：`8b549e65424853c005a4f0ce2a0eac38.H_40_3.mp4_20260609202856`（哈希后缀）、`a7452e0389cdb2c9a7ba1d8cdfe6047a`（纯哈希）、`%E5%82%B2%E9%A3%8E...`（URL编码垃圾）
  - 内容过滤成功拦截："B3 2 6.9"、"东哥Y2JB更新"、"Odyssey JAILBREAK RELEASED"、"奥迪a6l"
- 全链路通畅：B站 API → 多查询词 → 标题门禁 → 内容过滤 → MySQL → embedding → ES → kNN 检索 ✅
- **已知限制**：Docker 容器 IP 访问 B站时，部分中文主题（如"数据结构"）返回的 Top 8 结果中掺杂大量哈希文件名视频，质量过滤器正确拦截了这些垃圾，但也导致该主题 0 条入库。英文字母主题（Java/Python/Go/Rust 等）不受影响，正常入库
