const API = '/api/v1'

async function request(url, options) {
    const response = await fetch(url, options)
    if (!response.ok) {
        let message = `请求失败(${response.status})`
        try {
            const body = await response.json()
            if (body && body.message) {
                message = body.message
            }
        } catch (ignored) {
            // 后端返回的不是 JSON，保留默认提示
        }
        throw new Error(message)
    }
    return response.json()
}

const jsonHeaders = {'Content-Type': 'application/json'}

export function chat(payload) {
    return request(`${API}/ai/chat`, {method: 'POST', headers: jsonHeaders, body: JSON.stringify(payload)})
}

export function loadHistory(conversationId) {
    return request(`${API}/ai/conversations/${encodeURIComponent(conversationId)}/messages`)
}

export function clearConversation(conversationId) {
    return request(`${API}/ai/conversations/${encodeURIComponent(conversationId)}`, {method: 'DELETE'})
}

export function generateImage(payload) {
    return request(`${API}/image`, {method: 'POST', headers: jsonHeaders, body: JSON.stringify(payload)})
}

export function listDocuments() {
    return request(`${API}/rag/documents`)
}

export function ingestDocument(payload) {
    return request(`${API}/rag/documents`, {method: 'POST', headers: jsonHeaders, body: JSON.stringify(payload)})
}

export function clearDocuments() {
    return request(`${API}/rag/documents`, {method: 'DELETE'})
}

export function searchDocuments(query, topK = 4) {
    return request(`${API}/rag/search?query=${encodeURIComponent(query)}&topK=${topK}`)
}

export function runAgent(payload) {
    return request(`${API}/agent/run`, {method: 'POST', headers: jsonHeaders, body: JSON.stringify(payload)})
}

export function listTraces(limit = 20) {
    return request(`${API}/agent/traces?limit=${limit}`)
}

export function getTrace(traceId) {
    return request(`${API}/agent/traces/${encodeURIComponent(traceId)}`)
}

export function runEval(limit = 5) {
    return request(`${API}/agent/eval?limit=${limit}`, {method: 'POST'})
}

/**
 * SSE 流式对话。
 *
 * 用 fetch + ReadableStream 而不是 EventSource：
 * EventSource 只支持 GET，问题必须拼在 query 上（长文本/中文很容易超过 URL 长度上限，还会进访问日志），
 * fetch 可以 POST + JSON body，同时保留 AbortController 取消能力。
 */
export async function streamChat(payload, handlers, signal) {
    const response = await fetch(`${API}/ai/chat/stream`, {
        method: 'POST',
        headers: {...jsonHeaders, 'Accept': 'text/event-stream'},
        body: JSON.stringify(payload),
        signal
    })
    if (!response.ok || !response.body) {
        throw new Error(`流式请求失败(${response.status})`)
    }
    await consume(response, handlers)
}

/** Agent 流式执行：事件为 plan / step / sources / delta / done / error。 */
export async function streamAgent(payload, handlers, signal) {
    const response = await fetch(`${API}/agent/run/stream`, {
        method: 'POST',
        headers: {...jsonHeaders, 'Accept': 'text/event-stream'},
        body: JSON.stringify(payload),
        signal
    })
    if (!response.ok || !response.body) {
        throw new Error(`Agent 流式请求失败(${response.status})`)
    }
    await consume(response, handlers)
}

/** 按 SSE 协议切帧：以空行分隔，事件名在 event: 行、载荷在 data: 行。 */
async function consume(response, handlers) {
    const reader = response.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    try {
        while (true) {
            const {value, done} = await reader.read()
            if (done) {
                break
            }
            buffer += decoder.decode(value, {stream: true})
            let index
            while ((index = buffer.indexOf('\n\n')) >= 0) {
                dispatch(buffer.slice(0, index), handlers)
                buffer = buffer.slice(index + 2)
            }
        }
        if (buffer.trim()) {
            dispatch(buffer, handlers)
        }
    } finally {
        reader.releaseLock()
    }
}

function dispatch(raw, handlers) {
    let event = 'message'
    const dataLines = []
    for (const line of raw.split('\n')) {
        if (line.startsWith('event:')) {
            event = line.slice(6).trim()
        } else if (line.startsWith('data:')) {
            dataLines.push(line.slice(5).replace(/^ /, ''))
        }
    }
    if (!dataLines.length) {
        return
    }

    const data = dataLines.join('\n')
    let parsed
    try {
        parsed = JSON.parse(data)
    } catch (error) {
        console.warn('无法解析 SSE 数据', data)
        return
    }

    if (event === 'sources') {
        handlers.onSources?.(parsed.sources || [])
    } else if (event === 'delta') {
        handlers.onDelta?.(parsed.text || '')
    } else if (event === 'done') {
        handlers.onDone?.(parsed)
    } else if (event === 'error') {
        handlers.onError?.(parsed.message || '生成失败')
    } else if (event === 'plan') {
        handlers.onPlan?.(parsed.tasks || [])
    } else if (event === 'step') {
        handlers.onStep?.(parsed)
    }
}