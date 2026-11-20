# AI 增强型多场景智能客服（AISmartCustomerService）

基于 **SpringBoot 3 + SpringAI + 阿里云百炼（DashScope）** 的大模型应用：多轮对话、RAG 知识库问答、Function Calling 工具调用、**单 Agent 编排**、文生图、审计日志，前端 Vue3 + Element Plus 提供流式对话与执行过程可视化。

---

## 一、能力线与代码位置

| 能力线 | 实现要点 | 代码 |
|---|---|---|
| 统一接入层 | 模型、系统提示词、Advisor 链一次性装配；新增场景只加组件 | `config/ChatClientConfig.java` |
| 多轮对话 / 记忆 | `ChatMemory` + 可插拔存储（内存 / 文件 / Redis） | `memory/*`、`config/MemoryConfig.java` |
| RAG 知识库问答 | 切片 → EmbeddingModel → 向量库 → 命中片段注入 Prompt → 片段回传前端 | `rag/RagService.java` |
| Function Calling | `@Tool` / `@ToolParam` 声明式注册，模型自主选工具、抽参数 | `tool/*`、`agent/AgentToolRegistry.java` |
| **单 Agent 编排** | 计划 → 循环决策 → 观察 → 自检 → 生成；三重预算；轨迹回放 | `agent/AgentOrchestrator.java` |
| 评测 | 9 条固定用例，按关键词/工具/召回三项判定，输出通过率与平均步数 | `agent/EvalRunner.java`、`resources/agent/eval-cases.jsonl` |
| 文生图 | 通义万相 `ImageModel` | `controller/ImageController.java` |
| 审计日志 | 自定义 `BaseAdvisor` 拦截请求/响应，业务零侵入、可开关 | `advisor/AuditLogAdvisor.java` |
| SSE 流式 | 命名事件 `plan/step/sources/delta/done/error` | `controller/AiChatController.java#stream`、`AgentController.java#runStream` |

## 二、技术栈

- 后端：Java 17、SpringBoot 3.2.5、SpringAI 1.0.0-M6、spring-ai-alibaba 1.0.0-M6.1、Maven 父子工程（BOM 锁版本）
- 模型：通义千问 `qwen-plus`、`text-embedding-v3`、通义万相 `wanx2.1-t2i-turbo`
- 存储：SimpleVectorStore（向量库，JSON 落盘）、会话存储三选一（内存 / 文件 / Redis）
- 前端：Vue 3.5、Vite、Element Plus、fetch + ReadableStream 手写 SSE 解析

## 三、目录结构

```
AISmartCustomerService
├── pom.xml                      # 父工程：BOM 锁版本，子模块不写 version
└── springai-server
    ├── pom.xml
    └── src/main
        ├── java/com/mdkj
        │   ├── AiServiceApplication.java
        │   ├── agent/                       # ← 单 Agent 编排
        │   │   ├── AgentOrchestrator.java   #   计划 / 决策 / 自检 / 预算收敛
        │   │   ├── AgentToolRegistry.java   #   把 @Tool 收进 Map，供编排器显式调用
        │   │   ├── AgentTrace.java          #   轨迹（问题/计划/步骤/答案/用量）
        │   │   ├── AgentTraceStore.java     #   内存 LRU + 落盘 JSON，支持回放
        │   │   ├── AgentStep / AgentTask / AgentAction / AgentOutcome / AgentResult
        │   │   ├── EvalRunner / EvalCase / EvalReport
        │   │   └── JsonUtils.java           #   模型输出的 JSON 容错解析
        │   ├── advisor/AuditLogAdvisor.java
        │   ├── config/{AiProperties,ChatClientConfig,MemoryConfig,RagConfig}.java
        │   ├── controller/{AiChatController,ImageController,RagController,AgentController}.java
        │   ├── dto/*.java
        │   ├── memory/*.java
        │   ├── rag/*.java
        │   ├── support/{ApiError,GlobalExceptionHandler}.java
        │   └── tool/{WeatherTools,ExpressTools}.java
        └── resources
            ├── application.yml
            ├── agent/eval-cases.jsonl       # 评测用例（JSONL）
            └── docs/*.md                    # 内置知识库，启动自动入库
└── springai-web
    ├── vite.config.js                       # /api 代理到 5501
    └── src/{App.vue, main.js, api/aiApi.js}
```

## 四、快速开始

**环境要求**：JDK 17+（实测 JDK 21 可用）、Maven 3.8+、**Node.js 20.19+ 或 22.12+**。

> Node 版本是硬门槛：`package.json` 锁定的 `rolldown-vite@7.1.14` 声明 `engines.node` 为 `^20.19.0 || >=22.12.0`。Node 14/16 下执行 `npm run dev` 会直接抛 `SyntaxError: Unexpected token '??='`，无法启动。
> 前端依赖请在 `springai-web` 目录内安装，不要装到仓库根目录。

```powershell
# 1. 设 Key（只放环境变量，不要提交）
$env:DASHSCOPE_API_KEY="sk-你的百炼Key"

# 2. 后端：IDEA 运行 AiServiceApplication（端口 5501）
mvn -q -pl springai-server compile

# 3. 前端
cd springai-web; npm install; npm run dev      # http://localhost:8580
```

首次启动会把 `resources/docs/*.md` 自动切片入库，日志可见 `[rag] 入库《shipping.md》…`。

> 需要 `mvn spring-boot:run` 或可执行 jar 时，自行在 `springai-server/pom.xml` 加 `spring-boot-maven-plugin`（仓库里未加，避免离线环境解析不到插件）。
> Windows 控制台中文日志乱码是代码页（GBK）与 JVM UTF-8 不一致，`chcp 65001` 或用 IDEA 运行即可，不影响文件内容。

## 五、接口一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/ai/chat` | 一次性问答（返回 sources、token、耗时） |
| POST | `/api/v1/ai/chat/stream` | SSE 流式问答：`sources/delta/done/error` |
| GET/DELETE | `/api/v1/ai/conversations/{id}[/messages]` | 读历史 / 清空会话 |
| POST | `/api/v1/image` | 文生图（通义万相） |
| POST/GET/DELETE | `/api/v1/rag/documents` | 入库 / 列表 / 清空 |
| GET | `/api/v1/rag/search?query=&topK=` | 只检索不调模型，定位偏差来源 |
| **POST** | **`/api/v1/agent/run`** | **Agent 非流式执行，一次返回完整轨迹** |
| **POST** | **`/api/v1/agent/run/stream`** | **Agent 流式执行：`plan/step/sources/delta/done/error`** |
| GET | `/api/v1/agent/tools` | 工具清单（含 JSON Schema） |
| GET | `/api/v1/agent/traces?limit=` | 最近轨迹摘要 |
| GET | `/api/v1/agent/traces/{traceId}` | 单条轨迹回放（每步输入输出） |
| GET | `/api/v1/agent/eval/cases` | 评测用例清单 |
| POST | `/api/v1/agent/eval?limit=` | 跑评测，返回通过率与平均步数 |

```bash
# Agent 跑一轮（看它怎么想）
curl -N -X POST http://localhost:5501/api/v1/agent/run/stream \
  -H "Content-Type: application/json" \
  -d '{"conversationId":"demo-1","message":"哈尔滨今天要带伞吗，顺便看下我买的快递到哪了","rag":false}'
```

## 六、Agent 执行流程

```
用户问题
  ├─ 检索（可选）          命中片段 → 证据 + 回传前端
  ├─ 计划                 拆 1~3 个子任务，标注预期工具
  ├─ 循环（≤6 步）
  │    ├─ 决策            调工具 / 直接作答 / 主动结束
  │    ├─ 工具调用        失败不终止：错误作为观察结果喂回模型，让它换参数
  │    └─ 观察            结果进 scratchpad（截断 500 字，控制 token）
  ├─ 自检                 草稿是否有证据支撑；不通过则把问题写进最终提示词强制修正
  └─ 生成                 流式输出答案 + 引用
```

**三重预算**：最大步数 6、最大工具调用 4、总超时 60s。任一触发即停止循环，并把"为什么提前收敛"写进最终提示词，要求模型如实说明信息不全，而不是硬答。

**两个 ChatClient**：规划/决策/自检走不带会话记忆的 `internalClient`（否则中间过程会污染用户会话历史），只有最终答案走统一接入层的 `userFacingClient`，从而复用系统提示词、审计日志与会话记忆。

## 七、配置说明

| 配置 | 默认 | 说明 |
|---|---|---|
| `ai.audit-enabled` | `true` | 审计日志总开关 |
| `ai.memory.store` | `memory` | `memory` / `file` / `redis`；redis 连不上自动降级为 file |
| `ai.memory.window` | `20` | 注入历史条数上限 |
| `ai.rag.top-k` | `4` | 检索片段数 |
| `ai.rag.similarity-threshold` | `0.45` | 相似度阈值 |
| `ai.agent.max-steps` | `6` | 单轮决策步数上限 |
| `ai.agent.max-tool-calls` | `4` | 单轮工具调用上限 |
| `ai.agent.deadline-ms` | `60000` | 单轮总超时 |
| `ai.agent.critic` | `true` | 是否做回答自检 |
| `ai.agent.trace-dir` | `./data/agent-traces` | 轨迹落盘目录 |
| `ai.agent.eval-file` | `classpath:agent/eval-cases.jsonl` | 评测用例 |

## 八、评测怎么用

用例是 JSONL，一行一条，判定刻意做成确定性的（字符串包含 / 轨迹检查），不引入 LLM 当裁判：

```json
{"id":"tool-2","question":"帮我查一下快递 SF1234567890 到哪了","rag":false,"expectTool":"queryExpress"}
```

```bash
curl -X POST "http://localhost:5501/api/v1/agent/eval?limit=5"
# {"total":5,"passed":4,"passRate":0.8,"avgSteps":3.4,"avgCostMs":2140,"cases":[...]}
```

指标：通过率、平均步数、平均耗时、每条失败原因。**这组数字才是简历里"Agent 效果"的依据**，比形容词可靠。

## 九、几个设计取舍

1. **Agent 循环自己写，不用框架的 tools() 单跳。** Spring AI 1.0.0-M6 没有 Agent 运行时，`chatClient.prompt().tools(...)` 只做一轮"模型要工具→执行→再问"；多步决策、预算、终止条件、失败重试必须由业务代码驱动，否则谈不上编排。
2. **RAG 不用 `QuestionAnswerAdvisor`，改控制层显式编排。** Advisor 的 `adviseContext` 不跨 call/stream 暴露给控制层，拿不到命中片段就没法把 `sources` 回传做溯源。
3. **SSE 用命名事件而不是哨兵字符串。** `[over]` 这类哨兵一旦被模型自己输出就会误判断流；命名事件还能承载 plan/step 这类结构化过程。
4. **流式用 fetch + ReadableStream，不用 EventSource。** EventSource 只能 GET，问题必须拼在 URL 上，长文本会超长度上限，也无法取消。
5. **自写极简 Redis 客户端（RESP）而非引 starter。** 让工程在只有 JDK + Maven 缓存的环境里能直接编译运行；上生产换 `RedisTemplate` 只需替换 `SimpleRedisClient` 一层。
6. **`maven-compiler-plugin` 必须带 `-parameters`。** `@Tool`/`@ToolParam` 生成的 JSON Schema 依赖方法参数名，缺了这个开关参数名会退化成 `arg0/arg1`，模型无法按名字填参（踩过，已修）。

## 十、验证情况（如实记录）

- ✅ `javac` 编译通过：46 个类（含 Agent 层 18 个）。
- ✅ 启动通过：Tomcat 5501，ChatClient / ChatMemory / SimpleVectorStore / 4 个 Controller / 编排器均正常注入。
- ✅ SSE 双链路实测输出 `text/event-stream` 且事件帧正确；Agent 流事件顺序为 `step → plan → step… → sources → error/done`。
- ✅ 预算收敛实测：无有效 Key 时决策连续失败，6 步后停止并落 `status=failed` 轨迹，未死循环。
- ✅ 工具 Schema 实测参数名为 `city/date`、`trackingNo`（`-parameters` 生效）。
- ✅ 轨迹回放实测：`GET /agent/traces/{id}` 返回 8 步明细。
- ✅ 评测接口实测：无 Key 时返回 200 且用例记为失败（`执行异常：401`），不会 500。
- ✅ 前端 SFC 用 `@vue/compiler-sfc` 编译通过（56 个绑定，模板可渲染）。
- ⚠️ **真实对话 / 检索 / 出图 / Agent 完整闭环未验证**：运行环境无有效 `DASHSCOPE_API_KEY`。设 Key 后请依次验证 `/chat`、`/chat/stream`、`/rag/search`、`/image`、`/agent/run`、`/agent/eval`。
- ⚠️ 流式链路依赖 WebClient 的 HttpClient 连接器：缺少 reactor-netty 时 Boot 自动选 JDK HttpClient；若报 `WebClient` 相关错误，加 `spring-boot-starter-webflux` 即可。

## 十一、还能继续做

- 工具调用与召回来源写入审计日志（当前日志只有 token 与耗时）
- 危险工具（退款、改地址）增加人工确认节点，形成 human-in-the-loop
- 评测接入 CI，每次改动跑一遍用例集并对比通过率
- 会话级限流（Redis 令牌桶），防止 Key 被刷
- 知识库支持上传文件（当前是粘贴文本）