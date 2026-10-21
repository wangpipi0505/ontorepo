// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import { ApiError, type SystemRole, type SystemUser } from '@onto/api-client'
import { useSession } from '../../stores/session'
import RoleEditor from './RoleEditor.vue'
import UserEditor from './UserEditor.vue'
import PasswordReset from './PasswordReset.vue'
import RolesView from './RolesView.vue'
import { pagePermission } from '../../composables/pagePermissions'
const wrappers: VueWrapper[] = []
const global = {
  plugins: [ElementPlus],
  renderStubDefaultSlot: true,
  stubs: { ElSelect: true, ElOption: true, teleport: true },
}
const role: SystemRole = {
  id: 'r',
  code: 'REVIEWER',
  name: '复核人员',
  description: '',
  builtIn: false,
  status: 'ACTIVE',
  revision: 7,
  permissions: ['model.read'],
  memberCount: 0,
}
const user: SystemUser = {
  id: 'u',
  username: 'reviewer',
  displayName: '复核员',
  status: 'ACTIVE',
  revision: 3,
  roles: [{ id: 'r', code: 'REVIEWER', name: '复核人员' }],
  createdAt: '',
}
const definitions = [
  { code: 'model.read', name: '读取模型', group: '模型', description: '查看对象属性' },
]
beforeEach(() => {
  setActivePinia(createPinia())
  useSession().permissions = [
    'system.user.write',
    'system.role.write',
    'system.role.read',
    'system.permission.read',
  ]
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.restoreAllMocks()
})
describe('自定义角色与用户管理', () => {
  it('角色保存绑定打开时修订，冲突后保持输入并锁定重复保存', async () => {
    const put = vi
      .spyOn(useSession().api, 'put')
      .mockRejectedValue(new ApiError('IAM_REVISION_CONFLICT', '角色已更新', 409))
    const wrapper = mount(RoleEditor, { props: { role, definitions }, global })
    wrappers.push(wrapper)
    await wrapper.findAll('input')[1].setValue('新的名称')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(put).toHaveBeenCalledWith(
      '/system/roles/r',
      expect.objectContaining({
        name: '新的名称',
        permissions: ['model.read'],
        expectedRevision: 7,
      }),
    )
    expect(wrapper.text()).toContain('角色已被修改')
    expect(wrapper.findAll('input')[1].element.value).toBe('新的名称')
    await wrapper.get('form').trigger('submit')
    expect(put).toHaveBeenCalledTimes(1)
  })
  it('内置角色不会提交编辑，即使触发表单事件', async () => {
    const put = vi.spyOn(useSession().api, 'put')
    const wrapper = mount(RoleEditor, {
      props: { role: { ...role, builtIn: true }, definitions },
      global,
    })
    wrappers.push(wrapper)
    await wrapper.get('form').trigger('submit')
    expect(put).not.toHaveBeenCalled()
    expect(wrapper.text()).not.toContain('保存角色')
  })
  it('用户资料更新保留显式角色和修订，不携带密码字段', async () => {
    const put = vi.spyOn(useSession().api, 'put').mockResolvedValue(user)
    const wrapper = mount(UserEditor, { props: { user, roles: [role] }, global })
    wrappers.push(wrapper)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(put).toHaveBeenCalledWith('/system/users/u', {
      displayName: '复核员',
      status: 'ACTIVE',
      roleIds: ['r'],
      expectedRevision: 3,
    })
    expect(wrapper.emitted('saved')).toHaveLength(1)
  })
  it('密码重置必须显式确认，成功后不保留密码输入', async () => {
    const post = vi.spyOn(useSession().api, 'post').mockResolvedValue({})
    const wrapper = mount(PasswordReset, { props: { user }, global })
    wrappers.push(wrapper)
    await wrapper.get('input[type=password]').setValue('a-new-passphrase')
    await wrapper.get('form').trigger('submit')
    expect(post).not.toHaveBeenCalled()
    await wrapper.get('input[type=checkbox]').setValue(true)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(post).toHaveBeenCalledWith('/system/users/u/password', {
      password: 'a-new-passphrase',
      expectedRevision: 3,
    })
    expect(wrapper.get<HTMLInputElement>('input[type=password]').element.value).toBe('')
  })
  it('无写权限即使直接触发用户表单也不能提交', async () => {
    useSession().permissions = ['system.user.read']
    const put = vi.spyOn(useSession().api, 'put')
    const wrapper = mount(UserEditor, { props: { user, roles: [] }, global })
    wrappers.push(wrapper)
    await wrapper.get('form').trigger('submit')
    expect(put).not.toHaveBeenCalled()
  })
  it('只有角色读取权限时不显示新建和删除入口', async () => {
    useSession().permissions = ['system.role.read']
    vi.spyOn(useSession().api, 'get').mockResolvedValue([role])
    const wrapper = mount(RolesView, { global })
    wrappers.push(wrapper)
    await flushPromises()
    expect(wrapper.text()).not.toContain('新建角色')
    expect(wrapper.text()).not.toContain('删除')
    expect(wrapper.text()).toContain('角色配置只读')
  })
  it('系统管理、业务模型和执行留痕具有不同路由权限', () => {
    expect(pagePermission('/system/users')?.read).toBe('system.user.read')
    expect(pagePermission('/spaces/s/model')?.write).toBe('model.write')
    expect(pagePermission('/spaces/s/executions/e/trace')?.read).toBe('trace.read')
    expect(pagePermission('/unknown')).toBeNull()
  })
})
