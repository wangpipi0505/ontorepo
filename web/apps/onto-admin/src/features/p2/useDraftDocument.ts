import { useSession } from '../../stores/session'
import { useWorkspace } from '../../stores/workspace'
import { useResource } from '../../composables/useResource'
import { useDraftRevision } from '../../composables/useDraftRevision'
import { readDraftSnapshot } from '../../composables/readDraftSnapshot'
import { useMutation, notifySaved } from '../../composables/useMutation'
import type { Space } from '@onto/api-client'

/** 单文档编辑的修订基线来自完整一致性读取；409 后不自动重读或替换用户输入。 */
export function useDraftDocument<T>(read: (spaceId: string) => Promise<T>) {
  const api = useSession().api,
    workspace = useWorkspace()
  const resource = useResource<{ items: T; space: Space } | null>(null)
  const draft = useDraftRevision(),
    mutation = useMutation()
  async function load() {
    const id = workspace.space?.id
    if (!id) return
    const result = await resource.load(() => readDraftSnapshot(api, id, () => read(id)))
    if (result) {
      draft.start(result.space.draftRev)
      workspace.refreshed(result.space)
      mutation.error.value = ''
    }
    return result
  }
  async function save(write: (expectedDraftRev: number) => Promise<Space>) {
    if (!resource.data.value || !workspace.canWrite || draft.stale.value) return false
    const ok = await mutation.run(({ isCurrent }) =>
      draft.submit(async (rev) => {
        const next = await write(rev)
        // 页面退出或工作区重新加载后，成功响应也不能回写旧修订。
        if (isCurrent()) workspace.accept(next)
      }),
    )
    if (ok) notifySaved()
    return ok
  }
  return { api, workspace, resource, draft, mutation, load, save }
}
