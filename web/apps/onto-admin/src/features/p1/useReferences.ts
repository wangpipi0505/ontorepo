import { useResource } from '../../composables/useResource'
import { readDraftSnapshot } from '../../composables/readDraftSnapshot'
import { useSession } from '../../stores/session'
import { useWorkspace } from '../../stores/workspace'
interface NamedReference {
  id: string
  name: string
}
/** 选择器只读服务端同空间条目；失败直接展示，不提供本地演示条目，也不解锁旧表单。 */
export function useReferences(paths: string[]) {
  const resource = useResource<Record<string, NamedReference[]>>({})
  async function load() {
    const id = useWorkspace().space?.id,
      api = useSession().api
    if (!id) return
    return resource.load(async () => {
      const result = await readDraftSnapshot(api, id, async () => {
        // 原属性列表要求 classId；全空间选择器必须使用独立只读入口，保留旧接口400语义。
        const pairs = await Promise.all(
          paths.map(
            async (path) =>
              [
                path,
                await api.get<NamedReference[]>(
                  path === 'properties' ? `/spaces/${id}/properties` : `/${path}?spaceId=${id}`,
                ),
              ] as const,
          ),
        )
        return Object.fromEntries(pairs)
      })
      return result.items
    })
  }
  return { ...resource, load }
}
