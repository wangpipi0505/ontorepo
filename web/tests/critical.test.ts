import { describe, expect, it, vi } from 'vitest'
import { ApiClient, ApiError } from '../packages/api-client/src/index'
import { canReparent, buildTree } from '../apps/onto-admin/src/composables/classTree'

describe('API 信任边界', () => {
  it('旧请求迟到的401不能撤销重新登录后的新会话', async () => {
    let token: string | null = 'old-session'
    const expired = vi.fn(() => {
      token = null
    })
    let finish!: (response: Response) => void
    const api = new ApiClient(
      () => token,
      expired,
      vi.fn(
        () =>
          new Promise<Response>((resolve) => {
            finish = resolve
          }),
      ),
    )
    const old = api.get('/spaces')
    token = 'new-session'
    finish(
      new Response(JSON.stringify({ code: 'UNAUTHORIZED', message: '旧会话过期', data: null }), {
        status: 401,
      }),
    )
    await expect(old).rejects.toMatchObject({ status: 401 })
    expect(token).toBe('new-session')
    expect(expired).not.toHaveBeenCalled()
  })
  it('非法信封也统一为可恢复响应错误', async () => {
    const api = new ApiClient(
      () => null,
      () => {},
      vi.fn().mockResolvedValue(new Response('null', { status: 200 })),
    )
    await expect(api.get('/spaces')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })
  it('403 保留会话并显示权限错误', async () => {
    const expired = vi.fn()
    const api = new ApiClient(
      () => 'token',
      expired,
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ code: 'FORBIDDEN', message: '没有编辑权限', data: null }), {
          status: 403,
        }),
      ),
    )
    await expect(api.post('/classes', {})).rejects.toMatchObject({
      status: 403,
      message: '没有编辑权限',
    })
    expect(expired).not.toHaveBeenCalled()
  })
  it('401 清理会话且保留可恢复错误', async () => {
    const expired = vi.fn()
    const fetcher = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ code: 'UNAUTHORIZED', message: '会话已过期', data: null }), {
        status: 401,
      }),
    )
    const api = new ApiClient(() => 'memory-token', expired, fetcher)
    await expect(api.get('/spaces')).rejects.toMatchObject({ status: 401, message: '会话已过期' })
    expect(expired).toHaveBeenCalledOnce()
    expect(fetcher.mock.calls[0]?.[1].headers.get('Authorization')).toBe('Bearer memory-token')
  })
  it('409 不伪造成功，保留服务端冲突码', async () => {
    const api = new ApiClient(
      () => null,
      () => {},
      vi
        .fn()
        .mockResolvedValue(
          new Response(
            JSON.stringify({ code: 'DRAFT_REV_CONFLICT', message: '草稿已更新', data: null }),
            { status: 409 },
          ),
        ),
    )
    await expect(api.post('/classes', {})).rejects.toMatchObject({
      code: 'DRAFT_REV_CONFLICT',
      status: 409,
    })
  })
  it('网络断开转换可读错误', async () => {
    const api = new ApiClient(
      () => null,
      () => {},
      vi.fn().mockRejectedValue(new TypeError('Failed to fetch')),
    )
    await expect(api.get('/spaces')).rejects.toBeInstanceOf(ApiError)
    await expect(api.get('/spaces')).rejects.toMatchObject({ code: 'NETWORK_ERROR' })
  })
})

const classes = [
  { id: 'a', parentId: null, name: '设备', spaceId: 's', description: '', deprecated: false },
  { id: 'b', parentId: 'a', name: '电机', spaceId: 's', description: '', deprecated: false },
  { id: 'c', parentId: 'b', name: '主电机', spaceId: 's', description: '', deprecated: false },
]
describe('对象树层级保护', () => {
  it('禁止自父级、后代父级和不存在的父级', () => {
    expect(canReparent(classes, 'a', 'a')).toBe(false)
    expect(canReparent(classes, 'a', 'c')).toBe(false)
    expect(canReparent(classes, 'a', 'outside')).toBe(false)
    expect(canReparent(classes, 'c', null)).toBe(true)
  })
  it('保留真正层级且不修改 API 结果', () => {
    expect(buildTree(classes)[0]?.children[0]?.children[0]?.id).toBe('c')
    expect(classes[0]).not.toHaveProperty('children')
  })
})
