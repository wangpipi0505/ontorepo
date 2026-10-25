/** 图节点/边的最小契约；业务页面只提供标签与冲突标记，不向图形层泄漏领域对象。 */
export interface GraphNode {
  id: string
  label: string
  conflict?: boolean
  /** UIF-O5：泳道语义（PROV-O：AGENT/ACTIVITY/ENTITY 及其别名），驱动节点底色。 */
  lane?: string
}
export interface GraphEdge {
  id: string
  source: string
  target: string
  label: string
}
