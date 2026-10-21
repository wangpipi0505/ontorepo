/**
 * 正式版本的映射消费、四层范围和恢复验收。
 * 使用独立 CSV 空间与真实发布制品，期望结果直接来自固定样例，不能拿执行结果生成期望。
 * 留下两个正式版本供浏览器/重启复查；不创建外部凭据，不改其他验收空间。
 */
import assert from 'node:assert/strict'
import { readFile, writeFile } from 'node:fs/promises'

const root = new URL('../', import.meta.url)
const config = JSON.parse((await readFile(new URL('.runtime/local-env.json', root), 'utf8')).replace(/^\uFEFF/, ''))
const base = process.env.ONTO_API_BASE ?? 'http://127.0.0.1:18182/onto'
const checks = []
let token, space
async function request(path, method = 'GET', body, status = 200) {
  const multipart = body instanceof FormData
  const response = await fetch(base + path, {
    method,
    headers: {
      ...(body !== undefined && !multipart ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : multipart ? body : JSON.stringify(body),
    signal: AbortSignal.timeout(60000),
  })
  const envelope = await response.json()
  assert.equal(response.status, status, `${method} ${path}: ${envelope.code} ${envelope.message}`)
  if (status === 200) assert.equal(envelope.code, 'OK')
  else {
    assert.equal(Object.hasOwn(envelope, 'meta'), false)
    assert.equal(response.headers.get('X-Ontology-Release'), null)
  }
  return { ...envelope, releaseHeader: response.headers.get('X-Ontology-Release') }
}
const data = async (...args) => (await request(...args)).data
async function mutate(path, body, method = 'POST') {
  space = await data(path, method, { spaceId: space.id, expectedDraftRev: space.draftRev, ...body })
}
async function check(name, action) {
  await action()
  checks.push({ name, status: 'PASSED' })
}
async function publish() {
  const candidate = await data(`/spaces/${space.id}/candidates`, 'POST', { expectedDraftRev: space.draftRev })
  const bound = { expectedDraftRev: space.draftRev, candidateSha256: candidate.sha256 }
  await data(`/candidates/${candidate.id}/validate`, 'POST')
  const testRun = await data(`/candidates/${candidate.id}/tests`, 'POST')
  assert.equal(testRun.passed, true)
  assert.equal(testRun.results.length, 1)
  assert.ok(testRun.results[0].executionId)
  await data(`/candidates/${candidate.id}/review`, 'POST', { ...bound, reason: '固定样例精确断言、授权范围及冻结差异已核对' })
  const prepared = await data(`/candidates/${candidate.id}/prepare`, 'POST', bound)
  assert.equal(prepared.status, 'READY_TO_COMMIT')
  assert.equal(prepared.artifacts.length, 4)
  assert.ok(prepared.artifacts.every(x => x.ready))
  return {
    candidate,
    release: await data(`/candidates/${candidate.id}/commit`, 'POST', {
      expectedDraftRev: space.draftRev, expectedSha256: candidate.sha256,
    }),
  }
}

try {
  token = (await data('/auth/login', 'POST', {
    username: config.ONTO_BOOTSTRAP_USERNAME, password: config.ONTO_BOOTSTRAP_PASSWORD,
  })).token
  const code = `release-data-${Date.now().toString(36)}`
  space = await data('/spaces', 'POST', {
    code, name: '正式映射与版本恢复验收', namespaceUri: `https://example.org/${code}/`, domain: '发布数据',
  })
  await mutate('/classes', { name: '任务', parentId: null, deprecated: false, description: '真实受管 CSV 的任务' })
  const task = (await data(`/classes?spaceId=${space.id}`))[0]
  for (const [name, rangeType] of [['任务编号', 'STRING'], ['数量', 'INTEGER']]) {
    await mutate('/properties', { name, rangeType, domainClassId: task.id, required: true, description: name })
  }
  const properties = await data(`/spaces/${space.id}/properties`)
  const idProperty = properties.find(x => x.name === '任务编号')
  const numberProperty = properties.find(x => x.name === '数量')
  const form = new FormData()
  // 空标签坏值必须先被授权层拒绝；C 有合法值，用于证明冻结策略仍能收窄 NULL 授权。
  form.append('file', new Blob(['task_id,region,raw_number\nT-NULL,,bad\nT-C,C,03\nT-B,B,01\nT-A,A,02\n'], { type: 'text/csv' }), 'release-tasks.csv')
  const uploaded = await data(`/data-files?spaceId=${space.id}&expectedDraftRev=${space.draftRev}`, 'POST', form)
  space = uploaded.space
  await mutate('/datasources', {
    code: 'release_tasks', name: '正式任务样例', kind: 'FILE',
    connection: { dialect: 'csv', fileId: uploaded.file.id }, accessScope: [uploaded.file.resource],
  })
  const source = (await data(`/datasources?spaceId=${space.id}`))[0]
  const synced = await data(`/datasources/${source.id}/sync`, 'POST', { spaceId: space.id, expectedDraftRev: space.draftRev })
  space = synced.space
  await mutate('/mappings/objects', {
    datasourceId: source.id, sourceResource: uploaded.file.resource, targetClassId: task.id,
    objectKeyExpr: 'task:${task_id}', filter: null, metaSnapshotId: synced.snapshot.id,
  })
  const object = (await data(`/mappings?spaceId=${space.id}`)).objects[0]
  for (const [sourceField, targetPropertyId, kind] of [
    ['task_id', idProperty.id, 'IDENTITY'], ['raw_number', numberProperty.id, 'TO_NUMBER'],
  ]) {
    await mutate('/mappings/properties', { objectMappingId: object.id, sourceField, targetPropertyId, required: true, transform: { kind } })
  }
  await mutate(`/spaces/${space.id}/guards`, {
    guardCfg: { region: { type: 'STRING', filter_bindings: [{ table: uploaded.file.resource, column: 'region', operator: 'IN' }] } },
  }, 'PUT')
  const owner = (await data(`/spaces/${space.id}/grants`)).find(x => x.username === config.ONTO_BOOTSTRAP_USERNAME)
  async function spaceScope(dataScope) {
    space = await data(`/spaces/${space.id}`)
    await mutate(`/spaces/${space.id}/grants/${owner.userId}`, { role: 'OWNER', dataScope, attributes: { region: 'A' } }, 'PUT')
  }
  const scope = (...values) => ({ attr: 'region', in: values })
  await spaceScope(scope('A'))
  await mutate('/strategies', {
    name: '仅允许分类 A 和 B',
    policy: { maxRows: 10, timeoutMs: 5000, resultLimit: 10, failureMode: 'FAIL', readScope: scope('A', 'B') },
    boundOps: [],
  })
  const strategy = (await data(`/strategies?spaceId=${space.id}`))[0]
  await mutate('/operations', {
    code: 'published_tasks', name: '正式任务查询', kind: 'OBJECT_QUERY', strategyRef: strategy.id,
    queryTemplate: 'PREFIX owl:<http://www.w3.org/2002/07/owl#> SELECT ?s WHERE { ?s a owl:Class } ORDER BY ?s',
    maxRows: 10, timeoutMs: 5000,
  })
  const operation = (await data(`/operations?spaceId=${space.id}`))[0]
  const propertyIds = [idProperty.id, numberProperty.id]
  await mutate(`/operations/${operation.id}/data-query`, {
    dataQuery: { mode: 'OBJECT', objectMappingId: object.id, propertyIds, filters: [] },
  }, 'PUT')
  await mutate(`/operations/${operation.id}/assistant`, {
    inputSchema: { type: 'object', properties: {}, additionalProperties: false },
    outputSchema: { type: 'object', required: ['rows'], properties: { rows: { type: 'array', items: { type: 'object' } } } },
    assistantTool: {
      name: '正式任务', description: '只读任务查询', guard_attrs: ['region'], info_attrs: [], param_examples: [{}],
      exec: { readonly: true, side_effect: false, idempotent: true, cancellable: true },
      risk_level: 'READ_ONLY', error_semantics: [{ match: 'HTTP 403', semantic: 'NO_PERMISSION' }],
      return_spec: { modelSummary: { mode: 'TEMPLATE', template: '任务查询结果' }, structuredPath: '$.data', fileRefPaths: [], cardTemplate: '任务查询结果' },
    },
  }, 'PUT')
  const expectedRow = (id, number) => ({ objectId: `task:${id}`, classId: task.id, [idProperty.id]: id, [numberProperty.id]: number })
  const rowA = expectedRow('T-A', 2), rowB = expectedRow('T-B', 1)
  await mutate('/test-cases', {
    name: '正式任务 A 精确断言', question: '查询任务', operationId: operation.id, input: {},
    expected: { status: 'SUCCEEDED', columns: ['objectId', 'classId', ...propertyIds], rows: [rowA] },
    enabled: true, mode: 'DIRECT', matchMode: 'EXACT',
  })
  const first = await publish()
  const release = first.release
  const endpoint = (await data(`/releases/${release.id}/endpoints`))[0]
  const route = `/r/${space.code}/releases/${release.id}/ops/${operation.code}`
  async function grant(dataScope, status = 'ACTIVE', expiresAt = null) {
    return data(`/endpoints/${endpoint.id}/grants/${owner.userId}`, 'PUT', { dataScope, status, expiresAt })
  }
  async function rows(expected) {
    const result = await request(route, 'POST', {})
    assert.deepEqual(result.data.rows, expected)
    assert.equal(result.meta.releaseId, release.id)
    assert.equal(result.meta.contentDigest, release.bundleSha256)
    assert.equal(result.meta.preview, false)
    assert.equal(result.releaseHeader, release.id)
  }
  await check('正式实例端点缺授权时OWNER也拒绝', async () => {
    await request(route, 'POST', {}, 403)
  })
  await check('正式路由执行冻结CSV转换且只返回端点范围A', async () => {
    await spaceScope(scope('A', 'B'))
    await grant(scope('A'))
    await rows([rowA])
  })
  await check('端点NULL不限仍受冻结策略和非空标签约束', async () => {
    await spaceScope(null)
    await grant(null)
    await rows([rowB, rowA])
  })
  await check('端点B与空间A的交集不能扩大任一范围', async () => {
    await spaceScope(scope('A'))
    await grant(scope('B'))
    await rows([])
  })
  await check('过期和撤销端点授权均拒绝且无成功meta', async () => {
    await grant(scope('A'), 'ACTIVE', '2020-01-01T00:00:00Z')
    await request(route, 'POST', {}, 403)
    await grant(scope('A'), 'REVOKED')
    await request(route, 'POST', {}, 403)
    await grant(scope('A'))
    await rows([rowA])
  })
  await check('执行trace保留真实来源快照映射版本及六步授权', async () => {
    const facts = await data(`/spaces/${space.id}/executions`)
    const execution = facts.find(x => x.subjectType === 'RELEASE' && x.subjectId === release.id && x.status === 'SUCCEEDED')
    assert.ok(execution)
    const trace = await data(`/executions/${execution.id}/trace`)
    for (const kind of ['SOURCE_RECORD', 'META_SNAPSHOT', 'MAPPING_SNAPSHOT', 'ONTOLOGY_VERSION']) {
      assert.ok(trace.evidence.some(x => x.type === kind), `缺失 ${kind}`)
    }
    assert.equal(trace.authorization.length, 6)
    assert.ok(trace.authorization.every(x => x.passed))
    assert.ok(trace.graph.nodes.length > 0 && trace.timeline.length > 0)
    const metadata = trace.evidence.find(x => x.type === 'META_SNAPSHOT' && x.ref === synced.snapshot.id)
    assert.ok(metadata, '证据必须定位本次实际同步的快照')
    assert.equal(metadata.sha256, synced.snapshot.sha256)
    const ontology = trace.evidence.find(x => x.type === 'ONTOLOGY_VERSION')
    assert.equal(ontology.ref, release.id)
    assert.equal(ontology.sha256, release.bundleSha256)
    // 只保存追溯API的安全输出。重启时逐项比较原证据身份及绑定，不以“仍有同类节点”代替不变性。
    first.evidence = trace.evidence
    first.authorization = trace.authorization
    first.executionId = execution.id
  })
  await mutate(`/properties/${numberProperty.id}`, { ...numberProperty, name: '新版数量', description: '用于验证版本差异和恢复' }, 'PUT')
  const second = await publish()
  await check('版本差异定位属性且旧版本仍使用原冻结结果', async () => {
    const diff = await data(`/releases/diff?spaceId=${space.id}&fromReleaseId=${release.id}&toReleaseId=${second.release.id}`)
    assert.ok(diff.changed.some(x => x.kind === 'PROPERTY' && x.key === numberProperty.id))
    await rows([rowA])
  })
  await check('恢复旧版重建草稿且旧执行版本制品保持不变', async () => {
    const before = await data(`/spaces/${space.id}`)
    space = await data(`/releases/${release.id}/restore-draft`, 'POST', { expectedDraftRev: before.draftRev, reason: '核对历史全域恢复与来源证据保持' })
    assert.ok(space.draftRev > before.draftRev)
    const restored = await data(`/spaces/${space.id}/properties`)
    assert.equal(restored.find(x => x.id === numberProperty.id).name, '数量')
    const restoredMappings = await data(`/mappings?spaceId=${space.id}`)
    assert.equal(restoredMappings.objects[0].id, object.id)
    assert.equal(restoredMappings.objects[0].metaSnapshotId, synced.snapshot.id)
    assert.equal((await data(`/datasources?spaceId=${space.id}`))[0].id, source.id)
    assert.ok((await data(`/data-files?spaceId=${space.id}`)).some(x => x.id === uploaded.file.id))
    const trace = await data(`/executions/${first.executionId}/trace`)
    assert.equal(trace.execution.subjectId, release.id)
    assert.deepEqual(trace.evidence, first.evidence)
    assert.deepEqual(trace.authorization, first.authorization)
    assert.equal((await data(`/releases?spaceId=${space.id}`)).length, 2)
    await rows([rowA])
  })
  // relay 可处于停机状态；保存事件创建时就固定的身份，不要求基线时已被投递。
  const events = (await data(`/events?spaceId=${space.id}`))
    .filter(x => [release.id, second.release.id].includes(x.releaseId))
    .map(({ id, releaseId, type, boundStatus, createdAt }) => ({ id, releaseId, type, boundStatus, createdAt }))
  assert.equal(events.length, 2)
  assert.ok(events.every(x => x.type === 'RELEASED' && x.boundStatus === 'READY'))
  await writeFile(new URL('docs/evidence/continuation/p4-data-http-smoke.json', root), JSON.stringify({
    checkedAt: new Date().toISOString(), spaceId: space.id, spaceCode: space.code,
    candidateId: first.candidate.id, releaseId: release.id, bundleSha256: release.bundleSha256,
    versionNo: release.versionNo,
    secondReleaseId: second.release.id, secondCandidateId: second.candidate.id,
    secondBundleSha256: second.release.bundleSha256, secondVersionNo: second.release.versionNo,
    endpointId: endpoint.id, operationId: operation.id,
    opCode: operation.code, sourceId: source.id, snapshotId: synced.snapshot.id, fileId: uploaded.file.id,
    snapshotSha256: synced.snapshot.sha256,
    executionId: first.executionId, evidence: first.evidence, authorization: first.authorization,
    events, expectedRows: [rowA], checks,
  }, null, 2) + '\n')
  console.log(`${checks.length} 项正式映射/授权/恢复 HTTP 验收通过。`)
} finally {
  if (token) await request('/auth/logout', 'POST').catch(() => {})
}
