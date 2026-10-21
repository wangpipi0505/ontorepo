// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import { ApiError, type Endpoint, type Space } from '@onto/api-client'
import { useSession } from '../../stores/session'
import { useWorkspace } from '../../stores/workspace'
import DraftConflictsPanel from '../p1/DraftConflictsPanel.vue'
import EndpointGrantsPanel from './EndpointGrantsPanel.vue'
import CredentialsPanel from './CredentialsPanel.vue'
import SpacesView from '../../views/SpacesView.vue'
const wrappers: VueWrapper[] = []
const global = {
  plugins: [ElementPlus],
  stubs: { RouterLink: { template: '<a><slot /></a>' }, teleport: true },
}
const space = {
  id: 's',
  name: '测试空间',
  code: 'test',
  draftRev: 1,
  status: 'ACTIVE',
  role: 'OWNER',
} as Space
const endpoint = { id: 'e', opCode: 'query', status: 'ACTIVE' } as Endpoint
function keep(wrapper: VueWrapper) {
  wrappers.push(wrapper)
  return wrapper
}
beforeEach(() => {
  setActivePinia(createPinia())
  useWorkspace().space = { ...space }
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.restoreAllMocks()
})
describe('独立审核复验：组合权限保留已授权只读能力', () => {
  it('无对象和操作目录权限仍显示主冲突，且不发送未授权目录请求', async () => {
    useSession().permissions = ['space.read', 'rule.read']
    const get = vi
      .spyOn(useSession().api, 'get')
      .mockImplementation(async <T>(path: string): Promise<T> => {
        if (path === '/spaces/s') return space as T
        if (path.endsWith('/conflicts'))
          return {
            errors: [{ kind: 'LOGIC', message: '真实规则冲突', entityIds: ['r'] }],
            warnings: [],
          } as T
        return [] as T
      })
    const wrapper = keep(mount(DraftConflictsPanel, { global }))
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('真实规则冲突')
    expect(wrapper.text()).toContain('对象目录未授权')
    expect(get.mock.calls.some(([path]) => /classes|properties|operations/.test(path))).toBe(false)
  })
  it('已授权可选目录读取失败只影响定位，不覆盖成功的冲突检查', async () => {
    useSession().permissions = ['space.read', 'rule.read', 'model.read', 'operation.read']
    vi.spyOn(useSession().api, 'get').mockImplementation(async <T>(path: string): Promise<T> => {
      if (path === '/spaces/s') return space as T
      if (path.endsWith('/conflicts'))
        return {
          errors: [{ kind: 'TYPE', message: '服务端类型冲突', entityIds: ['r'] }],
          warnings: [],
        } as T
      throw new ApiError('NETWORK_ERROR', '定位目录暂不可用', 0)
    })
    const wrapper = keep(mount(DraftConflictsPanel, { global }))
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('服务端类型冲突')
    expect(wrapper.text()).toContain('规则目录读取失败')
  })
  it('grant.read 的 OWNER 可读端点授权，写入表单和按钮不出现', async () => {
    useSession().permissions = ['grant.read']
    const get = vi
      .spyOn(useSession().api, 'get')
      .mockImplementation(
        async <T>(path: string): Promise<T> =>
          (path === '/endpoints/e/grants'
            ? [{ userId: 'u', displayName: '只读授权主体', status: 'ACTIVE', dataScope: {} }]
            : []) as T,
      )
    const wrapper = keep(mount(EndpointGrantsPanel, { props: { endpoint }, global }))
    await flushPromises()
    expect(get).toHaveBeenCalledWith('/endpoints/e/grants')
    expect(wrapper.text()).toContain('只读授权主体')
    expect(wrapper.text()).not.toContain('编辑端点授权')
    expect(wrapper.find('[data-testid=endpoint-user]').exists()).toBe(false)
  })
  it('grant.read 的 OWNER 可读凭据安全目录，不能创建和撤销', async () => {
    useSession().permissions = ['grant.read']
    const get = vi
      .spyOn(useSession().api, 'get')
      .mockImplementation(
        async <T>(path: string): Promise<T> =>
          (path.startsWith('/api-credentials')
            ? [{ id: 'k', name: '安全目录记录', status: 'ACTIVE' }]
            : []) as T,
      )
    const wrapper = keep(mount(CredentialsPanel, { global }))
    await flushPromises()
    expect(get).toHaveBeenCalledWith('/api-credentials?spaceId=s')
    expect(wrapper.text()).toContain('安全目录记录')
    expect(wrapper.find('form').exists()).toBe(false)
    expect(wrapper.findAll('button').some((item) => item.text() === '撤销')).toBe(false)
  })
  it('读取权限撤销后迟到的授权目录不能重新出现', async () => {
    useSession().permissions = ['grant.read']
    let resolve!: (value: unknown) => void
    const pending = new Promise((done) => {
      resolve = done
    })
    vi.spyOn(useSession().api, 'get').mockImplementation(
      async <T>(path: string): Promise<T> =>
        (path === '/endpoints/e/grants' ? await pending : []) as T,
    )
    const wrapper = keep(mount(EndpointGrantsPanel, { props: { endpoint }, global }))
    useSession().permissions = []
    resolve([{ userId: 'u', displayName: '已撤权目录', status: 'ACTIVE' }])
    await flushPromises()
    expect(wrapper.text()).not.toContain('已撤权目录')
    expect(wrapper.text()).toContain('授权读取权限')
  })
  it('写权限撤销并重新授予后，旧创建响应仍不能恢复一次性密钥', async () => {
    useSession().permissions = ['grant.read', 'grant.write']
    vi.spyOn(useSession().api, 'get').mockImplementation(
      async <T>(path: string): Promise<T> =>
        (path.includes('grant-users')
          ? [{ userId: 'u', username: 'user', displayName: '用户' }]
          : []) as T,
    )
    let resolve!: (value: unknown) => void
    vi.spyOn(useSession().api, 'post').mockReturnValue(
      new Promise((done) => {
        resolve = done
      }),
    )
    const wrapper = keep(mount(CredentialsPanel, { global }))
    await flushPromises()
    await wrapper.get('[data-testid=credential-principal]').setValue('u')
    await wrapper.get('[data-testid=credential-name]').setValue('测试凭据')
    await wrapper.get('form').trigger('submit')
    useSession().permissions = ['grant.read']
    useSession().permissions = ['grant.read', 'grant.write']
    resolve({ key: '不得恢复的旧密钥', credential: { id: 'k', name: '旧凭据' } })
    await flushPromises()
    expect(wrapper.text()).not.toContain('不得恢复的旧密钥')
    expect(wrapper.text()).not.toContain('旧凭据')
  })
  it('space.read 的空间成员不会看到无效编辑按钮', async () => {
    useSession().permissions = ['space.read']
    vi.spyOn(useSession().api, 'get').mockImplementation(async <T>(path: string): Promise<T> => {
      if (path === '/spaces') return [space] as T
      if (path === '/spaces/s') return space as T
      throw new ApiError('FORBIDDEN', '其他权限未授予', 403)
    })
    const wrapper = keep(mount(SpacesView, { global }))
    await flushPromises()
    expect(wrapper.findAll('button').some((item) => item.text() === '编辑')).toBe(false)
  })
})
