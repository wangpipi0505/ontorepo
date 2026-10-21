// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, effectScope, h, type EffectScope } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { setAuthorizedSession } from '../test/authorizedSession'
import ElementPlus, { ElMessageBox } from 'element-plus'
import type { Space } from '@onto/api-client'
import { useSession } from '../stores/session'
import { useWorkspace } from '../stores/workspace'
import { useDraftCollection } from '../features/p1/useDraftCollection'
import ModelView from '../views/ModelView.vue'
import ClassDialog from './ClassDialog.vue'
import PropertyDialog from './PropertyDialog.vue'
import OperationDialog from './OperationDialog.vue'
import PropertyPanel from './PropertyPanel.vue'

const space = { id: 'A', name: '空间A', role: 'OWNER', status: 'ACTIVE', draftRev: 5 } as Space
const item = {
  id: 'class',
  spaceId: 'A',
  name: '旧名称',
  description: '旧说明',
  parentId: null,
  deprecated: false,
}
let scope: EffectScope
let wrappers: VueWrapper[] = []
beforeEach(() => {
  setAuthorizedSession()
  useWorkspace().space = { ...space }
  scope = effectScope()
})
afterEach(() => {
  wrappers.forEach((wrapper) => wrapper.unmount())
  wrappers = []
  scope.stop()
  vi.restoreAllMocks()
  document.body.innerHTML = ''
})
const select = defineComponent({
  props: { modelValue: [String, Number, Array], disabled: Boolean },
  emits: ['update:modelValue'],
  setup:
    (props, { emit, slots }) =>
    () =>
      h(
        'select',
        {
          value: props.modelValue,
          disabled: props.disabled,
          onChange: (event: Event) =>
            emit('update:modelValue', (event.target as HTMLSelectElement).value),
        },
        slots.default?.(),
      ),
})
const option = defineComponent({
  props: ['value', 'label'],
  setup: (props) => () => h('option', { value: props.value }, props.label),
})
const global = {
  plugins: [ElementPlus],
  stubs: { teleport: true, ElSelect: select, ElOption: option },
}
function waiting<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}
function reenter() {
  useWorkspace().space = { ...space, id: 'B' }
  useWorkspace().space = { ...space, draftRev: 9 }
}

describe('全量审查：迟到保存与各实体独立修订', () => {
  it.each(['save', 'remove'] as const)(
    '集合%s的旧成功不能回写重新进入空间的修订',
    async (action) => {
      const pendingResponse = waiting<Space>()
      vi.spyOn(useSession().api, 'get').mockImplementation(
        async <T>(path: string): Promise<T> => (path === '/spaces/A' ? space : [item]) as T,
      )
      vi.spyOn(useSession().api, 'post').mockReturnValue(pendingResponse.promise)
      vi.spyOn(useSession().api, 'delete').mockReturnValue(pendingResponse.promise)
      vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue(
        'confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>,
      )
      const page = scope.run(() => useDraftCollection<{ id: string; name: string }>('/codebooks'))!
      await page.load()
      page.edit(null)
      const pending = action === 'save' ? page.save({ name: '待保存' }) : page.remove(item)
      await flushPromises()
      scope.stop()
      reenter()
      pendingResponse.resolve({ ...space, draftRev: 6 })
      await pending
      expect(useWorkspace().space?.draftRev).toBe(9)
    },
  )

  it.each([
    { name: '对象', component: ClassDialog, props: { item: null, classes: [] } },
    { name: '属性', component: PropertyDialog, props: { item: null, classId: 'class' } },
    { name: '操作', component: OperationDialog, props: { item: null } },
  ])('$name对话框卸载后的成功响应不污染新页面', async ({ name, component, props }) => {
    const pendingResponse = waiting<Space>()
    vi.spyOn(useSession().api, 'get').mockImplementation(
      async <T>(path: string): Promise<T> => (path.startsWith('/spaces/') ? space : []) as T,
    )
    const post = vi.spyOn(useSession().api, 'post').mockReturnValue(pendingResponse.promise)
    const wrapper = mount(component, { props: { ...props, modelValue: false }, global })
    await wrapper.setProps({ modelValue: true })
    await flushPromises()
    const inputs = wrapper.findAll('input[type="text"]')
    await inputs[0]!.setValue(name === '操作' ? 'query' : '待保存')
    if (name === '操作') await inputs[1]!.setValue('查询')
    await wrapper.get('form').trigger('submit')
    expect(post).toHaveBeenCalledTimes(1)
    wrapper.unmount()
    reenter()
    pendingResponse.resolve({ ...space, draftRev: 6 })
    await flushPromises()
    expect(useWorkspace().space?.draftRev).toBe(9)
    expect(wrapper.emitted('saved')).toBeUndefined()
  })

  it.each(['edit', 'remove', 'drag'] as const)(
    '属性面板保存后，类%s仍绑定原类树修订',
    async (action) => {
      let serverRev = 5
      const parent = { ...item, id: 'parent', name: '父类' }
      const property = {
        id: 'property',
        spaceId: 'A',
        domainClassId: 'class',
        name: '属性',
        rangeType: 'STRING',
        required: false,
        description: '',
        facets: {},
      }
      vi.spyOn(useSession().api, 'get').mockImplementation(async <T>(path: string): Promise<T> => {
        const value =
          path === '/spaces/A'
            ? { ...space, draftRev: serverRev }
            : path.startsWith('/classes')
              ? [item, parent]
              : path.startsWith('/properties?')
                ? [property]
                : path.includes('/impact')
                  ? { references: [] }
                  : []
        return value as T
      })
      const put = vi.spyOn(useSession().api, 'put').mockImplementation(async <T>(): Promise<T> => {
        serverRev++
        return { ...space, draftRev: serverRev } as T
      })
      const remove = vi
        .spyOn(useSession().api, 'delete')
        .mockResolvedValue({ ...space, draftRev: 8 })
      vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue(
        'confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>,
      )
      const wrapper = mount(ModelView, { global })
      wrappers.push(wrapper)
      await flushPromises()
      await wrapper.get('.el-tree-node__content').trigger('click')
      await flushPromises()
      // 他人修改类到 r6，本页面只重读属性；属性成功保存后全局是 r7，类树仍来自 r5。
      serverRev = 6
      const panel = wrapper.getComponent(PropertyPanel)
      await panel
        .findAll('button')
        .find((button) => button.text() === '刷新')!
        .trigger('click')
      await flushPromises()
      await panel
        .findAll('button')
        .find((button) => button.text() === '编辑')!
        .trigger('click')
      await flushPromises()
      await panel.getComponent(PropertyDialog).get('form').trigger('submit')
      await flushPromises()
      expect(useWorkspace().space?.draftRev).toBe(7)
      if (action === 'edit') {
        await panel
          .findAll('button')
          .find((button) => button.text() === '编辑对象')!
          .trigger('click')
        await flushPromises()
        await wrapper.getComponent(ClassDialog).get('form').trigger('submit')
        await flushPromises()
        expect(put).toHaveBeenLastCalledWith(
          '/classes/class',
          expect.objectContaining({ name: '旧名称', expectedDraftRev: 5 }),
        )
      } else if (action === 'remove') {
        await panel
          .findAll('button')
          .find((button) => button.text() === '删除对象')!
          .trigger('click')
        await flushPromises()
        expect(remove).toHaveBeenCalledWith('/classes/class?spaceId=A&expectedDraftRev=5')
      } else {
        wrapper
          .getComponent({ name: 'ElTree' })
          .vm.$emit('node-drop', { data: item }, { data: parent }, 'inner', new Event('drop'))
        await flushPromises()
        expect(put).toHaveBeenLastCalledWith(
          '/classes/class',
          expect.objectContaining({ parentId: 'parent', expectedDraftRev: 5 }),
        )
      }
    },
  )
})
