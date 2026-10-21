/**
 * 在应用重启后核对原P4发布事实，不重新冻结或重建版本。
 * 原验收已主动弃用版本，因此验证历史可审计、消费仍拒绝、重复提交/弃用不产生新事实。
 */
import assert from 'node:assert/strict'
import { readFile, writeFile } from 'node:fs/promises'

const root = new URL('../', import.meta.url)
const config = JSON.parse((await readFile(new URL('.runtime/local-env.json', root), 'utf8')).replace(/^\uFEFF/, ''))
const deferred = process.argv.includes('--deferred')
const previous = JSON.parse(await readFile(new URL(`docs/evidence/continuation/${deferred ? 'p4-deferred-http-smoke.json' : 'p4-http-smoke.json'}`, root), 'utf8'))
const base = process.env.ONTO_API_BASE ?? 'http://127.0.0.1:18182/onto'
const checks = []
let token
async function request(path, method = 'GET', body, status = 200) {
  const response = await fetch(base + path, {
    method,
    headers: {
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(30000),
  })
  const envelope = await response.json()
  assert.equal(response.status, status, `${method} ${path}: ${envelope.code} ${envelope.message}`)
  if (status === 200) assert.equal(envelope.code, 'OK')
  else {
    assert.equal(Object.hasOwn(envelope, 'meta'), false)
    assert.equal(response.headers.get('X-Ontology-Release'), null)
  }
  return envelope.data
}
async function check(name, action) {
  await action()
  checks.push({ name, status: 'PASSED' })
}
try {
  token = (await request('/auth/login', 'POST', {
    username: config.ONTO_BOOTSTRAP_USERNAME, password: config.ONTO_BOOTSTRAP_PASSWORD,
  })).token
  const detail = await request(`/releases/${previous.releaseId}`)
  await check('原版本身份摘要与弃用状态在重启后保持', async () => {
    assert.equal(detail.release.id, previous.releaseId)
    assert.equal(detail.release.candidateId, previous.candidateId)
    assert.equal(detail.release.versionNo, previous.versionNo)
    assert.equal(detail.release.bundleSha256, previous.bundleSha256)
    assert.equal(detail.release.manifestSha256, previous.manifestSha256)
    assert.equal(detail.release.status, 'DEPRECATED')
    assert.ok(detail.endpoints.every(x => x.status === 'DISABLED'))
  })
  await check('历史可浏览但两种正式消费路由及制品拉取仍拒绝', async () => {
    assert.ok((await request(`/releases/${previous.releaseId}/entities`)).length > 0)
    const endpoint = detail.endpoints.find(x => x.id === previous.endpointId)
    assert.ok(endpoint)
    await request(`/internal/releases/${previous.releaseId}/ops/${endpoint.opCode}`, 'POST', {}, 409)
    const space = await request(`/spaces/${previous.spaceId}`)
    await request(`/r/${space.code}/releases/${previous.releaseId}/ops/${endpoint.opCode}`, 'POST', {}, 409)
    await request(`/releases/${previous.releaseId}/manifest`, 'GET', undefined, 409)
    await request(`/releases/${previous.releaseId}/context-pack`, 'GET', undefined, 409)
  })
  await check('原候选提交幂等且不复活已弃用版本', async () => {
    const publication = await request(`/candidates/${previous.candidateId}/publication`)
    assert.equal(publication.status, 'COMMITTED')
    assert.equal(publication.releaseId, previous.releaseId)
    const release = await request(`/candidates/${previous.candidateId}/commit`, 'POST', {
      expectedDraftRev: publication.draftRev, expectedSha256: previous.bundleSha256,
    })
    assert.equal(release.id, previous.releaseId)
    assert.equal(release.status, 'DEPRECATED')
    await request(`/releases/${previous.releaseId}/deprecate`, 'POST', { reason: '重启后幂等验收' })
    assert.equal((await request(`/releases?spaceId=${previous.spaceId}`)).length, 1)
  })
  await check('原事件及真实收件凭据未重复生成', async () => {
    if (deferred) {
      assert.equal(previous.deferEvents, true)
      const deadline = Date.now() + 20000
      // 进程重启的调度首轮存在延迟；仅轮询原记录，绝不替换ID/改数据库加速。
      while (Date.now() < deadline) {
        const current = await request(`/events?spaceId=${previous.spaceId}`)
        if (current.length === 2 && current.every(x => x.status === 'SENT')) break
        await new Promise(resolve => setTimeout(resolve, 300))
      }
    }
    const events = (await request(`/events?spaceId=${previous.spaceId}`)).filter(x => x.releaseId === previous.releaseId)
    assert.equal(events.filter(x => x.type === 'RELEASED').length, 1)
    assert.equal(events.filter(x => x.type === 'DEPRECATED').length, 1)
    assert.ok(events.every(x => x.status === 'SENT'))
    if (previous.events) assert.deepEqual(events.map(({ id, type, boundStatus }) => ({ id, type, boundStatus })),
      previous.events.map(({ id, type, boundStatus }) => ({ id, type, boundStatus })))
    const inbox = (await request(`/events/inbox?spaceId=${previous.spaceId}`)).filter(x => x.releaseId === previous.releaseId)
    assert.equal(inbox.length, 2)
    assert.equal(new Set(inbox.map(x => x.eventId)).size, 2)
    assert.ok(inbox.every(x => x.receipt && x.receivedAt))
    const released = events.find(x => x.type === 'RELEASED')
    const deprecated = events.find(x => x.type === 'DEPRECATED')
    assert.equal(released.boundStatus, 'READY')
    assert.equal(deprecated.boundStatus, 'DEPRECATED')
    assert.ok(released.id < deprecated.id)
    assert.ok(new Date(inbox.find(x => x.eventId === released.id).receivedAt).getTime()
      <= new Date(inbox.find(x => x.eventId === deprecated.id).receivedAt).getTime())
  })
  await check('正式调用事实与撤销凭据持久化', async () => {
    const facts = await request(`/spaces/${previous.spaceId}/executions`)
    assert.ok(facts.some(x => x.subjectType === 'RELEASE' && x.subjectId === previous.releaseId && x.caller === 'EXTERNAL' && x.status === 'SUCCEEDED'))
    const keys = await request(`/api-credentials?spaceId=${previous.spaceId}`)
    assert.ok(keys.length > 0)
    assert.ok(keys.every(x => x.status === 'REVOKED' && !Object.hasOwn(x, 'key')))
  })
  const evidenceDir = new URL(process.env.ONTO_EVIDENCE_DIR ?? 'docs/evidence/continuation/', root)
  await writeFile(new URL(deferred ? 'p4-deferred-persistence-smoke.json' : 'p4-persistence-smoke.json', evidenceDir), JSON.stringify({
    checkedAt: new Date().toISOString(), spaceId: previous.spaceId, releaseId: previous.releaseId, checks,
  }, null, 2) + '\n')
  console.log(`${checks.length} 项 P4 原始发布重启验证通过。`)
} finally {
  if (token) await request('/auth/logout', 'POST').catch(() => {})
}
