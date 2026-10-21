import { useEntityFocus } from '../../composables/useEntityFocus'
import { ref, shallowRef } from 'vue'
import type { Space } from '@onto/api-client'
import { useWorkspace } from '../../stores/workspace'
import { useSession } from '../../stores/session'
import { useResource } from '../../composables/useResource'
import { useDraftRevision } from '../../composables/useDraftRevision'
import { useMutation, confirmAction, notifySaved } from '../../composables/useMutation'
import { readDraftSnapshot } from '../../composables/readDraftSnapshot'

/** 仅复用列表的并发/提交不变量；领域字段仍由独立业务表单维护，避免通用巨型表单。 */
export function useDraftCollection<T extends { id: string; name: string }>(path: string) {
  const workspace = useWorkspace(),
    api = useSession().api
  const items = useResource<T[]>([]),
    mutation = useMutation(),
    draft = useDraftRevision()
  const open = ref(false),
    editing = shallowRef<T | null>(null)
  let loadedRev: number | undefined
  async function load() {
    const spaceId = workspace.space?.id
    if (!spaceId) return
    let current: Space | undefined
    const result = await items.load(async () => {
      const snapshot = await readDraftSnapshot(api, spaceId, () =>
        api.get<T[]>(`${path}?spaceId=${spaceId}`),
      )
      current = snapshot.space
      return snapshot.items
    })
    if (result && current) {
      loadedRev = current.draftRev
      workspace.refreshed(current)
    }
  }
  function edit(item: T | null) {
    if (!workspace.canWrite) return
    editing.value = item
    draft.start(loadedRev ?? workspace.space?.draftRev ?? 0)
    mutation.error.value = ''
    open.value = true
  }
  async function save(body: Record<string, unknown>) {
    const space = workspace.space
    if (!space || !workspace.canWrite) return false
    const ok = await mutation.run(({ isCurrent }) =>
      draft.submit(async (expectedDraftRev) => {
        const request = { ...body, spaceId: space.id, expectedDraftRev }
        const next = editing.value
          ? await api.put<Space>(`${path}/${editing.value.id}`, request)
          : await api.post<Space>(path, request)
        // 写入可以在服务端完成，但已离开的页面不能把成功响应回写新工作区。
        if (isCurrent()) workspace.accept(next)
      }),
    )
    if (ok) {
      open.value = false
      notifySaved()
      await load()
    }
    return ok
  }
  async function remove(item: T) {
    const space = workspace.space
    if (!space || !workspace.canWrite || loadedRev === undefined) return
    // 确认对话框等待期间可能已有列表重读；本次删除始终绑定点击时看到的修订。
    const expectedDraftRev = loadedRev,
      context = workspace.captureContext()
    if (!(await confirmAction(`删除“${item.name}”？受引用条目可能被服务端阻止。`))) return
    if (!workspace.isCurrentContext(context)) return
    await mutation.run(async ({ isCurrent }) => {
      const next = await api.delete<Space>(
        `${path}/${item.id}?spaceId=${space.id}&expectedDraftRev=${expectedDraftRev}`,
      )
      if (!isCurrent()) return
      workspace.accept(next)
      await load()
    })
  }
  const focus = useEntityFocus(items.data, items.loading)
  return { focus, workspace, api, items, mutation, draft, open, editing, load, edit, save, remove }
}
