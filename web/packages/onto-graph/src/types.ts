/** 图节点/边的最小契约；业务页面只提供标签与冲突标记，不向图形层泄漏领域对象。 */
export interface GraphNode {
  id: string
  label: string
  conflict?: boolean
}
export interface GraphEdge {
  id: string
  source: string
  target: string
  label: string
}
