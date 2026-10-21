/**
 * 从运行中的真实控制器导出 OpenAPI；输出只包含接口结构，不包含登录响应或运行凭据。
 * 业务错误/必填校验的补充规则仍在 contracts/p1-api.md，生成文档不能替代真实反例测试。
 */
import { readFile, writeFile } from 'node:fs/promises'

const root = new URL('../', import.meta.url)
const config = JSON.parse((await readFile(new URL('.runtime/local-env.json', root), 'utf8')).replace(/^\uFEFF/, ''))
const base = process.env.ONTO_SERVER_BASE ?? 'http://127.0.0.1:18182'
const login = await fetch(`${base}/onto/auth/login`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: config.ONTO_BOOTSTRAP_USERNAME, password: config.ONTO_BOOTSTRAP_PASSWORD }),
  signal: AbortSignal.timeout(10000),
})
if (!login.ok) throw new Error(`接口文档导出登录失败：HTTP ${login.status}`)
const envelope = await login.json()
const token = envelope.data.token
try {
  const response = await fetch(`${base}/v3/api-docs`, {
    headers: { Authorization: `Bearer ${token}` },
    signal: AbortSignal.timeout(20000),
  })
  if (!response.ok) throw new Error(`OpenAPI 导出失败：HTTP ${response.status}`)
  const schema = await response.json()
  if (!schema.openapi || !schema.paths?.['/onto/spaces']) throw new Error('OpenAPI 缺少空间接口。')
  await writeFile(new URL('contracts/openapi.json', root), JSON.stringify(schema, null, 2) + '\n')
  console.log(`已从真实后端导出 ${Object.keys(schema.paths).length} 个接口路径。`)
} finally {
  await fetch(`${base}/onto/auth/logout`, { method: 'POST', headers: { Authorization: `Bearer ${token}` } })
}
