import type {
  ApiClient,
  Conflicts,
  MappingValidation,
  Relation,
  Release,
  Rule,
  Space,
} from '@onto/api-client'
import { errorMessage } from '../../composables/useResource'
import { readDraftSnapshot } from '../../composables/readDraftSnapshot'

/** 单项读取失败保留未知态；不把接口故障折算成零条记录或通过。 */
export type Reading<T> = { value: T; error: '' } | { value: null; error: string }
export interface SpaceOverview {
  space: Space
  relations: Reading<number>
  rules: Reading<number>
  conflicts: Reading<Conflicts>
  mappings: Reading<MappingValidation>
  release: Reading<Release | null>
}
async function read<T>(work: () => Promise<T>): Promise<Reading<T>> {
  try {
    return { value: await work(), error: '' }
  } catch (cause) {
    return { value: null, error: errorMessage(cause) }
  }
}
export async function loadOverview(
  api: Pick<ApiClient, 'get'>,
  id: string,
): Promise<SpaceOverview> {
  const snapshot = await readDraftSnapshot(api, id, async () => {
    const [relations, rules, conflicts, release, mappings] = await Promise.all([
      read(async () => (await api.get<Relation[]>(`/relations?spaceId=${id}`)).length),
      read(async () => (await api.get<Rule[]>(`/rules?spaceId=${id}`)).length),
      read(() => api.get<Conflicts>(`/spaces/${id}/conflicts`)),
      read(async () =>
        (await api.get<Release[]>(`/releases?spaceId=${id}`)).reduce<Release | null>(
          (latest, item) => (!latest || item.versionNo > latest.versionNo ? item : latest),
          null,
        ),
      ),
      read(() => api.get<MappingValidation>(`/mappings/validate?spaceId=${id}`)),
    ])
    return { relations, rules, conflicts, release, mappings }
  })
  return { ...snapshot.items, space: snapshot.space }
}
export const releaseLabels: Record<Release['status'], string> = {
  READY: '已发布 · 可消费',
  DEPRECATED: '已弃用 · 不可消费',
  CORRUPTED: '制品损坏 · 不可消费',
}
