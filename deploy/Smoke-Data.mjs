/**
 * P3 真实 HTTP 验收：独立 onto_sources 样例库 + 本工程受管文件。
 * 权限、冻结、映射、关联两端过滤均通过公开管理/执行接口验证，不直接改业务表。
 * 五类外部实例不在本脚本中冒充通过；完整协议边界另见适配器真实测试记录。
 */
import assert from 'node:assert/strict'
import { readFile, writeFile } from 'node:fs/promises'

const root = new URL('../', import.meta.url)
const config = JSON.parse((await readFile(new URL('.runtime/local-env.json', root), 'utf8')).replace(/^\uFEFF/, ''))
const base = process.env.ONTO_API_BASE ?? 'http://127.0.0.1:18182/onto'
let token, space
const checks = []
async function request(path, method = 'GET', body, status = 200) {
  const multipart = body instanceof FormData
  const response = await fetch(base + path, {
    method,
    headers: {
      ...(body !== undefined && !multipart ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : multipart ? body : JSON.stringify(body),
    signal: AbortSignal.timeout(30000),
  })
  const envelope = await response.json()
  assert.equal(response.status, status, `${method} ${path}: ${envelope.code} ${envelope.message}`)
  if (status === 200) assert.equal(envelope.code, 'OK')
  return envelope
}
const data = async (...args) => (await request(...args)).data
async function mutate(path, body, method = 'POST') {
  space = await data(path, method, { spaceId: space.id, expectedDraftRev: space.draftRev, ...body })
}
async function check(name, action) {
  await action()
  checks.push({ name, status: 'PASSED' })
}
async function upload(name, type) {
  const form = new FormData()
  form.append('file', new Blob([await readFile(new URL(`docs/fixtures/p3/${name}`, root))], { type }), name)
  const result = await data(`/data-files?spaceId=${space.id}&expectedDraftRev=${space.draftRev}`, 'POST', form)
  space = result.space
  return result.file
}
function declaration(name) {
  return {
    inputSchema: { type: 'object', properties: {}, additionalProperties: false },
    outputSchema: { type: 'object', required: ['rows'], properties: { rows: { type: 'array', items: { type: 'object' } } } },
    assistantTool: {
      name, description: '按授权区域查询真实源记录',
      guard_attrs: ['region'], info_attrs: [], param_examples: [{}],
      exec: { readonly: true, side_effect: false, idempotent: true, cancellable: true },
      risk_level: 'READ_ONLY', error_semantics: [{ match: 'HTTP 403', semantic: 'NO_PERMISSION' }],
      return_spec: { modelSummary: { mode: 'TEMPLATE', template: name }, structuredPath: '$.data', fileRefPaths: [], cardTemplate: name },
    },
  }
}
const modelQuery = 'PREFIX owl:<http://www.w3.org/2002/07/owl#> SELECT ?s WHERE { ?s a owl:Class } ORDER BY ?s'
try {
  token = (await data('/auth/login', 'POST', {
    username: config.ONTO_BOOTSTRAP_USERNAME, password: config.ONTO_BOOTSTRAP_PASSWORD,
  })).token
  const code = `data-${Date.now().toString(36)}`
  space = await data('/spaces', 'POST', {
    code, name: '映射执行联合验收', namespaceUri: `https://example.org/${code}/`, domain: '算法任务',
  })
  for (const name of ['任务', '算法']) await mutate('/classes', { name, description: name, parentId: null, deprecated: false })
  const classes = await data(`/classes?spaceId=${space.id}`)
  const task = classes.find(x => x.name === '任务')
  const algorithm = classes.find(x => x.name === '算法')
  for (const [name, domainClassId] of [['任务编号', task.id], ['标准码', algorithm.id]]) {
    await mutate('/properties', { name, domainClassId, rangeType: 'STRING', required: true, description: name })
  }
  const properties = await data(`/spaces/${space.id}/properties`)
  const taskId = properties.find(x => x.name === '任务编号')
  const standardCode = properties.find(x => x.name === '标准码')
  await mutate('/relations', {
    name: '使用算法', fromClassId: task.id, toClassId: algorithm.id, cardinality: 'MANY_TO_ONE',
    transitive: false, symmetric: false, inverseName: null, description: '任务使用算法',
  })
  const relation = (await data(`/relations?spaceId=${space.id}`))[0]
  await mutate('/datasources', {
    code: 'local_samples', name: '独立任务样例库', kind: 'RELATIONAL',
    connection: {
      dialect: 'postgresql', url: `jdbc:postgresql://127.0.0.1:${config.ONTO_PG_PORT}/onto_sources`,
      user: config.ONTO_DB_USERNAME ?? 'onto', password: config.ONTO_DB_PASSWORD,
    },
    accessScope: ['onto_sample.tasks', 'onto_sample.algorithms'],
  })
  const source = (await data(`/datasources?spaceId=${space.id}`))[0]
  await check('源目录不泄露连接配置且真实三段探测全部通过', async () => {
    assert.equal(Object.hasOwn(source, 'connection'), false)
    assert.ok(!JSON.stringify(source).includes(config.ONTO_DB_PASSWORD))
    const result = await data(`/datasources/${source.id}/probe`, 'POST', { spaceId: space.id })
    assert.deepEqual(result.stages.map(x => x.stage), ['CONNECTIVITY', 'AUTHENTICATION', 'SCOPE'])
    assert.ok(result.stages.every(x => x.status === 'PASSED'))
  })
  const sync = await data(`/datasources/${source.id}/sync`, 'POST', { spaceId: space.id, expectedDraftRev: space.draftRev })
  space = sync.space
  const snapshot = sync.snapshot
  await check('不可变元数据包含任务算法两表真实主键及关联字段', async () => {
    const persisted = await data(`/datasources/${source.id}/snapshots/${snapshot.id}?spaceId=${space.id}`)
    assert.equal(persisted.sha256, snapshot.sha256)
    assert.deepEqual(persisted.resources.find(x => x.name === 'onto_sample.tasks').primaryKey, ['task_id'])
    assert.ok(persisted.resources.find(x => x.name === 'onto_sample.algorithms').fields.some(x => x.name === 'std_code'))
  })
  for (const [sourceResource, targetClassId, objectKeyExpr] of [
    ['onto_sample.tasks', task.id, 'task:${task_id}'],
    ['onto_sample.algorithms', algorithm.id, 'algorithm:${std_code}'],
  ]) await mutate('/mappings/objects', { datasourceId: source.id, sourceResource, targetClassId, objectKeyExpr, filter: null, metaSnapshotId: snapshot.id })
  let mappings = await data(`/mappings?spaceId=${space.id}`)
  const taskMap = mappings.objects.find(x => x.targetClassId === task.id)
  const algorithmMap = mappings.objects.find(x => x.targetClassId === algorithm.id)
  for (const [objectMappingId, sourceField, targetPropertyId] of [
    [taskMap.id, 'task_id', taskId.id], [algorithmMap.id, 'std_code', standardCode.id],
  ]) await mutate('/mappings/properties', { objectMappingId, sourceField, targetPropertyId, transform: { kind: 'IDENTITY' }, required: true })
  await mutate('/mappings/relations', {
    datasourceId: source.id, relationId: relation.id, fromObjectMappingId: taskMap.id, toObjectMappingId: algorithmMap.id,
    fromKeyExpr: 'task:${task_id}', toKeyExpr: 'algorithm:${std_code}',
    join: { fromField: 'algo_code', toField: 'std_code' }, cardinalityCheck: true, metaSnapshotId: snapshot.id,
  })
  mappings = await data(`/mappings?spaceId=${space.id}`)
  await mutate('/operations', { code: 'task_algorithms', name: '任务使用算法查询', queryTemplate: modelQuery, maxRows: 20, timeoutMs: 5000 })
  const operation = (await data(`/operations?spaceId=${space.id}`))[0]
  await mutate(`/operations/${operation.id}/data-query`, {
    dataQuery: { mode: 'RELATION', objectMappingId: taskMap.id, relationMappingId: mappings.relations[0].id, propertyIds: [taskId.id, standardCode.id], filters: [] },
  }, 'PUT')
  const guardResources = ['onto_sample.tasks', 'onto_sample.algorithms']
  const saveGuards = () => mutate(`/spaces/${space.id}/guards`, {
    guardCfg: { region: { type: 'STRING', filter_bindings: guardResources.map(table => ({ table, column: 'region', operator: 'IN' })) } },
  }, 'PUT')
  await saveGuards()
  const owner = (await data(`/spaces/${space.id}/grants`)).find(x => x.username === config.ONTO_BOOTSTRAP_USERNAME)
  await mutate(`/spaces/${space.id}/grants/${owner.userId}`, { role: 'OWNER', dataScope: { attr: 'region', in: ['A'] }, attributes: { region: 'A' } }, 'PUT')
  await mutate(`/operations/${operation.id}/assistant`, declaration('任务使用算法查询'), 'PUT')
  await check('三层映射四项完整性均通过', async () => {
    const validation = await data(`/mappings/validate?spaceId=${space.id}`)
    assert.equal(validation.valid, true, JSON.stringify(validation.checks))
    assert.equal(validation.checks.length, 4)
    assert.ok(validation.checks.every(x => x.passed))
  })
  const candidate = await data(`/spaces/${space.id}/candidates`, 'POST', { expectedDraftRev: space.draftRev })
  let execution
  await check('真实关系查询只返回A至A且拒绝B未分类及A至B跨标签关联', async () => {
    execution = await data(`/candidates/${candidate.id}/trial`, 'POST', { operationId: operation.id, input: {} })
    assert.equal(execution.status, 'SUCCEEDED', execution.errorCode)
    assert.equal(execution.rows.length, 1)
    assert.equal(execution.rows[0][taskId.id], 'T-A')
    assert.equal(execution.rows[0][standardCode.id], 'ALG-A')
    for (const kind of ['SOURCE_RECORD', 'META_SNAPSHOT', 'MAPPING_SNAPSHOT', 'ONTOLOGY_VERSION']) {
      assert.ok(execution.evidence.some(x => x.kind === kind), `缺少${kind}真实证据`)
    }
    const records = execution.evidence.filter(x => x.kind === 'SOURCE_RECORD')
    assert.equal(records.length, 2)
    assert.ok(records.some(x => x.ref.resource === 'onto_sample.tasks' && JSON.stringify(x.ref.key).includes('T-A')))
    assert.ok(records.some(x => x.ref.resource === 'onto_sample.algorithms' && JSON.stringify(x.ref.key).includes('ALG-A')))
    const row = JSON.stringify(execution.rows[0])
    assert.ok(row.includes('T-A') && row.includes('ALG-A'))
    for (const forbidden of ['T-B', 'T-NULL', 'T-CROSS', 'ALG-B']) assert.ok(!row.includes(forbidden))
  })
  await check('SERVICE与TRIAL使用同一冻结映射和授权结果', async () => {
    const result = await request(`/internal/candidates/${candidate.id}/ops/task_algorithms`, 'POST', {})
    assert.deepEqual(result.data.rows, execution.rows)
    assert.equal(result.meta.contentDigest, candidate.sha256)
    assert.equal(result.meta.preview, true)
  })
  await check('引用中的源删除被拒绝且返回可定位安全信息', async () => {
    const result = await request(`/datasources/${source.id}?spaceId=${space.id}&expectedDraftRev=${space.draftRev}`, 'DELETE', undefined, 409)
    assert.ok(result.message.includes(taskMap.id) || result.message.includes(operation.id))
    assert.ok(!result.message.includes(config.ONTO_DB_PASSWORD))
  })
  const file = await upload('tasks.csv', 'text/csv')
  const jsonFile = await upload('tasks.json', 'application/json')
  const excelFile = await upload('tasks.xlsx', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet')
  const document = await upload('domain.txt', 'text/plain')
  const wordDocument = await upload('domain.docx', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document')
  const pdfDocument = await upload('domain.pdf', 'application/pdf')
  const lake = JSON.parse(await readFile(new URL('docs/evidence/continuation/p3-lake-fixture.json', root), 'utf8'))
  const sourceKinds = [
    { kind: 'FILE', connection: { dialect: 'csv', fileId: file.id }, accessScope: [file.resource], resource: file.resource },
    { kind: 'FILE', connection: { dialect: 'json', fileId: jsonFile.id }, accessScope: [jsonFile.resource], resource: jsonFile.resource },
    { kind: 'FILE', connection: { dialect: 'excel', fileId: excelFile.id }, accessScope: [excelFile.resource], resource: excelFile.resource },
    {
      kind: 'HTTP', connection: { dialect: 'json', baseUrl: 'http://127.0.0.1:19180', endpoints: ['/tasks'], method: 'POST', body: {}, pageParam: 'page', pageSize: 2, maxPages: 3 },
      accessScope: ['/tasks'], resource: '/tasks',
    },
    {
      kind: 'LAKE', connection: {
        dialect: 's3-parquet', endpoint: config.ONTO_STORAGE_ENDPOINT, accessKey: config.ONTO_STORAGE_ACCESS_KEY,
        secretKey: config.ONTO_STORAGE_SECRET_KEY, bucket: config.ONTO_STORAGE_BUCKET,
      },
      accessScope: [lake.prefix], resource: lake.resource,
    },
  ]
  const protocolEvidence = []
  for (const item of sourceKinds) await check(`${item.kind}/${item.connection.dialect}真实登记探测同步映射执行及非SQL资源守卫`, async () => {
    const sourceCode = `${item.kind.toLowerCase()}_${item.connection.dialect.replaceAll('-', '_')}_sample`
    await mutate('/datasources', { code: sourceCode, name: `${item.kind}验收源`, kind: item.kind, connection: item.connection, accessScope: item.accessScope })
    const native = (await data(`/datasources?spaceId=${space.id}`)).find(x => x.code === sourceCode)
    assert.equal(Object.hasOwn(native, 'connection'), false)
    const probe = await data(`/datasources/${native.id}/probe`, 'POST', { spaceId: space.id })
    assert.ok(probe.stages.every(x => x.status === 'PASSED'), `${item.kind}探测失败`)
    const synced = await data(`/datasources/${native.id}/sync`, 'POST', { spaceId: space.id, expectedDraftRev: space.draftRev })
    space = synced.space
    assert.ok(synced.snapshot.resources.some(x => x.name === item.resource))
    await mutate('/mappings/objects', {
      datasourceId: native.id, sourceResource: item.resource, targetClassId: task.id,
      objectKeyExpr: 'task:${task_id}', filter: null, metaSnapshotId: synced.snapshot.id,
    })
    const mapped = (await data(`/mappings?spaceId=${space.id}`)).objects.find(x => x.datasourceId === native.id)
    await mutate('/mappings/properties', {
      objectMappingId: mapped.id, sourceField: 'task_id', targetPropertyId: taskId.id,
      transform: { kind: 'IDENTITY' }, required: true,
    })
    const opCode = `${sourceCode}_tasks`
    await mutate('/operations', { code: opCode, name: `${item.kind}任务查询`, queryTemplate: modelQuery, maxRows: 1, timeoutMs: 10000 })
    const nativeOp = (await data(`/operations?spaceId=${space.id}`)).find(x => x.code === opCode)
    await mutate(`/operations/${nativeOp.id}/data-query`, {
      dataQuery: { mode: 'OBJECT', objectMappingId: mapped.id, propertyIds: [taskId.id], filters: [] },
    }, 'PUT')
    guardResources.push(item.resource)
    await saveGuards()
    await mutate(`/operations/${nativeOp.id}/assistant`, declaration(`${item.kind}任务查询`), 'PUT')
    const frozen = await data(`/spaces/${space.id}/candidates`, 'POST', { expectedDraftRev: space.draftRev })
    const result = await data(`/candidates/${frozen.id}/trial`, 'POST', { operationId: nativeOp.id, input: {} })
    assert.equal(result.status, 'SUCCEEDED', result.errorCode)
    assert.equal(result.rows.length, 1)
    assert.equal(result.rows[0][taskId.id], 'T-A')
    protocolEvidence.push({
      kind: item.kind, dialect: item.connection.dialect, sourceId: native.id, snapshotId: synced.snapshot.id,
      candidateId: frozen.id, candidateSha256: frozen.sha256, opCode, operationId: nativeOp.id,
      executionId: result.id, resource: item.resource, expectedRows: result.rows,
    })
  })
  for (const sourceBody of [
    { label: 'TABLE', sourceKind: 'TABLE', datasourceId: source.id, metaSnapshotId: snapshot.id, resource: 'onto_sample.tasks' },
    { label: 'CSV', sourceKind: 'FILE', fileId: file.id },
    { label: 'JSON', sourceKind: 'FILE', fileId: jsonFile.id },
    { label: 'XLSX', sourceKind: 'FILE', fileId: excelFile.id },
    { label: 'TXT', sourceKind: 'DOCUMENT', fileId: document.id },
    { label: 'DOCX', sourceKind: 'DOCUMENT', fileId: wordDocument.id },
    { label: 'PDF', sourceKind: 'DOCUMENT', fileId: pdfDocument.id },
  ]) await check(`${sourceBody.label}真实来源抽取与显式人工确认`, async () => {
    const { label, ...sourceRequest } = sourceBody
    const before = await data(`/classes?spaceId=${space.id}`)
    const result = await data('/suggestions/extract', 'POST', { spaceId: space.id, expectedDraftRev: space.draftRev, ...sourceRequest })
    space = result.space
    const suggestions = await data(`/suggestions?spaceId=${space.id}&batchId=${result.batchId}`)
    assert.ok(suggestions.length > 0)
    assert.ok(suggestions.every(x => x.status === 'PENDING' && x.location.sourceName))
    assert.deepEqual(await data(`/classes?spaceId=${space.id}`), before)
    const proposed = suggestions.find(x => x.kind === 'CLASS')
    assert.ok(proposed, '样例须提取至少一个可人工确认的对象')
    const confirmedName = `${label}已确认对象`
    await mutate('/suggestions/decide', {
      decisions: [{ id: proposed.id, decision: 'ACCEPT', payload: { ...proposed.payload, name: confirmedName } }],
    })
    const after = await data(`/classes?spaceId=${space.id}`)
    assert.equal(after.length, before.length + 1)
    assert.ok(after.some(x => x.name === confirmedName))
    const reviewed = await data(`/suggestions?spaceId=${space.id}&batchId=${result.batchId}`)
    assert.equal(reviewed.find(x => x.id === proposed.id).status, 'ACCEPTED')
    assert.ok(reviewed.filter(x => x.id !== proposed.id).every(x => x.status === 'PENDING'))
  })
  await check('后续草稿源范围修改不污染旧候选的冻结范围映射及结果', async () => {
    await mutate(`/datasources/${source.id}`, {
      code: source.code, name: source.name, kind: source.kind, connection: null,
      accessScope: ['onto_sample.tasks'],
    }, 'PUT')
    const result = await data(`/candidates/${candidate.id}/trial`, 'POST', { operationId: operation.id, input: {} })
    assert.equal(result.status, 'SUCCEEDED', result.errorCode)
    assert.deepEqual(result.rows, execution.rows)
    assert.equal(result.candidateSha256, candidate.sha256)
  })
  await writeFile(new URL('docs/evidence/continuation/p3-http-smoke.json', root), JSON.stringify({
    checkedAt: new Date().toISOString(), spaceId: space.id, sourceId: source.id, snapshotId: snapshot.id,
    candidateId: candidate.id, candidateSha256: candidate.sha256, operationId: operation.id,
    opCode: operation.code, expectedRows: execution.rows, executionId: execution.id, protocolEvidence, checks,
  }, null, 2) + '\n')
  console.log(`${checks.length} 项 P3 真实 HTTP 验收通过。`)
} finally {
  if (token) await request('/auth/logout', 'POST').catch(() => {})
}
