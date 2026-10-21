/**
 * 仅供本工程本地 HTTP 数据源验收的真实服务，绑定环回地址。
 * 第一页放B/未分类，第二页才有A，用于证明范围过滤不会被第一页/LIMIT截断。
 * 数据取自公开测试夹具；不读取运行凭据，不接收或记录身份信息。
 */
import { createServer } from 'node:http'
import { readFile } from 'node:fs/promises'

const fixture = JSON.parse(await readFile(new URL('../docs/fixtures/p3/tasks.json', import.meta.url), 'utf8'))
const rows = [...fixture.filter(x => x.region !== 'A'), ...fixture.filter(x => x.region === 'A')]
const send = (response, status, value) => {
  response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' })
  response.end(JSON.stringify(value))
}
const server = createServer(async (request, response) => {
  try {
    const url = new URL(request.url, 'http://127.0.0.1:19180')
    if (url.pathname === '/health' && request.method === 'GET') return send(response, 200, { sample: true })
    if (url.pathname !== '/tasks') return send(response, 404, { error: '样例资源不存在' })
    if (!['GET', 'POST'].includes(request.method)) return send(response, 405, { error: '仅支持GET/POST' })
    let page = Number(url.searchParams.get('page') ?? 1)
    if (request.method === 'POST') {
      const chunks = []
      let size = 0
      for await (const chunk of request) {
        size += chunk.length
        if (size > 8192) {
          send(response, 413, { error: '样例请求过大' })
          request.destroy()
          return
        }
        chunks.push(chunk)
      }
      const body = JSON.parse(Buffer.concat(chunks).toString('utf8'))
      page = Number(body.page ?? page)
    }
    if (!Number.isSafeInteger(page) || page < 1 || page > 100) return send(response, 400, { error: '页码无效' })
    send(response, 200, rows.slice((page - 1) * 2, page * 2))
  } catch {
    if (!response.headersSent) send(response, 400, { error: '样例请求无效' })
  }
})
server.requestTimeout = 5000
server.headersTimeout = 5000
server.listen(19180, '127.0.0.1', () => console.log('本工程HTTP验收样例已就绪：127.0.0.1:19180/tasks；每页2条。'))
