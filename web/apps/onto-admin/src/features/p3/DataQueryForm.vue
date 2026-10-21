<script setup lang="ts">
/** MODEL 与实例查询分开配置；参数绑定通过助手输入字段选择，不接受执行 SQL。 */
import { computed, reactive, ref } from 'vue'
import type { DataQuery, MappingCollection, OntProperty } from '@onto/api-client'
const props = defineProps<{
  value: DataQuery | null
  mappings: MappingCollection
  properties: OntProperty[]
  inputParams: string[]
  canWrite: boolean
  pending: boolean
  stale: boolean
  error?: string
}>()
const emit = defineEmits<{ save: [value: DataQuery | null] }>()
const mode = ref<'MODEL' | 'OBJECT' | 'RELATION'>(props.value?.mode ?? 'MODEL')
const state = reactive({
  objectMappingId: props.value?.objectMappingId ?? '',
  relationMappingId: props.value?.relationMappingId ?? '',
  propertyIds: [...(props.value?.propertyIds ?? [])],
  filters: props.value?.filters.map((item) => ({ ...item })) ?? [],
})
const error = ref(''),
  disabled = computed(() => !props.canWrite || props.pending || props.stale)
const selectedRelation = computed(() =>
  props.mappings.relations.find(
    (item) =>
      item.id === state.relationMappingId && item.fromObjectMappingId === state.objectMappingId,
  ),
)
const bindings = computed(() => {
  const ends =
    mode.value === 'RELATION'
      ? selectedRelation.value
        ? [
            { id: selectedRelation.value.fromObjectMappingId, role: '起点' },
            { id: selectedRelation.value.toObjectMappingId, role: '终点' },
          ]
        : []
      : [{ id: state.objectMappingId, role: '对象' }]
  return ends.flatMap((end) => {
    const object = props.mappings.objects.find((item) => item.id === end.id)
    return props.mappings.properties
      .filter((item) => item.objectMappingId === end.id)
      .flatMap((mapping, index) => {
        const property = props.properties.find((item) => item.id === mapping.targetPropertyId)
        return property
          ? [
              {
                id: property.id,
                key: `${end.role}-${mapping.id ?? index}`,
                label: `${property.name} (${property.rangeType || '未声明类型'}) · ${end.role} ${object?.sourceResource || end.id}.${mapping.sourceField || ''}`,
              },
            ]
          : []
      })
  })
})
const ambiguousIds = computed(() => {
  const counts = new Map<string, number>()
  for (const item of bindings.value) counts.set(item.id, (counts.get(item.id) ?? 0) + 1)
  return new Set([...counts].filter(([, count]) => count > 1).map(([id]) => id))
})
// 同一本体属性对应两端时 API 不能表达端别；不得去重后偷偷任选一端。
const available = computed(() => bindings.value.filter((item) => !ambiguousIds.value.has(item.id)))
function objectChanged() {
  state.propertyIds = []
  state.filters = []
  state.relationMappingId = ''
}
function save() {
  error.value = ''
  if (disabled.value) return
  if (mode.value === 'MODEL') {
    emit('save', null)
    return
  }
  if (
    state.propertyIds.some((id) => ambiguousIds.value.has(id)) ||
    state.filters.some((item) => ambiguousIds.value.has(item.propertyId))
  ) {
    error.value = '所选本体属性在关系两端存在歧义，请拆分操作后重新配置。'
    return
  }
  if (
    !props.mappings.objects.some((item) => item.id === state.objectMappingId) ||
    (mode.value === 'RELATION' &&
      !props.mappings.relations.some(
        (item) =>
          item.id === state.relationMappingId && item.fromObjectMappingId === state.objectMappingId,
      ))
  ) {
    error.value = '请选择对象映射及关系模式所需的关系映射。'
    return
  }
  if (
    !state.propertyIds.length ||
    state.propertyIds.some((id) => !available.value.some((item) => item.id === id))
  ) {
    error.value = '至少选择一个已映射属性。'
    return
  }
  if (
    state.filters.some(
      (item) =>
        !available.value.some((property) => property.id === item.propertyId) ||
        !props.inputParams.includes(item.inputParam),
    )
  ) {
    error.value = '每个过滤条件必须选择映射属性与助手输入参数。'
    return
  }
  emit('save', {
    mode: mode.value,
    objectMappingId: state.objectMappingId,
    ...(mode.value === 'RELATION' ? { relationMappingId: state.relationMappingId } : {}),
    propertyIds: [...state.propertyIds],
    filters: state.filters.map((item) => ({ ...item })),
  })
}
</script>
<template>
  <el-form label-position="top" data-editor="data-query" @submit.prevent="save">
    <el-form-item label="查询模式">
      <el-select v-model="mode" :disabled="disabled">
        <el-option value="MODEL" label="MODEL · 模型元查询" />
        <el-option value="OBJECT" label="OBJECT · 对象实例查询" />
        <el-option value="RELATION" label="RELATION · 关系实例查询" />
      </el-select>
    </el-form-item>
    <template v-if="mode !== 'MODEL'">
      <p class="readonly-note">
        实例查询需要完整助手声明与必需守卫绑定；缺少守卫时服务端在访问数据源前拒绝。请先维护助手声明、策略与空间授权。
      </p>
      <el-form-item label="对象映射">
        <el-select v-model="state.objectMappingId" :disabled="disabled" @change="objectChanged">
          <el-option
            v-for="item in mappings.objects"
            :key="item.id"
            :value="item.id"
            :label="`${item.sourceResource} (${item.id})`"
          />
        </el-select>
      </el-form-item>
      <el-form-item v-if="mode === 'RELATION'" label="关系映射">
        <el-select v-model="state.relationMappingId" :disabled="disabled">
          <el-option
            v-for="item in mappings.relations.filter(
              (item) => item.fromObjectMappingId === state.objectMappingId,
            )"
            :key="item.id"
            :value="item.id"
            :label="`${item.relationId} (${item.id})`"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="输出属性">
        <el-select v-model="state.propertyIds" multiple :disabled="disabled">
          <el-option
            v-for="item in available"
            :key="item.key"
            :value="item.id"
            :label="item.label"
          />
        </el-select>
      </el-form-item>
      <p v-if="ambiguousIds.size" class="readonly-note">
        以下本体属性在关系两端存在歧义，不能选取，请拆分操作：{{ [...ambiguousIds].join('、') }}
      </p>
      <h3>参数化过滤</h3>
      <div v-for="(item, index) in state.filters" :key="index" class="panel">
        <el-form-item label="过滤属性">
          <el-select v-model="item.propertyId" :disabled="disabled">
            <el-option
              v-for="property in available"
              :key="property.key"
              :value="property.id"
              :label="property.label"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="比较方式">
          <el-select v-model="item.operator" :disabled="disabled">
            <el-option
              v-for="operator in ['EQ', 'IN', 'GT', 'GE', 'LT', 'LE']"
              :key="operator"
              :value="operator"
              :label="operator"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="助手输入参数">
          <el-select v-model="item.inputParam" :disabled="disabled">
            <el-option v-for="param in inputParams" :key="param" :value="param" :label="param" />
          </el-select>
        </el-form-item>
        <el-button :disabled="disabled" @click="state.filters.splice(index, 1)">移除条件</el-button>
      </div>
      <el-button
        :disabled="disabled"
        @click="state.filters.push({ propertyId: '', operator: 'EQ', inputParam: '' })"
      >
        添加过滤条件
      </el-button>
    </template>
    <p v-if="error || props.error" class="error-text" role="alert">{{ error || props.error }}</p>
    <p v-if="stale" class="readonly-note">此配置已过期，输入保留。刷新后重新编辑。</p>
    <el-button type="primary" native-type="submit" :disabled="disabled">保存查询配置</el-button>
  </el-form>
</template>
