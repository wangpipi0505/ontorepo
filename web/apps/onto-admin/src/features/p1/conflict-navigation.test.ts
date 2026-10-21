// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import ElementPlus from 'element-plus'
import type { Space } from '@onto/api-client'
import { setAuthorizedSession } from '../../test/authorizedSession'
import { useSession } from '../../stores/session'
import { useWorkspace } from '../../stores/workspace'
import DraftConflictsPanel from './DraftConflictsPanel.vue'
import RulesView from './RulesView.vue'
let wrapper: VueWrapper | undefined
afterEach(() => {
  wrapper?.unmount()
  vi.restoreAllMocks()
})
describe('冲突独立页与定位目标', () => {
  it('按类别显示冲突，真实属性链接包含所属类，缺失引用不伪造可点击目标', async () => {
    setAuthorizedSession()
    const space = { id: 's', draftRev: 1, status: 'ACTIVE', role: 'VIEWER' } as Space
    useWorkspace().space = space
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:all(.*)*', component: { template: '<div />' } }],
    })
    await router.push('/spaces/s/conflicts')
    vi.spyOn(useSession().api, 'get').mockImplementation(async <T>(path: string): Promise<T> => {
      if (path === '/spaces/s') return space as T
      if (path.endsWith('/conflicts'))
        return {
          errors: [{ kind: 'REFERENCE', message: '属性引用缺失', entityIds: ['p', 'gone'] }],
          warnings: [],
        } as T
      if (path.endsWith('/properties')) return [{ id: 'p', name: '分值', domainClassId: 'c' }] as T
      return [] as T
    })
    wrapper = mount(DraftConflictsPanel, { global: { plugins: [ElementPlus, router] } })
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.get('a').attributes('href')).toBe('/spaces/s/model?entityId=p&classId=c')
    expect(wrapper.text()).toContain('gone · 条目不存在或不可定位')
    expect(wrapper.findAll('h3').map((heading) => heading.text())).toEqual([
      '引用',
      '类型',
      '约束',
      '逻辑',
    ])
  })
  it('只读账号定位规则时展开详情，已失效标识给出刷新提示', async () => {
    setAuthorizedSession()
    const space = { id: 's', draftRev: 1, status: 'ACTIVE', role: 'VIEWER' } as Space
    useWorkspace().space = space
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:all(.*)*', component: { template: '<div />' } }],
    })
    await router.push('/spaces/s/rules?entityId=r')
    vi.spyOn(useSession().api, 'get').mockImplementation(async <T>(path: string): Promise<T> => {
      if (path === '/spaces/s') return space as T
      if (path.startsWith('/rules?'))
        return [
          {
            id: 'r',
            name: '规则详情',
            kind: 'VALIDATION',
            action: {},
            condition: '',
            severity: 'ERROR',
          },
        ] as T
      return [] as T
    })
    wrapper = mount(RulesView, {
      global: { plugins: [ElementPlus, router], stubs: { teleport: true } },
    })
    await flushPromises()
    expect(wrapper.text()).toContain('已定位：规则详情')
    expect(wrapper.getComponent({ name: 'ElTable' }).props('expandRowKeys')).toEqual(['r'])
    await router.push('/spaces/s/rules?entityId=gone')
    await flushPromises()
    expect(wrapper.text()).toContain('关联条目已删除或已变更')
  })
})
