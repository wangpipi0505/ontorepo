// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { setAuthorizedSession } from '../test/authorizedSession'
import ElementPlus from 'element-plus'
import type { Space } from '@onto/api-client'
import { useSession } from '../stores/session'
import { useWorkspace } from '../stores/workspace'
import LoginView from '../views/LoginView.vue'
import SpaceDialog from './SpaceDialog.vue'
import ClassDialog from './ClassDialog.vue'
import PropertyDialog from './PropertyDialog.vue'
import OperationDialog from './OperationDialog.vue'

vi.mock('vue-router', () => ({ useRouter: () => ({ replace: vi.fn() }) }))

const space: Space = {
  id: 'space-id',
  code: 'equipment',
  name: '设备模型',
  domain: '',
  namespaceUri: 'https://example.org/equipment/',
  status: 'ACTIVE',
  role: 'OWNER',
  draftRev: 5,
  classCount: 0,
  propertyCount: 0,
  operationCount: 0,
  createdAt: '2026-10-20T00:00:00Z',
}

describe('真实 Element Plus 表单结构与提交', () => {
  beforeEach(() => {
    setAuthorizedSession()
    document.body.innerHTML = ''
  })

  it('登录只渲染一个表单，点击提交会调用真实会话入口', async () => {
    const session = useSession()
    vi.spyOn(session.api, 'get').mockResolvedValue(['space.read'])
    const request = vi.spyOn(session.api, 'post').mockResolvedValue({
      token: 'test-memory-token',
      expiresAt: '2026-10-21T00:00:00Z',
      user: { id: 'user-id', username: 'owner', displayName: '所有者' },
    })
    const wrapper = mount(LoginView, {
      attachTo: document.body,
      global: { plugins: [ElementPlus] },
    })
    expect(wrapper.findAll('form')).toHaveLength(1)
    expect(wrapper.find('form form').exists()).toBe(false)
    await wrapper.get('input[autocomplete="username"]').setValue('owner')
    await wrapper.get('input[autocomplete="current-password"]').setValue('example-password')
    wrapper.get<HTMLButtonElement>('button[type="submit"]').element.click()
    await flushPromises()
    expect(request).toHaveBeenCalledWith('/auth/login', {
      username: 'owner',
      password: 'example-password',
    })
    expect(session.token).toBe('test-memory-token')
    wrapper.unmount()
  })

  it.each([
    { name: '空间', component: SpaceDialog, props: { space: null } },
    { name: '对象', component: ClassDialog, props: { item: null, classes: [] } },
    { name: '属性', component: PropertyDialog, props: { item: null, classId: 'class-id' } },
    { name: '操作', component: OperationDialog, props: { item: null } },
  ])('$name对话框只包含一个表单，点击保存提交预期API', async ({ name, component, props }) => {
    useWorkspace().space = { ...space }
    vi.spyOn(useSession().api, 'get').mockImplementation(
      async <T>(path: string): Promise<T> => (path.startsWith('/spaces/') ? { ...space } : []) as T,
    )
    const request = vi.spyOn(useSession().api, 'post').mockResolvedValue({ ...space, draftRev: 6 })
    // 使用真实 el-form，不以 stub 掩盖其原生 form 根元素。
    const wrapper = mount(component, {
      props: { ...props, modelValue: false },
      attachTo: document.body,
      global: { plugins: [ElementPlus], stubs: { teleport: true, ElSelect: true, ElOption: true } },
    })
    await wrapper.setProps({ modelValue: true })
    await flushPromises()
    expect(wrapper.findAll('form')).toHaveLength(1)
    expect(wrapper.find('form form').exists()).toBe(false)
    const event = new Event('submit', { bubbles: true, cancelable: true })
    wrapper.get('form').element.dispatchEvent(event)
    expect(event.defaultPrevented).toBe(true)
    const inputs = wrapper.findAll('input[type="text"]')
    if (name === '空间' || name === '操作') {
      await inputs[0]!.setValue('example')
      await inputs[1]!.setValue('示例名称')
      if (name === '空间') await inputs[2]!.setValue('https://example.org/example/')
    } else await inputs[0]!.setValue('示例名称')
    wrapper.get<HTMLButtonElement>('button[type="submit"]').element.click()
    await flushPromises()
    const paths: Record<string, string> = {
      空间: '/spaces',
      对象: '/classes',
      属性: '/properties',
      操作: '/operations',
    }
    expect(request).toHaveBeenCalledWith(paths[name], expect.objectContaining({ name: '示例名称' }))
    if (name !== '空间')
      expect(request).toHaveBeenCalledWith(
        paths[name],
        expect.objectContaining({ expectedDraftRev: 5, spaceId: space.id }),
      )
    wrapper.unmount()
  })
})
