<script setup lang="ts">
/** 名称、类型与关联以基本字段维护；候选关联 ID 与实体 ID 互斥提交。 */
import { computed } from 'vue'
import type { JsonObject, OntClass, Suggestion } from '@onto/api-client'
const props = defineProps<{
  kind: Suggestion['kind']
  classes: OntClass[]
  suggestions: Suggestion[]
  batchId: string
  suggestionId: string
  disabled: boolean
}>()
const payload = defineModel<JsonObject>({ required: true })
const classCandidates = computed(() =>
  props.suggestions.filter(
    (item) =>
      item.kind === 'CLASS' &&
      item.id !== props.suggestionId &&
      item.batchId === props.batchId &&
      item.status !== 'REJECTED',
  ),
)
function text(key: string) {
  const value = payload.value[key]
  return typeof value === 'string' ? value : ''
}
function set(key: string, value: unknown) {
  payload.value = { ...payload.value, [key]: value }
}
function reference(entity: string, candidate: string) {
  return text(entity)
    ? `entity:${text(entity)}`
    : text(candidate)
      ? `suggestion:${text(candidate)}`
      : ''
}
function setReference(entity: string, candidate: string, value: string) {
  const next = { ...payload.value }
  delete next[entity]
  delete next[candidate]
  if (value.startsWith('entity:')) next[entity] = value.slice(7)
  else if (value.startsWith('suggestion:')) next[candidate] = value.slice(11)
  else if (entity === 'parentId') next.parentId = null
  payload.value = next
}
const associations = computed(() =>
  props.kind === 'CLASS'
    ? [{ label: '父类（可选）', entity: 'parentId', candidate: 'parentSuggestionId' }]
    : props.kind === 'PROPERTY'
      ? [{ label: '所属类', entity: 'domainClassId', candidate: 'domainSuggestionId' }]
      : [
          { label: '起点类', entity: 'fromClassId', candidate: 'fromSuggestionId' },
          { label: '终点类', entity: 'toClassId', candidate: 'toSuggestionId' },
        ],
)
</script>
<template>
  <div class="stack">
    <el-form-item label="候选名称">
      <el-input
        :model-value="text('name')"
        :disabled="disabled"
        @update:model-value="set('name', $event)"
      />
    </el-form-item>
    <el-form-item v-if="kind !== 'RELATION'" label="描述">
      <el-input
        :model-value="text('description')"
        :disabled="disabled"
        type="textarea"
        @update:model-value="set('description', $event)"
      />
    </el-form-item>
    <el-form-item v-for="item in associations" :key="item.entity" :label="item.label">
      <el-select
        :model-value="reference(item.entity, item.candidate)"
        :disabled="disabled"
        clearable
        @update:model-value="setReference(item.entity, item.candidate, String($event))"
      >
        <el-option
          v-for="entity in classes"
          :key="entity.id"
          :value="`entity:${entity.id}`"
          :label="`已有类：${entity.name}`"
        />
        <el-option
          v-for="candidate in classCandidates"
          :key="candidate.id"
          :value="`suggestion:${candidate.id}`"
          :label="`同批候选：${String(candidate.payload.name ?? candidate.id)}`"
        />
      </el-select>
    </el-form-item>
    <template v-if="kind === 'PROPERTY'">
      <el-form-item label="值类型">
        <el-select
          :model-value="text('rangeType')"
          :disabled="disabled"
          @update:model-value="set('rangeType', $event)"
        >
          <el-option
            v-for="item in ['STRING', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'DATE']"
            :key="item"
            :value="item"
            :label="item"
          />
        </el-select>
      </el-form-item>
      <el-checkbox
        :model-value="payload.required === true"
        :disabled="disabled"
        @update:model-value="set('required', $event)"
      >
        必填属性
      </el-checkbox>
    </template>
    <template v-if="kind === 'RELATION'">
      <el-form-item label="关系基数">
        <el-select
          :model-value="text('cardinality')"
          :disabled="disabled"
          @update:model-value="set('cardinality', $event)"
        >
          <el-option
            v-for="item in ['ONE_TO_ONE', 'ONE_TO_MANY', 'MANY_TO_ONE', 'MANY_TO_MANY']"
            :key="item"
            :value="item"
            :label="item"
          />
        </el-select>
      </el-form-item>
      <div class="row">
        <el-checkbox
          :model-value="payload.transitive === true"
          :disabled="disabled"
          @update:model-value="set('transitive', $event)"
        >
          传递关系
        </el-checkbox>
        <el-checkbox
          :model-value="payload.symmetric === true"
          :disabled="disabled"
          @update:model-value="set('symmetric', $event)"
        >
          对称关系（超 EL）
        </el-checkbox>
      </div>
    </template>
  </div>
</template>
