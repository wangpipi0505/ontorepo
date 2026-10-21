import { watch } from 'vue'
import type { MetadataSnapshot, MetadataSummary } from '@onto/api-client'
import { useSession } from '../../stores/session'
import { useWorkspace } from '../../stores/workspace'
import { useResource } from '../../composables/useResource'

/** 切源立即撤销旧快照请求；相同快照 ID 也必须同时归属于所选源和空间。 */
export function useMetadataSelection(sourceId: () => string, snapshotId: () => string) {
  const api = useSession().api,
    workspace = useWorkspace()
  const summaries = useResource<MetadataSummary[]>([]),
    snapshot = useResource<MetadataSnapshot | null>(null)
  async function loadSummaries() {
    const source = sourceId(),
      spaceId = workspace.space?.id
    if (source && spaceId)
      await summaries.load(() =>
        api.get<MetadataSummary[]>(`/datasources/${source}/snapshots?spaceId=${spaceId}`),
      )
  }
  async function loadSnapshot() {
    const source = sourceId(),
      id = snapshotId(),
      spaceId = workspace.space?.id
    if (source && id && spaceId)
      await snapshot.load(() =>
        api.get<MetadataSnapshot>(`/datasources/${source}/snapshots/${id}?spaceId=${spaceId}`),
      )
  }
  watch(
    sourceId,
    () => {
      summaries.reset()
      snapshot.reset()
      void loadSummaries()
    },
    { immediate: true },
  )
  watch(
    [sourceId, snapshotId],
    () => {
      snapshot.reset()
      void loadSnapshot()
    },
    { immediate: true },
  )
  return { summaries, snapshot, loadSummaries, loadSnapshot }
}
