/**
 * P2 真实 HTTP 验收：只创建独立空间，不清理已有业务数据。
 * token/密码只在内存中使用；报告仅保存资源标识、摘要和检查结果。
 */
import assert from 'node:assert/strict'
import { readFile, writeFile, mkdir } from 'node:fs/promises'

const root = new URL('../', import.meta.url)
const config = JSON.parse((await readFile(new URL('.runtime/local-env.json', root), 'utf8')).replace(/^\uFEFF/, ''))
const base = process.env.ONTO_API_BASE ?? 'http://127.0.0.1:18182/onto'
const checks = []
let token, space, operation, candidate

async function request(path, method = 'GET', body, status = 200) {
  const response = await fetch(base + path, {
    method,
    headers: {
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(20000),
  })
  const envelope = await response.json()
  assert.equal(response.status, status, `${method} ${path}: ${envelope.code} ${envelope.message}`)
  if (status === 200) assert.equal(envelope.code, 'OK')
  return envelope
}
const data = async (...args) => (await request(...args)).data
async function check(name, action) {
  await action()
  checks.push({ name, status: 'PASSED' })
}
async function mutate(path, body, method = 'POST') {
  space = await data(path, method, { spaceId: space.id, expectedDraftRev: space.draftRev, ...body })
}
function declaration(guards = ['region']) {
  return {
    inputSchema: { type: 'object', properties: {}, additionalProperties: false },
    outputSchema: {
      type: 'object', required: ['columns', 'rows'],
      properties: {
        columns: { type: 'array', items: { type: 'string' } },
        rows: { type: 'array', items: { type: 'object' } },
      },
    },
    assistantTool: {
      name: '任务模型查询', description: '查询任务模型中的对象名称',
      guard_attrs: guards, info_attrs: [], param_examples: [{}],
      exec: { readonly: true, side_effect: false, idempotent: true, cancellable: true },
      risk_level: 'READ_ONLY',
      error_semantics: [{ match: 'HTTP 403', semantic: 'NO_PERMISSION' }],
      return_spec: {
        modelSummary: { mode: 'TEMPLATE', template: '模型查询结果' },
        structuredPath: '$.data', fileRefPaths: [], cardTemplate: '模型查询结果',
      },
    },
  }
}

try {
  token = (await data('/auth/login', 'POST', {
    username: config.ONTO_BOOTSTRAP_USERNAME, password: config.ONTO_BOOTSTRAP_PASSWORD,
  })).token
  const code = `supply-${Date.now().toString(36)}`
  space = await data('/spaces', 'POST', {
    code, name: '知识供给联合验收', namespaceUri: `https://example.org/${code}/`, domain: '算法任务',
  })
  await mutate('/classes', { name: '任务', parentId: null, description: '任务模型', deprecated: false })
  const task = (await data(`/classes?spaceId=${space.id}`))[0]
  await mutate('/operations', {
    code: 'list_classes', name: '任务模型查询',
    queryTemplate: 'PREFIX owl:<http://www.w3.org/2002/07/owl#> PREFIX rdfs:<http://www.w3.org/2000/01/rdf-schema#> SELECT ?name WHERE { ?s a owl:Class; rdfs:label ?name } ORDER BY ?name',
    maxRows: 20, timeoutMs: 2000,
  })
  operation = (await data(`/operations?spaceId=${space.id}`))[0]
  await mutate(`/spaces/${space.id}/guards`, {
    guardCfg: { region: { type: 'STRING', authorization_rule: { kind: 'PRINCIPAL_ATTRIBUTE', attribute: 'region' } } },
  }, 'PUT')
  const owner = (await data(`/spaces/${space.id}/grants`)).find(x => x.username === config.ONTO_BOOTSTRAP_USERNAME)
  assert.ok(owner)
  await mutate(`/spaces/${space.id}/grants/${owner.userId}`, {
    role: 'OWNER', dataScope: { attr: 'region', in: ['华东'] }, attributes: { region: '华东' },
  }, 'PUT')
  await mutate(`/operations/${operation.id}/assistant`, declaration(), 'PUT')
  await mutate('/knowledge', {
    term: '任务', aliases: ['任务模型', '业务任务'], definition: '查询任务模型中的对象名称',
    conceptRef: task.id, sourceRef: '本地联合验收/任务定义',
  })
  candidate = await data(`/spaces/${space.id}/candidates`, 'POST', { expectedDraftRev: space.draftRev })
  let contracts, pack, first
  await check('候选契约四元组为null且外层摘要匹配', async () => {
    contracts = await data(`/candidates/${candidate.id}/contracts`)
    assert.equal(contracts.candidateSha256, candidate.sha256)
    assert.equal(contracts.manifests.length, 1)
    const meta = contracts.manifests[0].ontology_meta
    assert.equal(meta.preview, true)
    for (const key of ['spaceCode', 'releaseId', 'version', 'contentDigest']) assert.equal(meta[key], null)
  })
  await check('内部候选调用返回业务结果和真实预览摘要', async () => {
    first = await request(`/internal/candidates/${candidate.id}/ops/list_classes`, 'POST', {})
    assert.ok(first.data.rows.some(row => row.name === '任务'))
    assert.deepEqual(first.meta, { spaceCode: space.code, releaseId: null, version: null, contentDigest: candidate.sha256, preview: true })
  })
  await check('冻结知识包和Lucene结果含真实来源关联', async () => {
    pack = await data(`/candidates/${candidate.id}/context-pack`)
    const search = await data(`/candidates/${candidate.id}/knowledge-search?q=${encodeURIComponent('业务任务')}&limit=10`)
    assert.equal(search.candidateSha256, candidate.sha256)
    assert.ok(search.hits.length)
    const entry = pack.termDictionary.find(x => (x.id ?? x.entryId) === search.hits[0].entryId)
    assert.equal(entry.sourceRef, '本地联合验收/任务定义')
    assert.ok(search.hits[0].score > 0)
  })
  await check('Schema失败无成功meta且实际失败事实可追溯', async () => {
    const response = await request(`/internal/candidates/${candidate.id}/ops/list_classes`, 'POST', { unexpected: true }, 400)
    assert.equal(response.code, 'SCHEMA_VALIDATION_FAILED')
    assert.equal(Object.hasOwn(response, 'meta'), false)
    const executions = await data(`/spaces/${space.id}/executions`)
    assert.ok(executions.some(x => x.status === 'FAILED' && x.errorCode === 'SCHEMA_VALIDATION_FAILED'))
  })
  const knowledge = (await data(`/knowledge?spaceId=${space.id}`))[0]
  await mutate(`/knowledge/${knowledge.id}`, {
    term: '更新后的任务', aliases: [], definition: '新草稿', conceptRef: task.id, sourceRef: '新草稿来源',
  }, 'PUT')
  await mutate(`/operations/${operation.id}/assistant`, declaration(['tenant_label']), 'PUT')
  await check('修改声明与知识草稿后旧候选仍保持原供给', async () => {
    assert.deepEqual(await data(`/candidates/${candidate.id}/contracts`), contracts)
    assert.deepEqual(await data(`/candidates/${candidate.id}/context-pack`), pack)
    assert.deepEqual((await request(`/internal/candidates/${candidate.id}/ops/list_classes`, 'POST', {})).data, first.data)
  })
  await mutate('/test-cases', {
    name: '无绑定必须拒绝', question: '直接查询模型', operationId: operation.id, input: {},
    expected: { status: 'SUCCEEDED', columns: first.data.columns, rows: first.data.rows },
    enabled: true, matchMode: 'EXACT', mode: 'DIRECT',
  })
  const invalid = await data(`/spaces/${space.id}/candidates`, 'POST', { expectedDraftRev: space.draftRev })
  await check('tenant_label无绑定三入口拒绝且不产生查询执行步骤', async () => {
    for (const [path, body] of [
      [`/candidates/${invalid.id}/trial`, { operationId: operation.id }],
      [`/internal/candidates/${invalid.id}/ops/list_classes`, {}],
    ]) {
      const response = await request(path, 'POST', body, 400)
      assert.equal(response.code, 'GUARD_ATTR_UNRESOLVED')
      assert.equal(Object.hasOwn(response, 'meta'), false)
    }
    const tests = await data(`/candidates/${invalid.id}/tests`, 'POST')
    assert.equal(tests.passed, false)
    const failed = (await data(`/spaces/${space.id}/executions`)).filter(x => x.subjectId === invalid.id)
    assert.equal(failed.length, 3)
    assert.ok(failed.every(x => x.status === 'FAILED' && !x.steps.some(step => step.kind === 'QUERY_EXEC' || step.kind === 'MODEL_SELECT')))
  })
  const evidence = new URL('docs/evidence/continuation/', root)
  await mkdir(evidence, { recursive: true })
  await writeFile(new URL('p2-http-smoke.json', evidence), JSON.stringify({
    checkedAt: new Date().toISOString(), spaceId: space.id, candidateId: candidate.id,
    invalidCandidateId: invalid.id, candidateSha256: candidate.sha256, checks,
  }, null, 2) + '\n')
  console.log(`${checks.length} 项 P2 真实 HTTP 验收通过，报告 docs/evidence/continuation/p2-http-smoke.json`)
} finally {
  if (token) await request('/auth/logout', 'POST').catch(() => {})
}
