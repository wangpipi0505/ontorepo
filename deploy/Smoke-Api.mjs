/**
 * 在真实服务上验证首批闭环；只创建本次唯一编码的验收空间，不清空数据库。
 * 凭据从被 Git 忽略的本地环境文件读取，输出报告不会记录口令或会话令牌。
 */
import assert from 'node:assert/strict'
import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'

const root = new URL('../', import.meta.url)
const config = JSON.parse(await readFile(new URL('.runtime/local-env.json', root), 'utf8').then(text => text.replace(/^\uFEFF/, '')))
const base = process.env.ONTO_API_BASE ?? 'http://127.0.0.1:18182/onto'
const checks = []
let token

async function call(path, method = 'GET', body, expectedStatus = 200, authenticated = true) {
  const response = await fetch(base + path, {
    method,
    headers: {
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(authenticated && token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(20000),
  })
  const result = await response.json()
  assert.equal(response.status, expectedStatus, `${method} ${path}：${result.code} ${result.message}`)
  if (expectedStatus === 200) assert.equal(result.code, 'OK')
  return expectedStatus === 200 ? result.data : result
}

async function check(name, action) {
  await action()
  checks.push({ name, status: 'PASSED' })
}

const login = await call('/auth/login', 'POST', {
  username: config.ONTO_BOOTSTRAP_USERNAME,
  password: config.ONTO_BOOTSTRAP_PASSWORD,
})
token = login.token
assert.ok(token)
await check('匿名请求不能读取空间', async () => {
  await call('/spaces', 'GET', undefined, 401, false)
})
const runId = Date.now().toString(36)
let space = await call('/spaces', 'POST', {
  code: `acceptance-${runId}`,
  name: '本体首批闭环验收',
  namespaceUri: `https://example.org/ontology/acceptance-${runId}/`,
  domain: '算法任务',
})
const originalRev = space.draftRev
space = await call('/classes', 'POST', {
  spaceId: space.id, name: '任务', parentId: null, description: '需要编排算法的业务任务',
  deprecated: false, expectedDraftRev: space.draftRev,
})
let classes = await call(`/classes?spaceId=${space.id}`)
const task = classes.find(item => item.name === '任务')
assert.ok(task)
await check('过期草稿修订拒绝并发覆盖', async () => {
  const result = await call('/classes', 'POST', {
    spaceId: space.id, name: '过期写入', parentId: null, description: '',
    deprecated: false, expectedDraftRev: originalRev,
  }, 409)
  assert.equal(result.code, 'DRAFT_REV_CONFLICT')
})
space = await call('/classes', 'POST', {
  spaceId: space.id, name: '侦察任务', parentId: task.id, description: '',
  deprecated: false, expectedDraftRev: space.draftRev,
})
classes = await call(`/classes?spaceId=${space.id}`)
const child = classes.find(item => item.name === '侦察任务')
await check('层级禁环且失败不增加修订', async () => {
  await call(`/classes/${task.id}`, 'PUT', {
    spaceId: space.id, name: '任务', parentId: child.id, description: '',
    deprecated: false, expectedDraftRev: space.draftRev,
  }, 400)
  assert.equal((await call(`/spaces/${space.id}`)).draftRev, space.draftRev)
})
space = await call('/properties', 'POST', {
  spaceId: space.id, domainClassId: task.id, name: '任务编号',
  rangeType: 'STRING', required: true, description: '任务在业务域内的标识',
  expectedDraftRev: space.draftRev,
})
const queryTemplate = `PREFIX owl: <http://www.w3.org/2002/07/owl#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
SELECT ?class ?name WHERE { ?class a owl:Class ; rdfs:label ?name . } ORDER BY ?name`
space = await call('/operations', 'POST', {
  spaceId: space.id, code: 'list_classes', name: '查看模型对象', queryTemplate,
  maxRows: 50, timeoutMs: 2000, expectedDraftRev: space.draftRev,
})
await check('查询禁止 SERVICE 访问外部网络', async () => {
  await call('/operations', 'POST', {
    spaceId: space.id, code: 'bad_service', name: '非法外部查询',
    queryTemplate: 'SELECT ?s WHERE { SERVICE <http://127.0.0.1:9/> { ?s ?p ?o } }',
    maxRows: 50, timeoutMs: 2000, expectedDraftRev: space.draftRev,
  }, 400)
})
const candidate = await call(`/spaces/${space.id}/candidates`, 'POST', { expectedDraftRev: space.draftRev })
const frozenOps = await call(`/candidates/${candidate.id}/operations`)
const operation = frozenOps.find(item => item.code === 'list_classes')
assert.ok(operation)
const first = await call(`/candidates/${candidate.id}/trial`, 'POST', { operationId: operation.id })
await check('真实候选试执行返回模型和证据摘要', async () => {
  assert.equal(first.status, 'SUCCEEDED')
  assert.equal(first.subjectType, 'CANDIDATE')
  assert.equal(first.candidateSha256, candidate.sha256)
  assert.ok(first.rows.some(row => row.name === '任务'))
  assert.ok(first.traceId)
  assert.ok(first.steps.length > 0)
})
space = await call(`/classes/${task.id}`, 'PUT', {
  spaceId: space.id, name: '修改后的任务', parentId: null, description: '草稿修订',
  deprecated: false, expectedDraftRev: space.draftRev,
})
space = await call(`/operations/${operation.id}?spaceId=${space.id}&expectedDraftRev=${space.draftRev}`, 'DELETE')
await check('草稿改名和删除操作后旧候选仍可独立执行', async () => {
  const retained = await call(`/candidates/${candidate.id}/operations`)
  assert.ok(retained.some(item => item.id === operation.id))
  const second = await call(`/candidates/${candidate.id}/trial`, 'POST', { operationId: operation.id })
  assert.equal(second.status, 'SUCCEEDED')
  assert.deepEqual(second.rows, first.rows)
  assert.equal(second.candidateSha256, first.candidateSha256)
})
await check('执行记录可按空间追溯', async () => {
  const executions = await call(`/spaces/${space.id}/executions`)
  assert.ok(executions.some(item => item.id === first.id && item.subjectId === candidate.id))
})
await check('注销后原令牌不可继续访问', async () => {
  await call('/auth/logout', 'POST')
  await call('/spaces', 'GET', undefined, 401)
})

const report = { checkedAt: new Date().toISOString(), base, spaceId: space.id, candidateId: candidate.id, checks }
const evidenceDir = new URL(process.env.ONTO_EVIDENCE_DIR ?? 'docs/evidence/', root)
const output = new URL('api-smoke.json', evidenceDir)
await mkdir(evidenceDir, { recursive: true })
await writeFile(output, JSON.stringify(report, null, 2) + '\n')
console.log(`${checks.length} 项真实 API 闭环检查通过；报告：${fileURLToPath(output)}`)
