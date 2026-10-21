/**
 * 应用重启后重放P3原始候选，验证加密源快照、映射、模型和来源证据均可持久恢复。
 * 本脚本不重新冻结、不修改源；原HTTP验收已将草稿关系库范围缩小，旧候选仍须返回原结果。
 */
import assert from 'node:assert/strict'
import { readFile, writeFile } from 'node:fs/promises'

const root = new URL('../', import.meta.url)
const config = JSON.parse((await readFile(new URL('.runtime/local-env.json', root), 'utf8')).replace(/^\uFEFF/, ''))
const previous = JSON.parse(await readFile(new URL('docs/evidence/continuation/p3-http-smoke.json', root), 'utf8'))
const base = process.env.ONTO_API_BASE ?? 'http://127.0.0.1:18182/onto'
const checks = []
let token
async function request(path, body) {
  const response = await fetch(base + path, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(30000),
  })
  const envelope = await response.json()
  assert.equal(response.status, 200, `${path}: ${envelope.code} ${envelope.message}`)
  assert.equal(envelope.code, 'OK')
  return envelope
}
try {
  token = (await request('/auth/login', {
    username: config.ONTO_BOOTSTRAP_USERNAME, password: config.ONTO_BOOTSTRAP_PASSWORD,
  })).data.token
  const sources = (await request(`/datasources?spaceId=${previous.spaceId}`)).data
  assert.deepEqual(sources.find(x => x.id === previous.sourceId).accessScope, ['onto_sample.tasks'])
  const original = { ...previous, kind: 'RELATIONAL', dialect: 'postgresql' }
  for (const item of [original, ...previous.protocolEvidence]) {
    const trial = (await request(`/candidates/${item.candidateId}/trial`, { operationId: item.operationId, input: {} })).data
    assert.equal(trial.status, 'SUCCEEDED', trial.errorCode)
    assert.equal(trial.candidateSha256, item.candidateSha256)
    assert.deepEqual(trial.rows, item.expectedRows)
    for (const kind of ['SOURCE_RECORD', 'META_SNAPSHOT', 'MAPPING_SNAPSHOT', 'ONTOLOGY_VERSION']) {
      assert.ok(trial.evidence.some(x => x.kind === kind), `${item.kind}/${item.dialect}缺少${kind}`)
    }
    const service = await request(`/internal/candidates/${item.candidateId}/ops/${item.opCode}`, {})
    assert.deepEqual(service.data.rows, item.expectedRows)
    assert.equal(service.meta.contentDigest, item.candidateSha256)
    assert.equal(service.meta.preview, true)
    checks.push({ kind: item.kind, dialect: item.dialect, candidateId: item.candidateId, executionId: trial.id, status: 'PASSED' })
  }
  const facts = (await request(`/spaces/${previous.spaceId}/executions`)).data
  for (const check of checks) assert.ok(facts.some(x => x.id === check.executionId && x.evidence.length >= 4))
  const evidenceDir = new URL(process.env.ONTO_EVIDENCE_DIR ?? 'docs/evidence/continuation/', root)
  await writeFile(new URL('p3-persistence-smoke.json', evidenceDir), JSON.stringify({
    checkedAt: new Date().toISOString(), spaceId: previous.spaceId,
    draftScopeNarrowingPreserved: true, persistedExecutionEvidence: true, checks,
  }, null, 2) + '\n')
  console.log(`P3 重启后 ${checks.length} 组真实源候选执行、SERVICE结果与持久证据检查通过。`)
} finally {
  if (token) await request('/auth/logout', {}).catch(() => {})
}
