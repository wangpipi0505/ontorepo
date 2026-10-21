// @vitest-environment jsdom
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useSession } from './session'
beforeEach(() => {
  setActivePinia(createPinia())
})
afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})
describe('IAM 当前会话权限边界', () => {
  it('未取得权限或权限读取失败时默认拒绝', async () => {
    const session = useSession()
    expect(session.has('system.user.write')).toBe(false)
    session.token = 'current'
    vi.spyOn(session.api, 'get').mockRejectedValue(new Error('权限读取失败'))
    await expect(session.refreshPermissions()).rejects.toThrow('权限读取失败')
    expect(session.has('model.write')).toBe(false)
  })
  it('旧令牌权限读取迟到后不能授予新账号权限', async () => {
    const session = useSession()
    let resolve!: (value: string[]) => void
    session.token = 'old'
    vi.spyOn(session.api, 'get').mockReturnValue(
      new Promise((done) => {
        resolve = done
      }),
    )
    const loading = session.refreshPermissions()
    session.clear()
    session.token = 'new'
    resolve(['system.user.write'])
    await loading
    expect(session.has('system.user.write')).toBe(false)
  })
  it('当前会话401提示只消费一次，匿名登录失败不提示过期', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ code: 'UNAUTHORIZED', message: '需要登录', data: null }), {
          status: 401,
        }),
      ),
    )
    const session = useSession()
    session.token = 'current'
    await expect(session.api.get('/spaces')).rejects.toThrow('需要登录')
    expect(session.consumeExpiredNotice()).toBe(true)
    expect(session.consumeExpiredNotice()).toBe(false)
    await expect(session.api.get('/auth/me')).rejects.toThrow()
    expect(session.consumeExpiredNotice()).toBe(false)
  })
})
