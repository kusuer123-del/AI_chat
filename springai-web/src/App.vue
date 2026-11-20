<script setup>
import {nextTick, onMounted, reactive, ref} from 'vue'
import {ElMessage, ElMessageBox} from 'element-plus'
import {
  chat,
  clearConversation,
  clearDocuments,
  generateImage,
  ingestDocument,
  listDocuments,
  loadHistory,
  runEval,
  searchDocuments,
  streamAgent,
  streamChat
} from './api/aiApi'

const GREETING = '你好，我是云栖优选的智能客服小云。可以问我配送、退换货、会员权益，也能查天气和快递，或者点「知识库」维护我用的资料。'

const conversationId = ref(newConversationId())
const messages = ref([{role: 'assistant', content: GREETING}])
const input = ref('')
const sending = ref(false)
const streamEnabled = ref(true)
const ragEnabled = ref(true)
const toolsEnabled = ref(true)
const imageMode = ref(false)
const agentMode = ref(false)
const evalRunning = ref(false)
const lastMeta = ref(null)
const listRef = ref(null)
let controller = null

const drawerVisible = ref(false)
const docTitle = ref('')
const docContent = ref('')
const documents = ref([])
const stats = reactive({totalDocuments: 0, totalChunks: 0})
const hits = ref([])
const previewing = ref(false)

function newConversationId() {
  return Math.random().toString(16).slice(2, 10) + Date.now().toString(16).slice(-8)
}

function scrollToEnd() {
  nextTick(() => {
    const element = listRef.value
    if (element) {
      element.scrollTop = element.scrollHeight
    }
  })
}

async function send() {
  const text = input.value.trim()
  if (!text || sending.value) {
    return
  }
  input.value = ''
  messages.value.push({role: 'user', content: text})
  const reply = {role: 'assistant', content: '', loading: true, sources: []}
  messages.value.push(reply)
  sending.value = true
  lastMeta.value = null
  scrollToEnd()

  try {
    if (imageMode.value) {
      await sendImage(text, reply)
    } else if (agentMode.value) {
      await sendAgent(text, reply)
    } else if (streamEnabled.value) {
      await sendStream(text, reply)
    } else {
      await sendOnce(text, reply)
    }
  } catch (error) {
    if (error.name === 'AbortError') {
      reply.content = reply.content || ''
      reply.content += '\n\n[已停止生成]'
    } else {
      reply.content = reply.content || `抱歉，出了点问题：${error.message}`
      ElMessage.error(error.message)
    }
  } finally {
    reply.loading = false
    sending.value = false
    controller = null
    scrollToEnd()
  }
}

async function sendStream(text, reply) {
  controller = new AbortController()
  reply.loading = false
  await streamChat(
      {
        conversationId: conversationId.value,
        message: text,
        rag: ragEnabled.value,
        tools: toolsEnabled.value
      },
      {
        onSources: (list) => {
          reply.sources = list
          scrollToEnd()
        },
        onDelta: (chunk) => {
          reply.content += chunk
          scrollToEnd()
        },
        onDone: (payload) => {
          lastMeta.value = payload
          if (payload?.conversationId) {
            conversationId.value = payload.conversationId
          }
        },
        onError: (message) => {
          reply.content += `\n\n[生成中断] ${message}`
        }
      },
      controller.signal
  )
}

/** Agent 模式：实时展示计划与每一步执行，最后流式输出答案。 */
async function sendAgent(text, reply) {
  controller = new AbortController()
  reply.loading = false
  reply.steps = []
  reply.tasks = []
  await streamAgent(
      {
        conversationId: conversationId.value,
        message: text,
        rag: ragEnabled.value
      },
      {
        onPlan: (tasks) => {
          reply.tasks = tasks
          scrollToEnd()
        },
        onStep: (step) => {
          reply.steps.push(step)
          scrollToEnd()
        },
        onSources: (list) => {
          reply.sources = list
        },
        onDelta: (chunk) => {
          reply.content += chunk
          scrollToEnd()
        },
        onDone: (payload) => {
          reply.traceId = payload.traceId
          lastMeta.value = {
            latencyMs: payload.costMs,
            promptTokens: payload.promptTokens,
            completionTokens: payload.completionTokens
          }
        },
        onError: (message) => {
          reply.content += `\n\n[执行中断] ${message}`
        }
      },
      controller.signal
  )
}

async function sendOnce(text, reply) {
  const result = await chat({
    conversationId: conversationId.value,
    message: text,
    rag: ragEnabled.value,
    tools: toolsEnabled.value
  })
  reply.content = result.content || '（模型没有返回内容）'
  reply.sources = result.sources || []
  conversationId.value = result.conversationId || conversationId.value
  lastMeta.value = {
    latencyMs: result.latencyMs,
    promptTokens: result.promptTokens,
    completionTokens: result.completionTokens
  }
}

async function sendImage(text, reply) {
  reply.loading = false
  reply.content = '正在生成图片…'
  scrollToEnd()
  const result = await generateImage({prompt: text})
  reply.content = ''
  reply.image = result.url
      || (result.b64Json ? `data:image/png;base64,${result.b64Json}` : '')
  if (!reply.image) {
    reply.content = '接口没有返回图片地址，检查一下万相模型是否开通。'
  }
}

function stop() {
  if (controller) {
    controller.abort()
  }
}

async function startNewConversation() {
  if (sending.value) {
    stop()
  }
  try {
    await clearConversation(conversationId.value)
  } catch (ignored) {
    // 旧会话可能本来就不存在，忽略
  }
  conversationId.value = newConversationId()
  messages.value = [{role: 'assistant', content: GREETING}]
  lastMeta.value = null
  ElMessage.success('已开启新会话')
}

async function restoreHistory() {
  try {
    const history = await loadHistory(conversationId.value)
    if (Array.isArray(history) && history.length) {
      messages.value = history.map(item => ({role: item.role, content: item.content}))
      scrollToEnd()
    }
  } catch (ignored) {
    // 后端没启动时静默失败，页面仍然可用
  }
}

function openDrawer() {
  drawerVisible.value = true
  refreshDocuments()
}

async function refreshDocuments() {
  try {
    const result = await listDocuments()
    documents.value = result.documents || []
    stats.totalDocuments = result.totalDocuments || 0
    stats.totalChunks = result.totalChunks || 0
  } catch (error) {
    ElMessage.error(error.message)
  }
}

async function submitDocument() {
  if (!docTitle.value.trim() || !docContent.value.trim()) {
    ElMessage.warning('标题和内容都要填')
    return
  }
  try {
    const result = await ingestDocument({title: docTitle.value.trim(), content: docContent.value.trim()})
    ElMessage.success(`《${result.title}》入库 ${result.chunkCount} 个切片`)
    docTitle.value = ''
    docContent.value = ''
    await refreshDocuments()
  } catch (error) {
    ElMessage.error(error.message)
  }
}

async function removeAllDocuments() {
  try {
    await ElMessageBox.confirm('清空后需要重新入库，确定吗？', '清空知识库', {type: 'warning'})
  } catch (ignored) {
    return
  }
  try {
    const result = await clearDocuments()
    ElMessage.success(`已清空 ${result.removedDocuments} 篇`)
    hits.value = []
    await refreshDocuments()
  } catch (error) {
    ElMessage.error(error.message)
  }
}

async function previewRecall() {
  const query = input.value.trim()
  if (!query) {
    ElMessage.warning('先在输入框写一句要检索的话')
    return
  }
  previewing.value = true
  try {
    hits.value = await searchDocuments(query, 4)
    if (!hits.value.length) {
      ElMessage.info('没有召回片段')
    }
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    previewing.value = false
  }
}

async function runEvaluation() {
  evalRunning.value = true
  try {
    const report = await runEval(5)
    const lines = [
      `通过 ${report.passed}/${report.total}（${(report.passRate * 100).toFixed(0)}%）`,
      `平均步数 ${report.avgSteps.toFixed(1)} · 平均耗时 ${report.avgCostMs}ms`,
      ...report.cases.map(item =>
          `${item.passed ? '✓' : '✗'} ${item.id}${item.reasons.length ? '：' + item.reasons.join('；') : ''}`)
    ]
    await ElMessageBox.alert(lines.join('<br/>'), '评测结果（前 5 条）', {dangerouslyUseHTMLString: true})
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    evalRunning.value = false
  }
}

function stepTagType(type) {
  if (type === 'tool') {
    return 'success'
  }
  if (type === 'error') {
    return 'danger'
  }
  if (type === 'critic') {
    return 'warning'
  }
  if (type === 'plan') {
    return 'primary'
  }
  return 'info'
}

function metaText(meta) {
  if (!meta) {
    return ''
  }
  const parts = []
  if (meta.firstTokenMs != null) {
    parts.push(`首字 ${meta.firstTokenMs}ms`)
  }
  if (meta.latencyMs != null) {
    parts.push(`总耗时 ${meta.latencyMs}ms`)
  }
  if (meta.promptTokens != null) {
    parts.push(`入 ${meta.promptTokens} tok`)
  }
  if (meta.completionTokens != null) {
    parts.push(`出 ${meta.completionTokens} tok`)
  }
  return parts.join(' · ')
}

onMounted(restoreHistory)
</script>

<template>
  <div class="page">
    <header class="header">
      <div class="brand">
        <span class="logo">云栖</span>
        <div>
          <h1>AI 智能客服</h1>
          <p>SpringBoot 3 · SpringAI · 阿里云百炼 · RAG · Function Calling</p>
        </div>
      </div>
      <div class="header-actions">
        <el-tag type="info" effect="plain" size="small">会话 {{ conversationId }}</el-tag>
        <el-button size="small" :loading="evalRunning" @click="runEvaluation">跑评测</el-button>
        <el-button size="small" @click="openDrawer">知识库</el-button>
        <el-button size="small" type="primary" plain @click="startNewConversation">新会话</el-button>
      </div>
    </header>

    <section class="controls">
      <el-switch v-model="streamEnabled" active-text="流式" inactive-text="一次性" :disabled="imageMode"/>
      <el-switch v-model="ragEnabled" active-text="检索增强" :disabled="imageMode"/>
      <el-switch v-model="toolsEnabled" active-text="工具调用" :disabled="imageMode"/>
      <el-switch v-model="agentMode" active-text="Agent 编排" :disabled="imageMode"/>
      <el-switch v-model="imageMode" active-text="文生图" inactive-text="对话" active-color="#b88230"/>
      <span class="hint">关掉「检索增强」就是无检索对照链路；打开「Agent 编排」会先拆任务再逐步执行</span>
    </section>

    <main class="timeline" ref="listRef">
      <div v-for="(message, index) in messages" :key="index" class="row" :class="message.role">
        <div class="avatar">{{ message.role === 'user' ? '我' : 'AI' }}</div>
        <div class="bubble">
          <div v-if="message.loading" class="typing">正在思考…</div>
          <div v-if="message.steps && message.steps.length" class="steps">
            <el-collapse>
              <el-collapse-item :title="`执行过程 ${message.steps.length} 步`" :name="index">
                <div v-for="step in message.steps" :key="step.index" class="step-row">
                  <el-tag size="small" effect="plain" :type="stepTagType(step.type)">{{ step.type }}</el-tag>
                  <div class="step-body">
                    <div class="step-title">{{ step.title }}
                      <span class="step-cost" v-if="step.costMs">{{ step.costMs }}ms</span>
                    </div>
                    <div class="step-detail" v-if="step.detail">{{ step.detail }}</div>
                    <div class="step-detail" v-if="step.output">{{ step.output }}</div>
                  </div>
                </div>
                <div class="step-foot" v-if="message.traceId">trace：{{ message.traceId }}</div>
              </el-collapse-item>
            </el-collapse>
          </div>
          <p v-if="message.content" class="text">{{ message.content }}</p>
          <img v-if="message.image" :src="message.image" alt="生成的图片" class="image"/>
          <div v-if="message.sources && message.sources.length" class="sources">
            <el-tag v-for="(source, sourceIndex) in message.sources" :key="sourceIndex"
                    size="small" type="success" effect="plain" class="source-tag"
                    :title="source.snippet">
              [{{ sourceIndex + 1 }}] {{ source.title }}
            </el-tag>
          </div>
        </div>
      </div>
    </main>

    <footer class="composer">
      <div class="meta" v-if="metaText(lastMeta)">{{ metaText(lastMeta) }}</div>
      <el-input
          v-model="input"
          type="textarea"
          :rows="2"
          resize="none"
          maxlength="2000"
          show-word-limit
          :placeholder="imageMode ? '描述画面，例如：樱花下的木质日式杂货铺，暖色调' : '输入问题，Enter 发送，Shift+Enter 换行'"
          @keydown.enter.exact.prevent="send"/>
      <div class="composer-actions">
        <el-button v-if="sending" type="warning" plain @click="stop">停止</el-button>
        <el-button type="primary" :loading="sending" @click="send">
          {{ imageMode ? '生成图片' : '发送' }}
        </el-button>
      </div>
    </footer>

    <el-drawer v-model="drawerVisible" title="知识库" size="460px" @open="refreshDocuments">
      <div class="doc-stats">
        共 {{ stats.totalDocuments }} 篇 / {{ stats.totalChunks }} 个切片
      </div>

      <el-input v-model="docTitle" placeholder="文档标题，例如：配送时效说明" size="default"/>
      <el-input v-model="docContent" type="textarea" :rows="6" class="doc-content"
                placeholder="粘贴文档正文，入库时会自动切片并向量化"/>
      <div class="doc-actions">
        <el-button type="primary" @click="submitDocument">入库</el-button>
        <el-button :loading="previewing" @click="previewRecall">检索预览</el-button>
        <el-button type="danger" plain @click="removeAllDocuments">清空</el-button>
      </div>

      <el-divider content-position="left">已入库</el-divider>
      <div v-if="!documents.length" class="empty">还没有文档，先入库一份试试</div>
      <div v-for="doc in documents" :key="doc.title" class="doc-item">
        <div class="doc-title">{{ doc.title }}</div>
        <div class="doc-meta">{{ doc.chunkCount }} 个切片 · {{ doc.createdAt }}</div>
      </div>

      <template v-if="hits.length">
        <el-divider content-position="left">召回片段</el-divider>
        <div v-for="(hit, index) in hits" :key="index" class="hit">
          <div class="doc-title">[{{ index + 1 }}] {{ hit.title }}
            <span class="score">相似度 {{ hit.score?.toFixed(3) }}</span>
          </div>
          <p class="hit-text">{{ hit.snippet }}</p>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  height: 100vh;
  max-width: 960px;
  margin: 0 auto;
  padding: 16px 20px;
  box-sizing: border-box;
  font-family: system-ui, -apple-system, "Segoe UI", sans-serif;
  background: #f6f7fb;
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid #e6e8ef;
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
}

.logo {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  color: #fff;
  font-weight: 700;
  background: linear-gradient(135deg, #3b82f6, #6366f1);
}

.header h1 {
  margin: 0;
  font-size: 18px;
}

.header p {
  margin: 2px 0 0;
  font-size: 12px;
  color: #8a8f9c;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.controls {
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 12px 4px;
  flex-wrap: wrap;
}

.hint {
  font-size: 12px;
  color: #a0a4b0;
}

.timeline {
  flex: 1;
  overflow-y: auto;
  padding: 8px 4px 16px;
}

.row {
  display: flex;
  gap: 10px;
  margin-bottom: 16px;
}

.row.user {
  flex-direction: row-reverse;
}

.avatar {
  width: 32px;
  height: 32px;
  flex: none;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-size: 12px;
  color: #fff;
  background: #6366f1;
}

.row.user .avatar {
  background: #3b82f6;
}

.bubble {
  max-width: 76%;
  padding: 10px 14px;
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 1px 3px rgba(20, 24, 40, 0.08);
}

.row.user .bubble {
  background: #3b82f6;
  color: #fff;
}

.text {
  margin: 0;
  white-space: pre-wrap;
  line-height: 1.65;
  font-size: 14px;
}

.typing {
  color: #98a1b3;
  font-size: 13px;
}

.steps {
  margin-bottom: 8px;
}

.step-row {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  padding: 6px 0;
  border-bottom: 1px dashed #eef0f5;
}

.step-body {
  flex: 1;
  min-width: 0;
}

.step-title {
  font-size: 13px;
  color: #3d4353;
  font-weight: 500;
}

.step-cost {
  font-size: 11px;
  color: #a0a4b0;
  margin-left: 6px;
  font-weight: 400;
}

.step-detail {
  font-size: 12px;
  color: #8a8f9c;
  line-height: 1.5;
  margin-top: 2px;
  word-break: break-all;
}

.step-foot {
  font-size: 11px;
  color: #a0a4b0;
  padding-top: 6px;
}

.image {
  max-width: 100%;
  border-radius: 10px;
  margin-top: 8px;
}

.sources {
  margin-top: 8px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.source-tag {
  cursor: help;
}

.meta {
  font-size: 12px;
  color: #98a1b3;
  padding: 0 4px 6px;
}

.composer {
  border-top: 1px solid #e6e8ef;
  padding-top: 10px;
}

.composer-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}

.doc-stats {
  font-size: 13px;
  color: #6b7280;
  margin-bottom: 10px;
}

.doc-content {
  margin-top: 8px;
}

.doc-actions {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}

.doc-item {
  padding: 8px 10px;
  border: 1px solid #eceef4;
  border-radius: 8px;
  margin-bottom: 8px;
}

.doc-title {
  font-size: 13px;
  font-weight: 600;
}

.doc-meta {
  font-size: 12px;
  color: #98a1b3;
  margin-top: 2px;
}

.empty {
  font-size: 13px;
  color: #98a1b3;
  padding: 8px 0;
}

.hit {
  margin-bottom: 10px;
}

.hit-text {
  margin: 4px 0 0;
  font-size: 12px;
  color: #6b7280;
  line-height: 1.5;
}

.score {
  font-weight: 400;
  font-size: 11px;
  color: #10b981;
  margin-left: 6px;
}
</style>