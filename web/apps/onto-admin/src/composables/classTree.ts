import type { OntClass } from '@onto/api-client'
export interface ClassNode extends OntClass {
  children: ClassNode[]
}
/** 父级选择和拖放共用校验；后端仍承担授权、跨空间与事务内防环校验。 */
export function canReparent(items: OntClass[], id: string, parentId: string | null): boolean {
  if (parentId === null) return true
  const source = items.find((item) => item.id === id)
  const seen = new Set<string>([id])
  let current: string | null = parentId
  while (current) {
    if (seen.has(current)) return false
    seen.add(current)
    const item = items.find((entry) => entry.id === current)
    if (!item || (source && item.spaceId !== source.spaceId)) return false
    current = item.parentId
  }
  return true
}
/** 拷贝节点再组树，避免 UI children 污染真实 API 实体。 */
export function buildTree(items: OntClass[]): ClassNode[] {
  const nodes = new Map(items.map((item) => [item.id, { ...item, children: [] } as ClassNode]))
  const roots: ClassNode[] = []
  for (const node of nodes.values()) {
    const parent = node.parentId ? nodes.get(node.parentId) : undefined
    if (parent && canReparent(items, node.id, parent.id)) parent.children.push(node)
    else roots.push(node)
  }
  return roots
}
