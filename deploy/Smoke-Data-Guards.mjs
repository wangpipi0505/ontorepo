/**
 * T02 的真实 HTTP 源补验：tenant_label 没有绑定时，三个候选入口全部拒绝。
 * 环回服务器计数实际源请求；元数据同步后清零，执行阶段必须保持零请求。
 * 所有模型及源均属于新验收空间，不停用共享样例或修改其他候选。
 */
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { once } from 'node:events'
import { readFile, writeFile } from 'node:fs/promises'

const root = new URL('../', import.meta.url)
const config = JSON.parse((await readFile(new URL('.runtime/local-env.json', root), 'utf8')).replace(/^\uFEFF/, ''))
const base = process.env.ONTO_API_BASE ?? 'http://127.0.0.1:18182/onto'
let token, space, sourceRequests = 0
const checks = []
const server = createServer((req, res) => {
  sourceRequests++
  res.writeHead(200, { 'Content-Type': 'application/json' })
  res.end(JSON.stringify([{ task_id: 'T-A', region: 'A' }]))
})
server.listen(0, '127.0.0.1')
await once(server, 'listening')
const sourceBase = `http://127.0.0.1:${server.address().port}`
async function request(path, method = 'GET', body, status = 200) {
  const response = await fetch(base + path, {
    method,
    headers: {
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(60000),
  })
  const envelope = await response.json()
  if (status !== null) assert.equal(response.status, status, `${method} ${path}: ${envelope.code} ${envelope.message}`)
  if (response.status === 200) assert.equal(envelope.code, 'OK')
  return { status: response.status, envelope }
}
const data = async (...args) => (await request(...args)).envelope.data
async function mutate(path, body, method = 'POST') {
  space = await data(path, method, { spaceId: space.id, expectedDraftRev: space.draftRev, ...body })
}
try {
  token = (await data('/auth/login', 'POST', {
    username: config.ONTO_BOOTSTRAP_USERNAME, password: config.ONTO_BOOTSTRAP_PASSWORD,
  })).token
  const code = `guard-${Date.now().toString(36)}`
  space = await data('/spaces', 'POST', {
    code, name: 'T02 三入口零源访问', namespaceUri: `https://example.org/${code}/`, domain: '守卫验收',
  })
  await mutate('/classes', { name: '任务', description: '真实HTTP源任务', parentId: null, deprecated: false })
  const task = (await data(`/classes?spaceId=${space.id}`))[0]
  await mutate('/properties', { name: '任务编号', domainClassId: task.id, rangeType: 'STRING', required: true, description: '编号' })
  const property = (await data(`/spaces/${space.id}/properties`))[0]
  await mutate('/datasources', {
    code: 'counted_http', name: '计数HTTP源', kind: 'HTTP',
    connection: { dialect: 'json', baseUrl: sourceBase, endpoints: ['/tasks'] }, accessScope: ['/tasks'],
  })
  const source = (await data(`/datasources?spaceId=${space.id}`))[0]
  const synced = await data(`/datasources/${source.id}/sync`, 'POST', { spaceId: space.id, expectedDraftRev: space.draftRev })
  space = synced.space
  assert.ok(sourceRequests > 0, '同步必须真实访问本次HTTP源')
  await mutate('/mappings/objects', {
    datasourceId: source.id, sourceResource: '/tasks', targetClassId: task.id,
    objectKeyExpr: 'task:${task_id}', filter: null, metaSnapshotId: synced.snapshot.id,
  })
  const object = (await data(`/mappings?spaceId=${space.id}`)).objects[0]
  await mutate('/mappings/properties', {
    objectMappingId: object.id, sourceField: 'task_id', targetPropertyId: property.id, transform: { kind: 'IDENTITY' }, required: true,
  })
  await mutate('/operations', {
    code: 'guarded_tasks', name: '守卫任务查询',
    queryTemplate: 'PREFIX owl:<http://www.w3.org/2002/07/owl#> SELECT ?s WHERE { ?s a owl:Class } ORDER BY ?s',
    maxRows: 1, timeoutMs: 5000,
  })
  const operation = (await data(`/operations?spaceId=${space.id}`))[0]
  await mutate(`/operations/${operation.id}/data-query`, {
    dataQuery: { mode: 'OBJECT', objectMappingId: object.id, propertyIds: [property.id], filters: [] },
  }, 'PUT')
  // region 有合法绑定；tenant_label 已定义类型但故意没有执行绑定，明确核验 NO_FILTER_BINDING。
  await mutate(`/spaces/${space.id}/guards`, {
    guardCfg: {
      region: { type: 'STRING', filter_binding: { table: '/tasks', column: 'region', operator: 'IN' } },
      tenant_label: { type: 'STRING' },
    },
  }, 'PUT')
  const owner = (await data(`/spaces/${space.id}/grants`)).find(x => x.username === config.ONTO_BOOTSTRAP_USERNAME)
  await mutate(`/spaces/${space.id}/grants/${owner.userId}`, {
    role: 'OWNER', dataScope: { attr: 'region', in: ['A'] }, attributes: { region: 'A' },
  }, 'PUT')
  await mutate(`/operations/${operation.id}/assistant`, {
    inputSchema: { type: 'object', properties: {}, additionalProperties: false },
    outputSchema: { type: 'object', required: ['rows'], properties: { rows: { type: 'array', items: { type: 'object' } } } },
    assistantTool: {
      name: '守卫任务查询', description: '缺少tenant_label必须拒绝', guard_attrs: ['region', 'tenant_label'], info_attrs: [], param_examples: [{}],
      exec: { readonly: true, side_effect: false, idempotent: true, cancellable: true },
      risk_level: 'READ_ONLY', error_semantics: [{ match: 'HTTP 403', semantic: 'NO_PERMISSION' }],
      return_spec: { modelSummary: { mode: 'TEMPLATE', template: '任务结果' }, structuredPath: '$.data', fileRefPaths: [], cardTemplate: '任务结果' },
    },
  }, 'PUT')
  await mutate('/test-cases', {
    name: '缺绑定必须失败', question: '任务查询', operationId: operation.id, input: {},
    expected: { status: 'SUCCEEDED', rows: [] }, enabled: true, mode: 'DIRECT', matchMode: 'EXACT',
  })
  const candidate = await data(`/spaces/${space.id}/candidates`, 'POST', { expectedDraftRev: space.draftRev })
  sourceRequests = 0
  for (const [entry, path, body] of [
    ['TRIAL', `/candidates/${candidate.id}/trial`, { operationId: operation.id, input: {} }],
    ['SERVICE', `/internal/candidates/${candidate.id}/ops/${operation.code}`, {}],
  ]) {
    const result = await request(path, 'POST', body, 400)
    assert.equal(result.envelope.code, 'GUARD_ATTR_UNRESOLVED')
    assert.equal(result.envelope.reason, 'NO_FILTER_BINDING')
    assert.equal(Object.hasOwn(result.envelope, 'meta'), false)
    assert.equal(sourceRequests, 0)
    checks.push({ entry, httpStatus: result.status, code: result.envelope.code,
      reason: result.envelope.reason, denied: true, sourceRequests, status: 'PASSED' })
  }
  const tested = await request(`/candidates/${candidate.id}/tests`, 'POST', undefined, null)
  const publicationCandidate = !!candidate.preReleaseId
  if (publicationCandidate) {
    // 新发布候选必须在契约失败时前置阻断，不能仅因最终测试结果为false便放过错误推进。
    assert.equal(tested.status, 409)
    assert.notEqual(tested.envelope.code, 'OK')
    assert.equal(Object.hasOwn(tested.envelope, 'meta'), false)
    const publication = await data(`/candidates/${candidate.id}/publication`)
    assert.equal(publication.status, 'FAILED')
    // 此夹具在冻结契约编译阶段即失败，机器门禁尚未开始，不能要求伪造一份CONTRACT执行报告。
    assert.equal(publication.checks.some(check => check.kind === 'TEST' && check.conclusion === 'PASSED'), false)
    await request(`/candidates/${candidate.id}/prepare`, 'POST', {
      expectedDraftRev: space.draftRev, candidateSha256: candidate.sha256,
    }, 409)
    const executions = await data(`/spaces/${space.id}/executions`)
    assert.equal(executions.filter(execution => execution.caller === 'LOCAL_TEST').length, 0)
    assert.equal((await data(`/releases?spaceId=${space.id}`)).length, 0)
    assert.equal((await data(`/events?spaceId=${space.id}`)).filter(event => event.type === 'RELEASED').length, 0)
  } else {
    assert.equal(tested.status, 200)
    assert.equal(tested.envelope.data.passed, false, '失败执行不能因期望空结果而通过')
    assert.equal(tested.envelope.data.results.length, 1)
    assert.equal(tested.envelope.data.results[0].passed, false)
  }
  assert.equal(sourceRequests, 0)
  checks.push({
    entry: 'LOCAL_TEST', httpStatus: tested.status, code: tested.envelope.code,
    testRunPassed: tested.status === 200 ? tested.envelope.data.passed : null,
    blockedBeforeTests: tested.status === 409, sourceRequests, status: 'PASSED',
  })
  // 分开保留P3旧候选运行期拒绝和P4新候选前置门禁证据，不覆盖原验收索引。
  const report = publicationCandidate ? 'p4-guards-http-smoke.json' : 'p3-guards-http-smoke.json'
  const evidenceDir = new URL(process.env.ONTO_EVIDENCE_DIR ?? 'docs/evidence/continuation/', root)
  await writeFile(new URL(report, evidenceDir), JSON.stringify({
    checkedAt: new Date().toISOString(), spaceId: space.id, candidateId: candidate.id,
    operationId: operation.id, candidateSha256: candidate.sha256, sourceRequests, checks,
  }, null, 2) + '\n')
  console.log('tenant_label 无绑定：TRIAL、LOCAL_TEST、SERVICE 均未通过，真实 HTTP 源请求数为 0。')
} finally {
  if (token) await request('/auth/logout', 'POST').catch(() => {})
  server.closeAllConnections()
  await new Promise(resolve => server.close(resolve))
}
