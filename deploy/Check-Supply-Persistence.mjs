/**
 * 应用重启后复用 P2 原验收候选，核验冻结知识和检索索引能够从持久数据重建。
 * 不修改草稿、不创建候选；令牌仅保留于进程内，证据不含身份凭据。
 */
import assert from 'node:assert/strict'
import { readFile, writeFile } from 'node:fs/promises'

const root = new URL('../', import.meta.url)
const config = JSON.parse((await readFile(new URL('.runtime/local-env.json', root), 'utf8')).replace(/^\uFEFF/, ''))
const previous = JSON.parse(await readFile(new URL('docs/evidence/continuation/p2-http-smoke.json', root), 'utf8'))
const base = process.env.ONTO_API_BASE ?? 'http://127.0.0.1:18182/onto'
let token
async function request(path, body) {
  const response = await fetch(base + path, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(20000),
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
  const path = `/candidates/${previous.candidateId}`
  const contracts = (await request(`${path}/contracts`)).data
  assert.equal(contracts.candidateSha256, previous.candidateSha256)
  assert.deepEqual(contracts.manifests[0].guard_attrs, ['region'])
  const pack = (await request(`${path}/context-pack`)).data
  assert.equal(pack.candidateSha256, previous.candidateSha256)
  assert.equal(pack.termDictionary[0].term, '任务')
  assert.equal(pack.termDictionary[0].sourceRef, '本地联合验收/任务定义')
  const live = (await request(`/knowledge?spaceId=${previous.spaceId}`)).data
  assert.ok(live.some(entry => entry.term === '更新后的任务'), '应证明草稿与原候选确已分离')
  const search = (await request(`${path}/knowledge-search?q=${encodeURIComponent('业务任务')}&limit=10`)).data
  assert.ok(search.hits.some(hit => hit.entryId === pack.termDictionary[0].entryId && hit.score > 0))
  const execution = await request(`/internal/candidates/${previous.candidateId}/ops/list_classes`, {})
  assert.equal(execution.meta.contentDigest, previous.candidateSha256)
  assert.deepEqual(execution.data, { columns: ['name'], rows: [{ name: '任务' }] })
  const evidence = {
    checkedAt: new Date().toISOString(),
    candidateId: previous.candidateId,
    candidateSha256: previous.candidateSha256,
    result: 'PASSED',
    checks: ['冻结契约摘要与守卫不变', '冻结词条与来源不变', '草稿知识更新未污染旧候选', 'Lucene重建命中原词条', '原候选内部执行及真实摘要保留'],
  }
  const evidenceDir = new URL(process.env.ONTO_EVIDENCE_DIR ?? 'docs/evidence/continuation/', root)
  await writeFile(new URL('p2-persistence-smoke.json', evidenceDir), JSON.stringify(evidence, null, 2) + '\n')
  console.log('P2 重启后 5 项冻结供给持久性检查通过。')
} finally {
  if (token) await request('/auth/logout', {}).catch(() => {})
}
