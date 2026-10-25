/** mock 传输器回归测试：信封结构、路由命中与兜底行为。 */
import { describe, expect, it } from 'vitest'
import { mockFetcher } from '@onto/api-client/mock'

const envelope = async (url: string, init?: RequestInit) => {
  const response = await mockFetcher(url, init)
  return { status: response.status, body: await response.json() }
}

describe('mock 传输器烟雾验证', () => {
  it('登录返回演示令牌与用户', async () => {
    const { status, body } = await envelope('/onto/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username: 'demo', password: 'x' }),
    })
    expect(status).toBe(200)
    expect(body.code).toBe('OK')
    expect(body.data.token).toBe('mock-token-demo')
    expect(body.data.user.displayName).toBe('演示管理员')
  })

  it('权限列表覆盖路由守卫所需读码', async () => {
    const { body } = await envelope('/onto/auth/permissions')
    for (const code of ['space.read', 'model.read', 'release.read', 'trace.read', 'system.user.read'])
      expect(body.data).toContain(code)
  })

  it('空间列表与详情命中 fixture', async () => {
    const list = await envelope('/onto/spaces')
    expect(list.body.data).toHaveLength(2)
    const detail = await envelope('/onto/spaces/sp-sat')
    expect(detail.body.data.name).toBe('卫星本体')
  })

  it('候选发布与溯源详情返回完整结构', async () => {
    const pub = await envelope('/onto/candidates/cand-001/publication')
    expect(pub.body.data.checks).toHaveLength(5)
    const trace = await envelope('/onto/executions/exe-101/trace')
    expect(trace.body.data.graph.nodes.length).toBeGreaterThan(3)
    expect(trace.body.data.authorization).toHaveLength(3)
  })

  it('评论接口返回列表且提交回显', async () => {
    const list = await envelope('/onto/releases/proposal-comments?candidateId=cand-001')
    expect(list.body.data.length).toBeGreaterThan(1)
    const created = await envelope('/onto/releases/proposal-comments', {
      method: 'POST',
      body: JSON.stringify({ candidateId: 'cand-001', content: '测试评论' }),
    })
    expect(created.body.data.content).toBe('测试评论')
  })

  it('未匹配读请求返回空集合、写请求推进草稿修订', async () => {
    const empty = await envelope('/onto/unknown-resource')
    expect(empty.body.data).toEqual([])
    const written = await envelope('/onto/unknown-resource', {
      method: 'POST',
      body: JSON.stringify({ spaceId: 'sp-gnd' }),
    })
    expect(written.body.data.draftRev).toBe(3)
  })

  it('不存在的空间返回 404 信封', async () => {
    const { status, body } = await envelope('/onto/spaces/sp-none')
    expect(status).toBe(404)
    expect(body.code).toBe('NOT_FOUND')
  })
})
