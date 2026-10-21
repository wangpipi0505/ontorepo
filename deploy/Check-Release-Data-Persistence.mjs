/**
 * 应用重启后只读核对原正式映射版本、恢复后的草稿及历史执行。
 * 必须先成功运行 Smoke-Release-Data.mjs 并重启应用；不重新冻结或用新版本替代旧证据。
 */
import assert from 'node:assert/strict'
import { readFile, writeFile } from 'node:fs/promises'

const root = new URL('../', import.meta.url)
const config = JSON.parse((await readFile(new URL('.runtime/local-env.json', root), 'utf8')).replace(/^\uFEFF/, ''))
const previous = JSON.parse(await readFile(new URL('docs/evidence/continuation/p4-data-http-smoke.json', root), 'utf8'))
const base = process.env.ONTO_API_BASE ?? 'http://127.0.0.1:18182/onto'
const checks = []
let token
async function request(path, method = 'GET', body) {
  const response = await fetch(base + path, {
    method,
    headers: {
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(60000),
  })
  const result = await response.json()
  assert.equal(response.status, 200, `${method} ${path}: ${result.code}`)
  assert.equal(result.code, 'OK')
  return { ...result, releaseHeader: response.headers.get('X-Ontology-Release') }
}
const data = async (...args) => (await request(...args)).data
async function check(name, action) {
  await action()
  checks.push({ name, status: 'PASSED' })
}
try {
  token = (await data('/auth/login', 'POST', {
    username: config.ONTO_BOOTSTRAP_USERNAME, password: config.ONTO_BOOTSTRAP_PASSWORD,
  })).token
  let release
  await check('原两个正式版本与摘要重启后保持', async () => {
    const all = await data(`/releases?spaceId=${previous.spaceId}`)
    assert.equal(all.length, 2)
    release = all.find(x => x.id === previous.releaseId)
    assert.ok(release)
    assert.equal(release.candidateId, previous.candidateId)
    assert.equal(release.bundleSha256, previous.bundleSha256)
    assert.equal(release.versionNo, previous.versionNo)
    assert.equal(release.status, 'READY')
    const second = all.find(x => x.id === previous.secondReleaseId)
    assert.ok(second)
    assert.equal(second.status, 'READY')
    assert.equal(second.candidateId, previous.secondCandidateId)
    assert.equal(second.bundleSha256, previous.secondBundleSha256)
    assert.equal(second.versionNo, previous.secondVersionNo)
  })
  await check('正式原路由和别名执行相同冻结映射与真实版本meta', async () => {
    const expectedMeta = {
      spaceCode: previous.spaceCode, releaseId: previous.releaseId,
      version: `v${release.versionNo}`, contentDigest: previous.bundleSha256, preview: false,
    }
    for (const route of [
      `/r/${previous.spaceCode}/releases/${previous.releaseId}/ops/${previous.opCode}`,
      `/s/${previous.spaceCode}/ops/${previous.opCode}?releaseId=${previous.releaseId}`,
    ]) {
      const response = await request(route, 'POST', {})
      assert.deepEqual(response.data.rows, previous.expectedRows)
      assert.deepEqual(response.meta, expectedMeta)
      assert.equal(response.releaseHeader, previous.releaseId)
    }
  })
  await check('恢复后的源文件映射目录保持原引用', async () => {
    const mappings = await data(`/mappings?spaceId=${previous.spaceId}`)
    assert.equal(mappings.objects.length, 1)
    assert.equal(mappings.objects[0].datasourceId, previous.sourceId)
    assert.equal(mappings.objects[0].metaSnapshotId, previous.snapshotId)
    const files = await data(`/data-files?spaceId=${previous.spaceId}`)
    assert.ok(files.some(x => x.id === previous.fileId))
    const sources = await data(`/datasources?spaceId=${previous.spaceId}`)
    assert.equal(sources.length, 1)
    assert.equal(sources[0].id, previous.sourceId)
  })
  await check('原历史执行与来源证据重启后仍可追溯', async () => {
    const trace = await data(`/executions/${previous.executionId}/trace`)
    assert.equal(trace.execution.id, previous.executionId)
    assert.equal(trace.execution.subjectId, previous.releaseId)
    assert.equal(trace.execution.subjectType, 'RELEASE')
    for (const kind of ['SOURCE_RECORD', 'META_SNAPSHOT', 'MAPPING_SNAPSHOT', 'ONTOLOGY_VERSION']) {
      assert.ok(trace.evidence.some(x => x.type === kind), `缺失 ${kind}`)
    }
    // 不只检验种类：原行ID、引用、摘要、映射绑定及安全详情均必须与停机前完全一致。
    assert.deepEqual(trace.evidence, previous.evidence)
    assert.deepEqual(trace.authorization, previous.authorization)
    const metadata = trace.evidence.find(x => x.type === 'META_SNAPSHOT' && x.ref === previous.snapshotId)
    assert.ok(metadata)
    assert.equal(metadata.sha256, previous.snapshotSha256)
    const ontology = trace.evidence.find(x => x.type === 'ONTOLOGY_VERSION')
    assert.equal(ontology.ref, previous.releaseId)
    assert.equal(ontology.sha256, previous.bundleSha256)
    assert.equal(trace.authorization.length, 6)
    assert.ok(trace.authorization.every(x => x.passed))
  })
  await check('原两版本RELEASED收件事实持久去重', async () => {
    const events = await data(`/events?spaceId=${previous.spaceId}`)
    const inbox = await data(`/events/inbox?spaceId=${previous.spaceId}`)
    const identities = events
      .filter(x => [previous.releaseId, previous.secondReleaseId].includes(x.releaseId))
      .map(({ id, releaseId, type, boundStatus, createdAt }) => ({ id, releaseId, type, boundStatus, createdAt }))
    assert.deepEqual(identities, previous.events)
    for (const id of [previous.releaseId, previous.secondReleaseId]) {
      const released = events.filter(x => x.releaseId === id && x.type === 'RELEASED')
      assert.equal(released.length, 1)
      assert.equal(released[0].status, 'SENT')
      assert.equal(released[0].boundStatus, 'READY')
      assert.equal(inbox.filter(x => x.eventId === released[0].id).length, 1)
    }
  })
  const evidenceDir = new URL(process.env.ONTO_EVIDENCE_DIR ?? 'docs/evidence/continuation/', root)
  await writeFile(new URL('p4-data-persistence-smoke.json', evidenceDir), JSON.stringify({
    checkedAt: new Date().toISOString(), spaceId: previous.spaceId,
    releaseId: previous.releaseId, bundleSha256: previous.bundleSha256, checks,
  }, null, 2) + '\n')
  console.log(`${checks.length} 项原正式映射版本重启验证通过。`)
} finally {
  if (token) await request('/auth/logout', 'POST').catch(() => {})
}
