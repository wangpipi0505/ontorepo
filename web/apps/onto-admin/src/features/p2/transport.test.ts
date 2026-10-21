import { describe, expect, it, vi } from 'vitest'
import { ApiClient } from '@onto/api-client'
describe('候选内部调用信封', () => {
  it('消费协议成功信封允许只有code/data/meta', async () => {
    const api = new ApiClient(
      () => null,
      () => {},
      async () =>
        new Response(JSON.stringify({ code: 'OK', data: { rows: [] }, meta: { preview: true } })),
    )
    expect(await api.post('/internal/candidates/c/ops/query', {})).toEqual({ rows: [] })
  })
  it('保留真实业务结果和 preview 元数据，普通请求仍只返回 data', async () => {
    const value = {
      code: 'OK',
      message: '',
      data: { columns: ['name'], rows: [{ name: '设备' }] },
      meta: {
        spaceCode: 'demo',
        releaseId: null,
        version: null,
        contentDigest: 'sha',
        preview: true,
      },
    }
    const fetcher = vi
      .fn<typeof fetch>()
      .mockImplementation(async () => new Response(JSON.stringify(value), { status: 200 }))
    const api = new ApiClient(
      () => 'memory-token',
      () => {},
      fetcher,
    )
    expect('requestEnvelope' in api, '内部调用需保留候选元数据').toBe(true)
    const transport = api as ApiClient & {
      requestEnvelope<T>(
        path: string,
        method: string,
        body: unknown,
      ): Promise<{ data: T; meta?: unknown }>
    }
    const result = await transport.requestEnvelope('/internal/candidates/c/ops/query', 'POST', {
      limit: 3,
    })
    expect(result).toEqual(value)
    expect(await api.get('/ordinary')).toEqual(value.data)
    expect(fetcher.mock.calls[0]?.[1]?.body).toBe('{"limit":3}')
  })
})
