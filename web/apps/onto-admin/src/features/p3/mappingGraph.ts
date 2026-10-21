import type {
  Mapping,
  MappingCollection,
  MappingKind,
  MetadataSnapshot,
  ObjectMapping,
  OntClass,
  OntProperty,
  PropertyMapping,
  RangeType,
  Relation,
} from '@onto/api-client'

export type SourcePick = { resource: string; objectMappingId?: string; field?: string }
export type TargetPick = { kind: 'class' | 'property'; id: string }
/** 预填没有持久 ID，父页面必须走原有审查表单和 expectedDraftRev 写入。 */
export type MappingPrefill =
  | { kind: 'objects'; value: Omit<ObjectMapping, 'id'> }
  | { kind: 'properties'; value: Omit<PropertyMapping, 'id'> }
export interface GraphContext {
  sourceId: string
  snapshot: MetadataSnapshot
  mappings: MappingCollection
  classes: OntClass[]
  properties: OntProperty[]
}
export const resourceNode = (resource: string) => JSON.stringify(['resource', resource])
export const fieldNode = (object: string, field: string) => JSON.stringify(['field', object, field])
export const targetNode = (kind: 'class' | 'property', id: string) => JSON.stringify([kind, id])
export const relationNode = (side: 'source' | 'target', id: string) =>
  JSON.stringify(['relation', side, id])
export function selectedNode(pick: SourcePick) {
  return pick.field && pick.objectMappingId
    ? fieldNode(pick.objectMappingId, pick.field)
    : resourceNode(pick.resource)
}
/** 仅对已知标量类型预填 IDENTITY；未知/需转换的源类型交给完整表单人工配置。 */
export function scalarType(type: string): RangeType | null {
  const value = type.toUpperCase().replace(/\(.*/, '').trim()
  if (
    /^(SMALLINT|INTEGER|INT|BIGINT|INT[248]|TINYINT|INT(8|16|32|64)|SERIAL|BIGSERIAL)$/.test(value)
  )
    return 'INTEGER'
  if (/^(DECIMAL|NUMERIC|NUMBER|DOUBLE|DOUBLE PRECISION|FLOAT[48]?|REAL|FLOAT(32|64))$/.test(value))
    return 'DECIMAL'
  if (/^(STRING|TEXT|VARCHAR|CHAR|CHARACTER VARYING|CHARACTER|NVARCHAR|UTF8)$/.test(value))
    return 'STRING'
  if (/^(BOOL|BOOLEAN)$/.test(value)) return 'BOOLEAN'
  if (value === 'DATE') return 'DATE'
  return null
}
export function prefillMapping(
  context: GraphContext,
  source: SourcePick,
  target: TargetPick,
): MappingPrefill {
  const resource = context.snapshot.resources.find((item) => item.name === source.resource)
  if (!resource) throw new Error('来源资源已变化，请重新读取快照。')
  if (source.field === undefined) {
    if (target.kind !== 'class')
      throw new Error('来源资源只能连接本体类；字段请先选择所属对象映射。')
    if (!context.classes.some((item) => item.id === target.id))
      throw new Error('目标类已变化，请刷新映射。')
    return {
      kind: 'objects',
      value: {
        datasourceId: context.sourceId,
        metaSnapshotId: context.snapshot.id,
        sourceResource: resource.name,
        targetClassId: target.id,
        objectKeyExpr: resource.primaryKey.map((field) => '${' + field + '}').join(':'),
        filter: null,
      },
    }
  }
  if (target.kind !== 'property') throw new Error('来源字段只能连接本体属性。')
  const object = context.mappings.objects.find(
    (item) =>
      item.id === source.objectMappingId &&
      item.datasourceId === context.sourceId &&
      item.metaSnapshotId === context.snapshot.id &&
      item.sourceResource === resource.name,
  )
  const property = context.properties.find((item) => item.id === target.id)
  const field = resource.fields.find((item) => item.name === source.field)
  if (!object || !field) throw new Error('字段的所属对象映射不属于当前来源和快照。')
  if (!property || property.domainClassId !== object.targetClassId)
    throw new Error('目标属性必须属于该字段映射的所属对象。')
  const type = scalarType(field.type)
  if (
    !type ||
    (type !== property.rangeType && !(type === 'INTEGER' && property.rangeType === 'DECIMAL'))
  )
    throw new Error(
      `类型不兼容或尚未识别（${field.type} → ${property.rangeType}），请通过属性映射表单审查类型转换。`,
    )
  return {
    kind: 'properties',
    value: {
      objectMappingId: object.id,
      sourceField: field.name,
      targetPropertyId: property.id,
      required: property.required ?? false,
      transform: { kind: 'IDENTITY' },
    },
  }
}
export interface MappingEdge {
  id: string
  kind: MappingKind
  item: Mapping
  from: string
  to: string
  label: string
}
export function mappingEdges(context: GraphContext, relations: Relation[]): MappingEdge[] {
  const objects = context.mappings.objects.filter(
    (item) => item.datasourceId === context.sourceId && item.metaSnapshotId === context.snapshot.id,
  )
  const edges: MappingEdge[] = objects.map((item) => ({
    id: item.id,
    kind: 'objects',
    item,
    from: resourceNode(item.sourceResource),
    to: targetNode('class', item.targetClassId),
    label: `${item.sourceResource} → ${context.classes.find((c) => c.id === item.targetClassId)?.name ?? item.targetClassId}`,
  }))
  for (const item of context.mappings.properties) {
    if (!objects.some((object) => object.id === item.objectMappingId)) continue
    edges.push({
      id: item.id,
      kind: 'properties',
      item,
      from: fieldNode(item.objectMappingId, item.sourceField),
      to: targetNode('property', item.targetPropertyId),
      label: `${item.sourceField} → ${context.properties.find((p) => p.id === item.targetPropertyId)?.name ?? item.targetPropertyId}`,
    })
  }
  for (const item of context.mappings.relations) {
    if (item.datasourceId !== context.sourceId || item.metaSnapshotId !== context.snapshot.id)
      continue
    edges.push({
      id: item.id,
      kind: 'relations',
      item,
      from: relationNode('source', item.id),
      to: relationNode('target', item.relationId),
      label: `${relations.find((r) => r.id === item.relationId)?.name ?? item.relationId} · ${item.join.fromField} ↔ ${item.join.toField}`,
    })
  }
  return edges
}
export interface MappingRecommendation {
  id: string
  source: SourcePick
  target: TargetPick
  label: string
  confidence: number
  evidence: string
}
const normalized = (name: string) => name.toLocaleLowerCase().replace(/[\s_-]/g, '')
/** 规则分数不是模型概率；只推荐名称可解释匹配，并保留快照摘要和类型证据。 */
export function recommendMappings(context: GraphContext): MappingRecommendation[] {
  const result: MappingRecommendation[] = []
  const offer = (
    source: SourcePick,
    target: TargetPick,
    sourceName: string,
    targetName: string,
    type?: string,
  ) => {
    const short = sourceName.split('.').at(-1) ?? sourceName
    if (!normalized(short) || normalized(short) !== normalized(targetName)) return
    try {
      prefillMapping(context, source, target)
    } catch {
      return
    }
    const exact = short === targetName
    result.push({
      id: `${selectedNode(source)}:${targetNode(target.kind, target.id)}`,
      source,
      target,
      label: `${sourceName} → ${targetName}`,
      confidence: exact ? 100 : 90,
      evidence: `${exact ? '名称完全匹配' : '忽略大小写、空格与下划线后名称匹配'}${type ? `；类型兼容 ${type}` : ''}；资源 ${source.resource}；快照 ${context.snapshot.id} / ${context.snapshot.sha256}`,
    })
  }
  for (const resource of context.snapshot.resources) {
    for (const cls of context.classes) {
      if (
        !context.mappings.objects.some(
          (item) =>
            item.datasourceId === context.sourceId &&
            item.metaSnapshotId === context.snapshot.id &&
            item.sourceResource === resource.name &&
            item.targetClassId === cls.id,
        )
      )
        offer({ resource: resource.name }, { kind: 'class', id: cls.id }, resource.name, cls.name)
    }
    for (const object of context.mappings.objects.filter(
      (item) =>
        item.datasourceId === context.sourceId &&
        item.metaSnapshotId === context.snapshot.id &&
        item.sourceResource === resource.name,
    )) {
      for (const property of context.properties.filter(
        (item) => item.domainClassId === object.targetClassId,
      )) {
        if (
          context.mappings.properties.some(
            (item) => item.objectMappingId === object.id && item.targetPropertyId === property.id,
          )
        )
          continue
        for (const field of resource.fields)
          offer(
            { resource: resource.name, objectMappingId: object.id, field: field.name },
            { kind: 'property', id: property.id },
            field.name,
            property.name,
            `${field.type} → ${property.rangeType}`,
          )
      }
    }
  }
  return result.sort((a, b) => b.confidence - a.confidence || a.id.localeCompare(b.id))
}
/** 扁平渲染完整父子顺序，循环/悬空历史数据仍可达但不递归失控。 */
export function classRows(classes: OntClass[]): { value: OntClass; depth: number }[] {
  const result: { value: OntClass; depth: number }[] = [],
    seen = new Set<string>()
  function visit(value: OntClass, depth: number) {
    if (seen.has(value.id)) return
    seen.add(value.id)
    result.push({ value, depth })
    classes.filter((item) => item.parentId === value.id).forEach((item) => visit(item, depth + 1))
  }
  classes
    .filter((item) => !item.parentId || !classes.some((parent) => parent.id === item.parentId))
    .forEach((item) => visit(item, 0))
  classes.forEach((item) => visit(item, 0))
  return result
}
