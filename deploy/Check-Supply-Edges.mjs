/**
 * 通过真实 HTTP 验证任意 JSON 根在三入口保持一致，以及纯标点知识不能制造 NL 命中。
 * 只创建专用验收空间；凭据仅在内存使用，不写报告，不修改已有候选。
 */
import assert from 'node:assert/strict'
import { readFile, writeFile } from 'node:fs/promises'

const root = new URL('../', import.meta.url)
const config = JSON.parse((await readFile(new URL('.runtime/local-env.json', root), 'utf8')).replace(/^\uFEFF/, ''))
const base = process.env.ONTO_API_BASE ?? 'http://127.0.0.1:18182/onto'
const checks = []
let token, space

async function request(path, method = 'GET', body) {
  const response = await fetch(base + path, {
    method,
    headers: {
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    // null 是业务输入，只有 undefined 表示没有请求体。
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(20000),
  })
  const envelope = await response.json()
  assert.equal(response.status, 200, `${method} ${path}: ${envelope.code} ${envelope.message}`)
  assert.equal(envelope.code, 'OK')
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

try {
  token = (await data('/auth/login', 'POST', {
    username: config.ONTO_BOOTSTRAP_USERNAME, password: config.ONTO_BOOTSTRAP_PASSWORD,
  })).token
  const code = `edges-${Date.now().toString(36)}`
  space = await data('/spaces', 'POST', {
    code, name: '供给边界真实验收', namespaceUri: `https://example.org/${code}/`, domain: '边界验证',
  })
  await mutate('/classes', { name: '任务', parentId: null, description: '固定查询对象', deprecated: false })
  await mutate('/operations', {
    code: 'list_classes', name: '任务模型查询',
    queryTemplate: 'PREFIX owl:<http://www.w3.org/2002/07/owl#> PREFIX rdfs:<http://www.w3.org/2000/01/rdf-schema#> SELECT ?name WHERE { ?s a owl:Class; rdfs:label ?name } ORDER BY ?name',
    maxRows: 20, timeoutMs: 5000,
  })
  const operation = (await data(`/operations?spaceId=${space.id}`))[0]
  await mutate(`/spaces/${space.id}/guards`, {
    guardCfg: { _region: { type: 'STRING', authorization_rule: { kind: 'PRINCIPAL_ATTRIBUTE', attribute: '_region' } } },
  }, 'PUT')
  const owner = (await data(`/spaces/${space.id}/grants`)).find(x => x.username === config.ONTO_BOOTSTRAP_USERNAME)
  assert.ok(owner)
  await mutate(`/spaces/${space.id}/grants/${owner.userId}`, {
    role: 'OWNER', dataScope: { attr: '_region', in: ['A'] }, attributes: { _region: 'A' },
  }, 'PUT')
  const values = [['x'], 'value', 0, false, true, null]
  await mutate(`/operations/${operation.id}/assistant`, {
    inputSchema: { type: ['array', 'string', 'number', 'boolean', 'null'] },
    outputSchema: {
      type: 'object', required: ['columns', 'rows'],
      properties: { columns: { type: 'array', items: { type: 'string' } }, rows: { type: 'array', items: { type: 'object' } } },
    },
    assistantTool: {
      name: '任务模型查询', description: '列出冻结任务模型名称',
      guard_attrs: ['_region'], info_attrs: [], param_examples: values,
      exec: { readonly: true, side_effect: false, idempotent: true, cancellable: true },
      risk_level: 'READ_ONLY', error_semantics: [{ match: 'HTTP 403', semantic: 'NO_PERMISSION' }],
      return_spec: {
        modelSummary: { mode: 'TEMPLATE', template: '模型查询结果' },
        structuredPath: '$.data', fileRefPaths: [], cardTemplate: '模型查询结果',
      },
    },
  }, 'PUT')
  const expected = { status: 'SUCCEEDED', columns: ['name'], rows: [{ name: '任务' }] }
  for (const [index, input] of values.entries()) {
    await mutate('/test-cases', {
      name: `JSON根-${index + 1}`, question: '直接执行', operationId: operation.id, input,
      expected, enabled: true, matchMode: 'EXACT', mode: 'DIRECT',
    })
  }
  const candidate = await data(`/spaces/${space.id}/candidates`, 'POST', { expectedDraftRev: space.draftRev })
  for (const [index, input] of values.entries()) {
    await check(`TRIAL原样接收JSON根-${index + 1}`, async () => {
      const result = await data(`/candidates/${candidate.id}/trial`, 'POST', { operationId: operation.id, input })
      assert.equal(result.status, 'SUCCEEDED', result.errorCode)
      assert.deepEqual(result.rows, expected.rows)
    })
    await check(`SERVICE原样接收JSON根-${index + 1}`, async () => {
      const result = await request(`/internal/candidates/${candidate.id}/ops/list_classes`, 'POST', input)
      assert.deepEqual(result.data.rows, expected.rows)
      assert.equal(result.meta.preview, true)
      assert.equal(result.meta.contentDigest, candidate.sha256)
    })
  }
  await check('LOCAL_TEST六种JSON根全部通过完整精确断言', async () => {
    const result = await data(`/candidates/${candidate.id}/tests`, 'POST')
    assert.equal(result.results.length, 6)
    assert.equal(result.passed, true)
    assert.ok(result.results.every(x => x.passed && x.executionId))
  })
  await mutate('/knowledge', {
    term: '!!!', aliases: ['???'], definition: '用于检查空归一化词条边界', conceptRef: null,
    sourceRef: '独立HTTP回归/合成词条',
  })
  await mutate('/test-cases', {
    name: '陌生问题不得误命中', question: '完全陌生的问题', operationId: operation.id, input: null,
    expected, enabled: true, matchMode: 'EXACT', mode: 'NL',
  })
  const negative = await data(`/spaces/${space.id}/candidates`, 'POST', { expectedDraftRev: space.draftRev })
  await check('纯标点知识不能让NL无命中用例假通过', async () => {
    const result = await data(`/candidates/${negative.id}/tests`, 'POST')
    const failed = result.results.find(x => x.name === '陌生问题不得误命中')
    assert.equal(result.passed, false)
    assert.ok(failed?.executionId)
    assert.equal(failed.passed, false)
    const facts = await data(`/spaces/${space.id}/executions`)
    const execution = facts.find(x => x.id === failed.executionId)
    assert.equal(execution?.errorCode, 'NL_OPERATION_NOT_FOUND')
    assert.ok(!execution.steps.some(x => ['QUERY_EXEC', 'MODEL_SELECT'].includes(x.kind)))
  })
  await writeFile(new URL('docs/evidence/continuation/p2-http-edges.json', root), JSON.stringify({
    checkedAt: new Date().toISOString(), spaceId: space.id, candidateId: candidate.id,
    negativeCandidateId: negative.id, candidateSha256: candidate.sha256, checks,
  }, null, 2) + '\n')
  console.log(`${checks.length} 项供给边界真实 HTTP 验收通过。`)
} finally {
  if (token) await request('/auth/logout', 'POST').catch(() => {})
}
