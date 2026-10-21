/**
 * 真实本地 HTTP 验收系统管理。仅创建本轮 c8_http_* 账号、角色与空间，不清空业务库。
 * 凭据只在内存使用，报告保存资源标识、制品摘要与断言结果；结束时停用本轮账号并移除角色。
 */
import assert from 'node:assert/strict'
import { randomBytes } from 'node:crypto'
import { readFile, writeFile } from 'node:fs/promises'

const root = new URL('../', import.meta.url)
const readJson = async path => JSON.parse((await readFile(new URL(path, root), 'utf8')).replace(/^\uFEFF/, ''))
const config = await readJson('.runtime/local-env.json')
const runtime = await readJson('.runtime/c8-runtime.json')
const base = process.env.ONTO_API_BASE ?? 'http://127.0.0.1:18182/onto'
const tag = process.env.C8_EVIDENCE_TAG ?? 'stage'
assert.ok(['stage', 'final'].includes(tag), '验收报告标签只能为 stage 或 final')
const runId = Date.now().toString(36)
const checks = []
let adminToken, testUser, roleA, roleB, space
const temporaryPassword = randomBytes(24).toString('base64url')
const replacementPassword = randomBytes(24).toString('base64url')

async function call(path, { method = 'GET', body, token = adminToken, expected = 200, raw = false } = {}) {
  const response = await fetch((raw ? base.replace(/\/onto$/, '') : base) + path, {
    method, headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(30000),
  })
  const result = await response.json()
  assert.equal(response.status, expected, `${method} ${path}: ${result.code ?? 'INVALID_ENVELOPE'}`)
  if (expected === 200) assert.equal(result.code, 'OK')
  return expected === 200 ? result.data : result
}
async function check(name, work) { await work(); checks.push({ name, status: 'PASSED' }) }
const editRole = (role, status, permissions, expectedRevision = role.revision) => ({ name: role.name, description: '真实HTTP验收', status, permissions, expectedRevision })
const editUser = (user, status, roleIds) => ({ displayName: user.displayName, status, roleIds, expectedRevision: user.revision })

try {
  adminToken = (await call('/auth/login', { method: 'POST', token: null, body: { username: config.ONTO_BOOTSTRAP_USERNAME, password: config.ONTO_BOOTSTRAP_PASSWORD } })).token
  await check('部署引导账号获得显式系统管理权限', async () => {
    const permissions = await call('/auth/permissions')
    assert.ok(permissions.includes('system.user.write') && permissions.includes('system.role.write'))
    const catalog = await call('/system/permissions')
    assert.ok(catalog.some(item => item.code === 'service.execute'))
  })
  roleA = await call('/system/roles', { method: 'POST', body: { code: `c8_http_space_${runId}`, name: 'C8 HTTP 空间只读', description: '', permissions: ['space.read'] } })
  roleB = await call('/system/roles', { method: 'POST', body: { code: `c8_http_model_${runId}`, name: 'C8 HTTP 模型只读', description: '', permissions: ['model.read'] } })
  testUser = await call('/system/users', { method: 'POST', body: { username: `c8_http_${runId}`, displayName: 'C8 HTTP 权限验收', password: temporaryPassword, roleIds: [roleA.id, roleB.id] } })
  let userToken = (await call('/auth/login', { method: 'POST', token: null, body: { username: testUser.username, password: temporaryPassword } })).token
  await check('自定义多角色权限取并集且系统目录默认拒绝', async () => {
    assert.deepEqual(await call('/auth/permissions', { token: userToken }), ['model.read', 'space.read'])
    await call('/system/users', { token: userToken, expected: 403 })
  })
  space = await call('/spaces', { method: 'POST', body: { code: `c8_http_${runId}`, name: 'C8 HTTP 权限隔离验收', namespaceUri: `https://example.org/c8/http/${runId}/`, domain: '独立审核' } })
  await check('功能权限不自动授予空间可见性', async () => { await call(`/spaces/${space.id}`, { token: userToken, expected: 403 }) })
  space = await call(`/spaces/${space.id}/grants/${testUser.id}`, { method: 'PUT', body: { role: 'EDITOR', dataScope: null, attributes: {}, expectedDraftRev: space.draftRev } })
  await check('空间EDITOR仍需功能写权限，矩阵和编码路径均不得绕过', async () => {
    const body = { spaceId: space.id, name: '不得写入', parentId: null, description: '', deprecated: false, expectedDraftRev: space.draftRev }
    for (const path of ['/onto/classes', '/onto;x/classes', '/%6fnto/classes']) await call(path, { raw: true, method: 'POST', token: userToken, body, expected: 403 })
    assert.equal((await call(`/classes?spaceId=${space.id}`)).length, 0)
  })
  await check('角色停用对已有会话立即生效且修订冲突不覆盖配置', async () => {
    roleB = await call(`/system/roles/${roleB.id}`, { method: 'PUT', body: editRole(roleB, 'DISABLED', ['model.read']) })
    assert.deepEqual(await call('/auth/permissions', { token: userToken }), ['space.read'])
    assert.equal((await call(`/system/roles/${roleB.id}`, { method: 'PUT', body: editRole(roleB, 'ACTIVE', ['model.read'], 0), expected: 409 })).code, 'IAM_REVISION_CONFLICT')
    await call(`/system/roles/${roleA.id}?expectedRevision=${roleA.revision}`, { method: 'DELETE', expected: 409 })
  })
  await check('密码重置撤销旧会话并拒绝旧密码', async () => {
    testUser = await call(`/system/users/${testUser.id}/password`, { method: 'POST', body: { password: replacementPassword, expectedRevision: testUser.revision } })
    await call('/auth/me', { token: userToken, expected: 401 })
    await call('/auth/login', { method: 'POST', token: null, body: { username: testUser.username, password: temporaryPassword }, expected: 401 })
    userToken = (await call('/auth/login', { method: 'POST', token: null, body: { username: testUser.username, password: replacementPassword } })).token
  })
  await check('账号停用撤销当前会话，管理审计不含凭据', async () => {
    testUser = await call(`/system/users/${testUser.id}`, { method: 'PUT', body: editUser(testUser, 'DISABLED', []) })
    await call('/auth/me', { token: userToken, expected: 401 })
    const audit = await call('/system/audit?page=1&pageSize=100')
    assert.ok(audit.items.some(item => item.targetId === testUser.id && item.action === 'USER_PASSWORD_RESET'))
    const safe = JSON.stringify(audit)
    for (const secret of [temporaryPassword, replacementPassword, userToken, adminToken]) assert.ok(!safe.includes(secret))
  })
  await check('无引用自定义角色可删除且未知权限被拒绝', async () => {
    await call(`/system/roles/${roleA.id}?expectedRevision=${roleA.revision}`, { method: 'DELETE' }); roleA = null
    await call(`/system/roles/${roleB.id}?expectedRevision=${roleB.revision}`, { method: 'DELETE' }); roleB = null
    await call('/system/roles', { method: 'POST', body: { code: `c8_http_bad_${runId}`, name: '非法权限', description: '', permissions: ['*'] }, expected: 400 })
  })
  const evidence = { recordedAt: new Date().toISOString(), runtimeSha256: runtime.sha256, runId, spaceId: space.id, userId: testUser.id, checks }
  await writeFile(new URL(`docs/evidence/continuation/c8-iam-http-${tag}.json`, root), JSON.stringify(evidence, null, 2) + '\n')
  console.log(JSON.stringify({ status: 'PASSED', checks: checks.length, runtimeSha256: runtime.sha256 }))
} finally {
  // 失败也只回收本轮账号/角色，保留审计与空间作为可复核证据，绝不删除既有业务资源。
  if (adminToken && testUser) {
    try {
      const latest = (await call('/system/users')).find(user => user.id === testUser.id)
      if (latest && (latest.status !== 'DISABLED' || latest.roles.length)) await call(`/system/users/${latest.id}`, { method: 'PUT', body: editUser(latest, 'DISABLED', []) })
      for (const role of [roleA, roleB].filter(Boolean)) {
        const current = (await call('/system/roles')).find(value => value.id === role.id)
        if (current && current.memberCount === 0) await call(`/system/roles/${current.id}?expectedRevision=${current.revision}`, { method: 'DELETE' })
      }
    } catch { console.error('本轮临时配置清理未完成，请依据本轮资源标识检查；未输出凭据。') }
  }
}
