// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { setAuthorizedSession } from '../../test/authorizedSession'
import ElementPlus, { ElMessageBox } from 'element-plus'
import type { Space, OntClass, PropertyFacets } from '@onto/api-client'
import { useSession } from '../../stores/session'
import { useWorkspace } from '../../stores/workspace'
import PropertyPanel from '../../components/PropertyPanel.vue'
import PropertyFacetsFields from './PropertyFacetsFields.vue'
import RelationsView from './RelationsView.vue'

describe('C2 审查反例正式回归', () => {
  it('展示服务端超范围标记，仍可手工维护关系且不提交只读标记', async () => {
    setAuthorizedSession()
    const space = { id: 's', role: 'OWNER', status: 'ACTIVE', draftRev: 5 } as Space
    useWorkspace().space = space
    const relation = {
      id: 'r',
      spaceId: 's',
      name: '对称关系',
      fromClassId: 'a',
      toClassId: 'b',
      cardinality: 'MANY_TO_MANY',
      inverseOf: null,
      transitive: false,
      symmetric: true,
      description: '',
      subsetExceeded: true,
    }
    const api = useSession().api
    vi.spyOn(api, 'get').mockImplementation(async <T>(path: string): Promise<T> => {
      const value: unknown =
        path === '/spaces/s'
          ? space
          : path.startsWith('/relations?')
            ? [relation]
            : path.startsWith('/classes?')
              ? [
                  { id: 'a', name: '甲' },
                  { id: 'b', name: '乙' },
                ]
              : []
      return value as T
    })
    const update = vi.spyOn(api, 'put').mockResolvedValue({ ...space, draftRev: 6 })
    const wrapper = mount(RelationsView, {
      attachTo: document.body,
      global: { plugins: [ElementPlus], stubs: { teleport: true, ElSelect: true, ElOption: true } },
    })
    try {
      await flushPromises()
      expect(wrapper.text()).toContain('超出推理子集')
      await wrapper
        .findAll('button')
        .find((button) => button.text() === '编辑')!
        .trigger('click')
      await flushPromises()
      expect(wrapper.text()).toContain('OWNER')
      expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeUndefined()
      wrapper.get<HTMLButtonElement>('button[type="submit"]').element.click()
      await flushPromises()
      expect(update).toHaveBeenCalledWith(
        '/relations/r',
        expect.objectContaining({ symmetric: true, expectedDraftRev: 5 }),
      )
      expect(update.mock.calls[0]?.[1]).not.toHaveProperty('subsetExceeded')
    } finally {
      wrapper.unmount()
    }
  })
  it('读取影响期间刷新列表，旧属性删除必须继续携带发起时修订', async () => {
    setAuthorizedSession()
    const space = { id: 's', role: 'OWNER', status: 'ACTIVE', draftRev: 5 } as Space
    useWorkspace().space = space
    let revision = 5
    let finishImpact!: (value: unknown) => void
    const impact = new Promise((resolve) => {
      finishImpact = resolve
    })
    const api = useSession().api
    vi.spyOn(api, 'get').mockImplementation(async <T>(path: string): Promise<T> => {
      if (path.includes('/impact?')) return (await impact) as T
      if (path === '/spaces/s') return { ...space, draftRev: revision } as T
      if (path.startsWith('/properties?'))
        return [
          {
            id: 'p',
            spaceId: 's',
            domainClassId: 'a',
            name: revision === 5 ? '旧属性' : '他人已更新的属性',
            rangeType: 'INTEGER',
            required: false,
            description: '',
            facets: {},
          },
        ] as T
      return [] as T
    })
    const remove = vi.spyOn(api, 'delete').mockResolvedValue({ ...space, draftRev: 7 })
    // 当前 Element Plus 声明将确认动作与输入结果写成交叉类型；普通确认运行时返回动作字符串。
    const confirm = vi
      .spyOn(ElMessageBox, 'confirm')
      .mockResolvedValue('confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>)
    const wrapper = mount(PropertyPanel, {
      props: { item: { id: 'a', spaceId: 's', name: '对象' } as OntClass },
      global: { plugins: [ElementPlus], stubs: { teleport: true } },
    })
    try {
      await flushPromises()
      await wrapper
        .findAll('button')
        .find((button) => button.text() === '删除')!
        .trigger('click')
      revision = 6
      await wrapper
        .findAll('button')
        .find((button) => button.text() === '刷新')!
        .trigger('click')
      await flushPromises()
      finishImpact({ references: [] })
      await flushPromises()
      expect(remove).toHaveBeenCalledWith('/properties/p?spaceId=s&expectedDraftRev=5')
    } finally {
      wrapper.unmount()
      confirm.mockRestore()
    }
  })

  it.each(['minimum', 'maximum'] as const)('清空已有可选%s移除该分面', async (key) => {
    const wrapper = mount(PropertyFacetsFields, {
      props: { facets: { [key]: 3 }, books: [], rangeType: 'INTEGER', required: false },
      global: { plugins: [ElementPlus] },
    })
    try {
      const input = wrapper.findAll('input[role="spinbutton"]')[key === 'minimum' ? 0 : 1]!
      expect((input.element as HTMLInputElement).value).toBe('3')
      await input.setValue('')
      await input.trigger('blur')
      await flushPromises()
      const fields = wrapper.vm as unknown as { read(): PropertyFacets }
      expect(fields.read()).toEqual({ primaryKey: false })
    } finally {
      wrapper.unmount()
    }
  })

  it.each(['minimum', 'maximum'] as const)('数值%s为0时保留，不当作空范围', async (key) => {
    const wrapper = mount(PropertyFacetsFields, {
      props: { facets: { [key]: 0 }, books: [], rangeType: 'INTEGER', required: false },
      global: { plugins: [ElementPlus] },
    })
    try {
      await flushPromises()
      const fields = wrapper.vm as unknown as { read(): PropertyFacets }
      expect(fields.read()).toEqual({ primaryKey: false, [key]: 0 })
    } finally {
      wrapper.unmount()
    }
  })
})
