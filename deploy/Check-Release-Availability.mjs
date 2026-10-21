/**
 * 对原正式数据版本验证对象存储的真实停机/恢复，脚本本身不结束进程或改写对象。
 * --unavailable 须在独立MinIO已停止时执行；恢复服务后使用 --recovered。
 * 网络不可达必须拒绝消费但保留READY，不能制造CORRUPTED或损坏告警。
 */
import assert from 'node:assert/strict'
import { readFile, writeFile } from 'node:fs/promises'

const mode = process.argv[2]
assert.ok(['--unavailable', '--recovered'].includes(mode), '请显式选择 --unavailable 或 --recovered')
const unavailable = mode === '--unavailable'
const root = new URL('../', import.meta.url)
const config = JSON.parse((await readFile(new URL('.runtime/local-env.json', root), 'utf8')).replace(/^\uFEFF/, ''))
const previous = JSON.parse(await readFile(new URL('docs/evidence/continuation/p4-data-http-smoke.json', root), 'utf8'))
const base = process.env.ONTO_API_BASE ?? 'http://127.0.0.1:18182/onto'
let token
const checks = []
async function request(path, method = 'GET', body, status = 200) {
  const response = await fetch(base + path, {
    method,
    headers: {
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(45000),
  })
  const envelope = await response.json()
  assert.equal(response.status, status, `${method} ${path}: ${envelope.code}`)
  if (status === 200) assert.equal(envelope.code, 'OK')
  return { ...envelope, releaseHeader: response.headers.get('X-Ontology-Release') }
}
try {
  token = (await request('/auth/login', 'POST', {
    username: config.ONTO_BOOTSTRAP_USERNAME, password: config.ONTO_BOOTSTRAP_PASSWORD,
  })).data.token
  const integrity = (await request(`/releases/${previous.releaseId}/integrity-check`, 'POST')).data
  assert.equal(integrity.release.status, 'READY')
  assert.equal(integrity.release.bundleSha256, previous.bundleSha256)
  assert.equal(integrity.integrity.conclusion, unavailable ? 'UNAVAILABLE' : 'PASSED')
  checks.push({ name: unavailable ? '实际停机识别为不可达且保留READY及原摘要' : '恢复后四类原制品重新校验通过', status: 'PASSED' })

  const result = await request(`/r/${previous.spaceCode}/releases/${previous.releaseId}/ops/${previous.opCode}`, 'POST', {}, unavailable ? 503 : 200)
  if (unavailable) {
    assert.equal(result.code, 'ARTIFACT_STORAGE_UNAVAILABLE')
    assert.equal(Object.hasOwn(result, 'meta'), false)
    assert.equal(result.releaseHeader, null)
  } else {
    assert.deepEqual(result.data.rows, previous.expectedRows)
    assert.equal(result.meta.releaseId, previous.releaseId)
    assert.equal(result.meta.contentDigest, previous.bundleSha256)
    assert.equal(result.meta.preview, false)
    assert.equal(result.releaseHeader, previous.releaseId)
  }
  checks.push({ name: unavailable ? '消费503且无成功meta或版本头' : '恢复后调用原版本与原结果成功', status: 'PASSED' })

  const events = (await request(`/events?spaceId=${previous.spaceId}`)).data
  assert.equal(events.filter(event => event.type === 'CORRUPTED_ALERT').length, 0)
  const releases = (await request(`/releases?spaceId=${previous.spaceId}`)).data
  assert.ok(releases.every(release => release.status === 'READY'))
  checks.push({ name: '未伪造损坏状态或损坏告警', status: 'PASSED' })
  await writeFile(new URL(`docs/evidence/continuation/p4-storage-${unavailable ? 'unavailable' : 'recovered'}.json`, root),
    JSON.stringify({ checkedAt: new Date().toISOString(), releaseId: previous.releaseId, checks }, null, 2) + '\n')
  console.log(`3 项对象存储${unavailable ? '真实停机' : '恢复'}验证通过。`)
} finally {
  if (token) await request('/auth/logout', 'POST').catch(() => {})
}
