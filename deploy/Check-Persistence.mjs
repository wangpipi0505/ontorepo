/**
 * 在应用和独立 PostgreSQL 重启后复核上一轮真实验收的候选。
 * 不重建模型，不重新冻结候选；因此能发现依赖进程内状态或草稿回读的问题。
 */
import assert from 'node:assert/strict'
import { readFile, writeFile } from 'node:fs/promises'

const root = new URL('../', import.meta.url)
const config = JSON.parse((await readFile(new URL('.runtime/local-env.json', root), 'utf8')).replace(/^\uFEFF/, ''))
const previous = JSON.parse(await readFile(new URL('docs/evidence/api-smoke.json', root), 'utf8'))
const base = process.env.ONTO_API_BASE ?? 'http://127.0.0.1:18182/onto'
let token
async function request(path, body) {
  const response = await fetch(base + path, {
    method: body ? 'POST' : 'GET',
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(15000),
  })
  const result = await response.json()
  assert.equal(response.status, 200, `${path}: ${result.code} ${result.message}`)
  return result.data
}
token = (await request('/auth/login', {
  username: config.ONTO_BOOTSTRAP_USERNAME,
  password: config.ONTO_BOOTSTRAP_PASSWORD,
})).token
try {
  const frozen = await request(`/candidates/${previous.candidateId}/operations`)
  const operation = frozen.find(item => item.code === 'list_classes')
  assert.ok(operation, '旧候选中的操作必须保留')
  const live = await request(`/operations?spaceId=${previous.spaceId}`)
  assert.ok(!live.some(item => item.id === operation.id), '草稿已删除操作，不能靠草稿完成恢复')
  const execution = await request(`/candidates/${previous.candidateId}/trial`, { operationId: operation.id })
  assert.equal(execution.status, 'SUCCEEDED')
  assert.ok(execution.rows.some(row => row.name === '任务'))
  assert.ok(!execution.rows.some(row => row.name === '修改后的任务'))
  const candidates = await request(`/spaces/${previous.spaceId}/candidates`)
  assert.equal(execution.candidateSha256, candidates.find(item => item.id === previous.candidateId).sha256)
  const history = await request(`/spaces/${previous.spaceId}/executions`)
  assert.ok(history.length >= 3, '旧执行事实与重启后的新执行事实应共同存在')
  const evidenceDir = new URL(process.env.ONTO_EVIDENCE_DIR ?? 'docs/evidence/', root)
  await writeFile(new URL('persistence-smoke.json', evidenceDir), JSON.stringify({
    checkedAt: new Date().toISOString(),
    spaceId: previous.spaceId,
    candidateId: previous.candidateId,
    candidateSha256: execution.candidateSha256,
    executionId: execution.id,
    result: 'PASSED',
    checks: ['旧候选操作持久化', '草稿删除不影响旧候选', '旧模型内容保留', '摘要一致', '历史执行事实保留'],
  }, null, 2) + '\n')
  console.log('重启后 5 项持久性检查通过。')
} finally {
  await request('/auth/logout', {})
}
