/**
 * 双信封统一请求封装（009 拍板 B，oryxos-admin-ui skill 约定）——页面不得手写两套解析：
 * 成功信封 { code: 0, message, data, timestamp }；错误信封 { errorCode, message, timestamp }。
 */
export async function apiGet(path) {
  const res = await fetch(path)
  const contentType = res.headers.get('content-type') ?? ''
  if (!contentType.includes('application/json')) {
    // 非 JSON 响应（如 dev 模式无后端时被 SPA 回落成 HTML）——给出可读错误而不是 SyntaxError
    throw new Error(`服务返回非 JSON（HTTP ${res.status}）——后端未启动或路径不存在`)
  }
  const body = await res.json()
  if (!res.ok || body.errorCode !== undefined) {
    throw new Error(body.message ?? `HTTP ${res.status}`)
  }
  return body.data
}

/** 写操作统一封装（010 定时任务页：POST run / PUT enabled）——与 apiGet 同款双信封解析，错误信封统一抛 message。 */
export async function apiPost(path) {
  const res = await fetch(path, { method: 'POST' })
  const body = await res.json()
  if (!res.ok || body.errorCode !== undefined) {
    throw new Error(body.message ?? `HTTP ${res.status}`)
  }
  return body.data
}

/** 写操作统一封装（PUT enabled）——双信封解析同上。 */
export async function apiPut(path, payload) {
  const res = await fetch(path, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  const body = await res.json()
  if (!res.ok || body.errorCode !== undefined) {
    throw new Error(body.message ?? `HTTP ${res.status}`)
  }
  return body.data
}
