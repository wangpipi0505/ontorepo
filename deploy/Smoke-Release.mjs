/**
 * P4正式发布的真实HTTP闭环。所有内容写入独立验收空间，API key只在内存保留。
 * 覆盖五门禁/准备屏障/并发幂等、端点默认拒绝、正式meta与版本头、弃用及本地收件箱。
 * 不把本地通知收件箱当成外部智能体平台上架成功。
 */
import assert from 'node:assert/strict'
import { readFile, writeFile } from 'node:fs/promises'

const root = new URL('../', import.meta.url)
const config = JSON.parse((await readFile(new URL('.runtime/local-env.json', root), 'utf8')).replace(/^\uFEFF/, ''))
const base = process.env.ONTO_API_BASE ?? 'http://127.0.0.1:18182/onto'
// 此模式用于独立应用已禁用relay的真实积压验收；必须实际观察PENDING/空收件箱，
// 不能用脚本少等一会儿冒充停机。重启启用relay后由持久化脚本校验原事件顺序。
const deferEvents = process.argv.includes('--defer-events')
const reportName = deferEvents ? 'p4-deferred-http-smoke.json' : 'p4-http-smoke.json'
let token, space, credentialId, apiKey
const checks = []
async function request(path, method = 'GET', body, status = 200, key) {
  const response = await fetch(base + path, {
    method,
    headers: {
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(key ? { 'X-API-Key': key } : token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(30000),
  })
  const envelope = await response.json()
  assert.equal(response.status, status, `${method} ${path}: ${envelope.code} ${envelope.message}`)
  if (status === 200) assert.equal(envelope.code, 'OK')
  return { envelope, releaseHeader: response.headers.get('X-Ontology-Release') }
}
const data = async (...args) => (await request(...args)).envelope.data
async function mutate(path, body, method = 'POST') {
  space = await data(path, method, { spaceId: space.id, expectedDraftRev: space.draftRev, ...body })
}
async function check(name, action) {
  await action()
  checks.push({ name, status: 'PASSED' })
}
async function eventually(action, description) {
  const deadline = Date.now() + 15000
  do {
    if (await action()) return
    await new Promise(resolve => setTimeout(resolve, 300))
  } while (Date.now() < deadline)
  assert.fail(description)
}

try {
  token = (await data('/auth/login', 'POST', {
    username: config.ONTO_BOOTSTRAP_USERNAME, password: config.ONTO_BOOTSTRAP_PASSWORD,
  })).token
  const code = `release-${Date.now().toString(36)}`
  space = await data('/spaces', 'POST', {
    code, name: '正式发布消费验收', namespaceUri: `https://example.org/${code}/`, domain: '发布验证',
  })
  await mutate('/classes', { name: '任务', description: '冻结的任务模型', parentId: null, deprecated: false })
  await mutate('/operations', {
    code: 'list_tasks', name: '任务查询',
    queryTemplate: 'PREFIX owl:<http://www.w3.org/2002/07/owl#> PREFIX rdfs:<http://www.w3.org/2000/01/rdf-schema#> SELECT ?name WHERE { ?s a owl:Class; rdfs:label ?name } ORDER BY ?name',
    maxRows: 20, timeoutMs: 5000,
  })
  const operation = (await data(`/operations?spaceId=${space.id}`))[0]
  await mutate(`/spaces/${space.id}/guards`, {
    guardCfg: { region: { type: 'STRING', authorization_rule: { kind: 'PRINCIPAL_ATTRIBUTE', attribute: 'region' } } },
  }, 'PUT')
  const owner = (await data(`/spaces/${space.id}/grants`)).find(x => x.username === config.ONTO_BOOTSTRAP_USERNAME)
  await mutate(`/spaces/${space.id}/grants/${owner.userId}`, {
    role: 'OWNER', dataScope: { attr: 'region', in: ['A'] }, attributes: { region: 'A' },
  }, 'PUT')
  await mutate(`/operations/${operation.id}/assistant`, {
    inputSchema: { type: 'object', properties: {}, additionalProperties: false },
    outputSchema: {
      type: 'object', required: ['columns', 'rows'],
      properties: { columns: { type: 'array', items: { type: 'string' } }, rows: { type: 'array', items: { type: 'object' } } },
    },
    assistantTool: {
      name: '任务查询', description: '查询冻结任务模型',
      guard_attrs: ['region'], info_attrs: [], param_examples: [{}],
      exec: { readonly: true, side_effect: false, idempotent: true, cancellable: true },
      risk_level: 'READ_ONLY', error_semantics: [{ match: 'HTTP 403', semantic: 'NO_PERMISSION' }],
      return_spec: { modelSummary: { mode: 'TEMPLATE', template: '任务查询结果' }, structuredPath: '$.data', fileRefPaths: [], cardTemplate: '任务查询结果' },
    },
  }, 'PUT')
  await mutate('/test-cases', {
    name: '冻结任务精确结果', question: '直接查询', operationId: operation.id, input: {},
    expected: { status: 'SUCCEEDED', columns: ['name'], rows: [{ name: '任务' }] },
    enabled: true, mode: 'DIRECT', matchMode: 'EXACT',
  })
  const candidate = await data(`/spaces/${space.id}/candidates`, 'POST', { expectedDraftRev: space.draftRev })
  const bound = { expectedDraftRev: space.draftRev, candidateSha256: candidate.sha256 }
  let publication, release, endpoint
  await check('冻结预分配标识与真实bundle且提交前无正式版本', async () => {
    assert.ok(candidate.preReleaseId)
    assert.ok(Number.isInteger(candidate.preVersionNo) && candidate.preVersionNo > 0)
    publication = await data(`/candidates/${candidate.id}/publication`)
    assert.equal(publication.artifacts.find(x => x.kind === 'BUNDLE')?.sha256, candidate.sha256)
    assert.equal((await data(`/releases?spaceId=${space.id}`)).length, 0)
  })
  await check('先运行冻结用例不跳过机器门禁且同候选仍可继续校验', async () => {
    const early = await data(`/candidates/${candidate.id}/tests`, 'POST')
    assert.equal(early.passed, true)
    assert.equal(early.candidateSha256, candidate.sha256)
    const waiting = await data(`/candidates/${candidate.id}/publication`)
    assert.equal(waiting.status, 'VALIDATING')
    assert.equal(waiting.checks.some(item => ['CONFLICT', 'CONSISTENCY'].includes(item.kind)), false)
  })
  await check('机器校验真实用例和OWNER复核建立同摘要五门禁', async () => {
    await data(`/candidates/${candidate.id}/validate`, 'POST')
    const tests = await data(`/candidates/${candidate.id}/tests`, 'POST')
    assert.equal(tests.passed, true)
    assert.equal(tests.results.length, 1)
    publication = await data(`/candidates/${candidate.id}/review`, 'POST', { ...bound, reason: '逐项核对冻结差异及机器验收结果' })
    assert.equal(publication.checks.length, 5)
    assert.ok(publication.checks.every(x => x.conclusion === 'PASSED' && x.candidateSha256 === candidate.sha256))
  })
  await check('正式制品全部归档就绪后才READY_TO_COMMIT', async () => {
    publication = await data(`/candidates/${candidate.id}/prepare`, 'POST', bound)
    assert.equal(publication.status, 'READY_TO_COMMIT')
    assert.deepEqual(publication.artifacts.map(x => x.kind).sort(), ['BUNDLE', 'CONTEXT_PACK', 'KNOWLEDGE_INDEX', 'MANIFEST'])
    assert.ok(publication.artifacts.every(x => x.ready && x.sha256 && x.size > 0))
    assert.equal((await data(`/releases?spaceId=${space.id}`)).length, 0)
  })
  await check('并发重复提交只产生一个READY版本与原releaseId', async () => {
    const body = { expectedDraftRev: candidate.draftRev, expectedSha256: candidate.sha256 }
    const results = await Promise.all([data(`/candidates/${candidate.id}/commit`, 'POST', body), data(`/candidates/${candidate.id}/commit`, 'POST', body)])
    release = results[0]
    assert.equal(release.id, candidate.preReleaseId)
    assert.equal(results[1].id, release.id)
    assert.equal(release.status, 'READY')
    assert.equal(release.bundleSha256, candidate.sha256)
    assert.equal((await data(`/releases?spaceId=${space.id}`)).length, 1)
  })
  endpoint = (await data(`/releases/${release.id}/endpoints`)).find(x => x.opCode === operation.code)
  assert.ok(endpoint)
  await check('OWNER无端点授权也拒绝且失败无成功meta和版本头', async () => {
    const result = await request(`/internal/releases/${release.id}/ops/${operation.code}`, 'POST', {}, 403)
    assert.equal(Object.hasOwn(result.envelope, 'meta'), false)
    assert.equal(result.releaseHeader, null)
  })
  await data(`/endpoints/${endpoint.id}/grants/${owner.userId}`, 'PUT', {
    dataScope: { attr: 'region', in: ['A'] }, expiresAt: null, status: 'ACTIVE',
  })
  const expectedMeta = { spaceCode: space.code, releaseId: release.id, version: `v${release.versionNo}`, contentDigest: release.bundleSha256, preview: false }
  await check('正式内部调用的meta与版本头来自实际发布版本', async () => {
    const result = await request(`/internal/releases/${release.id}/ops/${operation.code}`, 'POST', {})
    assert.deepEqual(result.envelope.data.rows, [{ name: '任务' }])
    assert.deepEqual(result.envelope.meta, expectedMeta)
    assert.equal(result.releaseHeader, release.id)
  })
  await check('API key映射本地主体并执行真实EXTERNAL事实', async () => {
    const created = await data('/api-credentials', 'POST', { spaceId: space.id, principalId: owner.userId, name: '本地接口验收', expiresAt: null })
    credentialId = created.credential.id
    apiKey = created.key
    assert.ok(typeof apiKey === 'string' && apiKey.length > 20)
    const directory = await data(`/api-credentials?spaceId=${space.id}`)
    assert.ok(!JSON.stringify(directory).includes(apiKey))
    const result = await request(`/s/${space.code}/ops/${operation.code}?releaseId=${release.id}`, 'POST', {}, 200, apiKey)
    assert.deepEqual(result.envelope.meta, expectedMeta)
    assert.equal(result.releaseHeader, release.id)
    const facts = await data(`/spaces/${space.id}/executions`)
    assert.ok(facts.some(x => x.subjectType === 'RELEASE' && x.subjectId === release.id && x.caller === 'EXTERNAL' && x.status === 'SUCCEEDED'))
  })
  await check('编辑草稿不改变已发布查询或正式manifest摘要', async () => {
    const manifestBefore = await data(`/releases/${release.id}/manifest`)
    const packBefore = await data(`/releases/${release.id}/context-pack`)
    const detailBefore = await data(`/releases/${release.id}`)
    assert.equal(detailBefore.release.manifestSha256, release.manifestSha256)
    space = await data(`/spaces/${space.id}`)
    await mutate('/classes', { name: '草稿新增对象', description: '不属于冻结版本', parentId: null, deprecated: false })
    const result = await request(`/internal/releases/${release.id}/ops/${operation.code}`, 'POST', {})
    assert.deepEqual(result.envelope.data.rows, [{ name: '任务' }])
    assert.deepEqual(result.envelope.meta, expectedMeta)
    assert.deepEqual(await data(`/releases/${release.id}/manifest`), manifestBefore)
    assert.deepEqual(await data(`/releases/${release.id}/context-pack`), packBefore)
    const detailAfter = await data(`/releases/${release.id}`)
    assert.equal(detailAfter.release.manifestSha256, release.manifestSha256)
    assert.deepEqual(detailAfter.artifacts, detailBefore.artifacts)
  })
  await check(deferEvents ? 'relay停用时RELEASED持久积压且没有伪造接收' : '本地收件箱实际接收RELEASED且并发提交不重复建事件', async () => {
    if (!deferEvents) await eventually(async () => (await data(`/events/inbox?spaceId=${space.id}`)).some(x => x.releaseId === release.id && x.type === 'RELEASED'), '本地收件箱未收到RELEASED')
    const released = (await data(`/events?spaceId=${space.id}`)).filter(x => x.releaseId === release.id && x.type === 'RELEASED')
    assert.equal(released.length, 1)
    assert.equal(released[0].boundStatus, 'READY')
    if (deferEvents) {
      assert.equal(released[0].status, 'PENDING')
      assert.equal((await data(`/events/inbox?spaceId=${space.id}`)).length, 0)
    }
  })
  await check('弃用幂等且消费立即拒绝并记录DEPRECATED通知', async () => {
    await data(`/releases/${release.id}/deprecate`, 'POST', { reason: '验收完成后验证撤销消费' })
    await data(`/releases/${release.id}/deprecate`, 'POST', { reason: '幂等重试' })
    const failed = await request(`/internal/releases/${release.id}/ops/${operation.code}`, 'POST', {}, 409)
    assert.equal(Object.hasOwn(failed.envelope, 'meta'), false)
    assert.equal(failed.releaseHeader, null)
    if (!deferEvents) await eventually(async () => (await data(`/events/inbox?spaceId=${space.id}`)).some(x => x.releaseId === release.id && x.type === 'DEPRECATED'), '本地收件箱未收到DEPRECATED')
    const events = (await data(`/events?spaceId=${space.id}`)).filter(x => x.releaseId === release.id)
    assert.equal(events.filter(x => x.type === 'DEPRECATED').length, 1)
    assert.ok(events.find(x => x.type === 'RELEASED').id < events.find(x => x.type === 'DEPRECATED').id)
    if (deferEvents) {
      assert.equal(events.length, 2)
      assert.ok(events.every(x => x.status === 'PENDING' && x.retryCount === 0 && x.releaseStatus === 'DEPRECATED'))
      assert.equal((await data(`/events/inbox?spaceId=${space.id}`)).length, 0)
    }
  })
  await data(`/api-credentials/${credentialId}/revoke`, 'POST')
  apiKey = undefined
  const evidenceDir = new URL(process.env.ONTO_EVIDENCE_DIR ?? 'docs/evidence/continuation/', root)
  await writeFile(new URL(reportName, evidenceDir), JSON.stringify({
    checkedAt: new Date().toISOString(), spaceId: space.id, candidateId: candidate.id,
    releaseId: release.id, versionNo: release.versionNo, bundleSha256: release.bundleSha256,
    manifestSha256: release.manifestSha256, endpointId: endpoint.id, deferEvents,
    events: (await data(`/events?spaceId=${space.id}`)).map(({ id, type, boundStatus, status }) => ({ id, type, boundStatus, status })),
    checks,
  }, null, 2) + '\n')
  console.log(`${checks.length} 项 P4 真实 HTTP 验收通过。`)
} finally {
  // 验收中途断言失败也撤销本次临时key，不留下不再使用的消费凭据。
  if (token && credentialId && apiKey) await data(`/api-credentials/${credentialId}/revoke`, 'POST').catch(() => {})
  apiKey = undefined
  if (token) await request('/auth/logout', 'POST').catch(() => {})
}
