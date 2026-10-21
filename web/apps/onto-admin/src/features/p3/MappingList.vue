<script setup lang="ts">
/** 映射列表独立呈现目标名称与来源链，表单开着时不允许切层或切编辑对象。 */
import type {
  Mapping,
  MappingCollection,
  MappingKind,
  OntClass,
  OntProperty,
  Relation,
} from '@onto/api-client'
const props = defineProps<{
  mappings: MappingCollection
  classes: OntClass[]
  properties: OntProperty[]
  relations: Relation[]
  canWrite: boolean
  disabled: boolean
}>()
const kind = defineModel<MappingKind>('kind', { required: true })
const emit = defineEmits<{ edit: [value: Mapping | null] }>()
const labels: Record<MappingKind, string> = {
  objects: '对象映射',
  properties: '属性映射',
  relations: '关系映射',
}
function summary(item: Mapping) {
  if ('sourceResource' in item)
    return `${item.sourceResource} → ${props.classes.find((c) => c.id === item.targetClassId)?.name || item.targetClassId}`
  if ('sourceField' in item)
    return `${item.sourceField} → ${props.properties.find((p) => p.id === item.targetPropertyId)?.name || item.targetPropertyId}`
  return `${item.fromObjectMappingId} → ${item.toObjectMappingId} · ${props.relations.find((r) => r.id === item.relationId)?.name || item.relationId}`
}
function trace(item: Mapping) {
  if ('objectMappingId' in item) {
    const parent = props.mappings.objects.find((value) => value.id === item.objectMappingId)
    return `对象映射 ${item.objectMappingId} / 源 ${parent?.datasourceId ?? '引用不可达'} / ${parent?.sourceResource ?? ''} / 字段 ${item.sourceField} / 快照 ${parent?.metaSnapshotId ?? ''}`
  }
  return `源 ${item.datasourceId} / 快照 ${item.metaSnapshotId}`
}
</script>
<template>
  <section class="panel stack">
    <div class="row">
      <el-button
        v-for="(label, key) in labels"
        :key="key"
        :type="kind === key ? 'primary' : 'default'"
        :disabled="disabled"
        @click="kind = key"
      >
        {{ label }} · {{ mappings[key].length }}
      </el-button>
      <el-button v-if="canWrite" :disabled="disabled" @click="emit('edit', null)">
        新增{{ labels[kind] }}
      </el-button>
    </div>
    <el-table :data="mappings[kind]" empty-text="当前层尚无映射">
      <el-table-column prop="id" label="映射标识" />
      <el-table-column label="来源与目标" min-width="260">
        <template #default="{ row }">
          {{ summary(row) }}
          <p class="muted">{{ trace(row) }}</p>
          <details>
            <summary>查看完整定义</summary>
            <dl class="details-grid">
              <template v-for="(value, key) in row" :key="key">
                <dt>{{ key }}</dt>
                <dd>{{ typeof value === 'object' ? JSON.stringify(value) : value }}</dd>
              </template>
            </dl>
          </details>
        </template>
      </el-table-column>
      <el-table-column label="操作">
        <template #default="{ row }">
          <el-button v-if="canWrite" text :disabled="disabled" @click="emit('edit', row)">
            影响 / 编辑
          </el-button>
          <span v-else>只读</span>
        </template>
      </el-table-column>
    </el-table>
  </section>
</template>
