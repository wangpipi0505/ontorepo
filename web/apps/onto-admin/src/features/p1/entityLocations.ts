/** 冲突只返回稳定标识；导航目标必须由同一草稿快照中的实体类型确定，不能猜测不存在的条目。 */
export interface EntityLocation {
  id: string
  name: string
  path: string
  classId?: string
}
export interface LocationRecord {
  id: string
  name: string
  domainClassId?: string
}
export const conflictEntityPaths = [
  'classes',
  'properties',
  'rules',
  'constraints',
  'operations',
  'strategies',
] as const
export function entityLocations(lists: Record<string, LocationRecord[]>) {
  const index: Record<string, EntityLocation> = {}
  for (const path of conflictEntityPaths) {
    for (const item of lists[path] || [])
      index[item.id] = {
        id: item.id,
        name: item.name,
        path: path === 'classes' || path === 'properties' ? 'model' : path,
        ...(path === 'properties' ? { classId: item.domainClassId } : {}),
      }
  }
  return index
}
