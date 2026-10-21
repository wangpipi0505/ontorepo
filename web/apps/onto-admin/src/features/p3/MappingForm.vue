<script setup lang="ts">
/** 三层映射使用可追溯选择器；复杂过滤才接受 JSON，不接受任意脚本或 SQL。 */
import { computed, reactive, ref } from 'vue'
import type {
  Codebook,
  Datasource,
  Mapping,
  MappingCollection,
  MappingKind,
  ObjectMapping,
  OntClass,
  OntProperty,
  PropertyMapping,
  Relation,
  RelationMapping,
} from '@onto/api-client'
import { objectJson } from './sourceFields'
import { useMetadataSelection } from './useMetadataSelection'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import type { MappingPrefill } from './mappingGraph'
const props = defineProps<{
  kind: MappingKind
  value: Mapping | null
  prefill?: MappingPrefill
  sources: Datasource[]
  mappings: MappingCollection
  classes: OntClass[]
  properties: OntProperty[]
  relations: Relation[]
  codebooks: Codebook[]
  canWrite: boolean
  pending: boolean
  stale: boolean
  error?: string
}>()
const emit = defineEmits<{ save: [value: Record<string, unknown>]; cancel: [] }>()
const object =
  props.kind === 'objects'
    ? props.prefill?.kind === 'objects'
      ? props.prefill.value
      : (props.value as ObjectMapping | null)
    : null
const property =
  props.kind === 'properties'
    ? props.prefill?.kind === 'properties'
      ? props.prefill.value
      : (props.value as PropertyMapping | null)
    : null
const relation = props.kind === 'relations' ? (props.value as RelationMapping | null) : null
const fields = reactive({
  datasourceId: object?.datasourceId ?? relation?.datasourceId ?? '',
  metaSnapshotId: object?.metaSnapshotId ?? relation?.metaSnapshotId ?? '',
  sourceResource: object?.sourceResource ?? '',
  targetClassId: object?.targetClassId ?? '',
  objectKeyExpr: object?.objectKeyExpr ?? '',
  filter: object?.filter ? JSON.stringify(object.filter, null, 2) : '',
  objectMappingId: property?.objectMappingId ?? '',
  sourceField: property?.sourceField ?? '',
  targetPropertyId: property?.targetPropertyId ?? '',
  required: property?.required ?? false,
  transform: property?.transform?.kind ?? 'IDENTITY',
  codebookId: property?.transform?.codebookId ?? '',
  relationId: relation?.relationId ?? '',
  fromObjectMappingId: relation?.fromObjectMappingId ?? '',
  toObjectMappingId: relation?.toObjectMappingId ?? '',
  fromKeyExpr: relation?.fromKeyExpr ?? '',
  toKeyExpr: relation?.toKeyExpr ?? '',
  fromField: relation?.join.fromField ?? '',
  toField: relation?.join.toField ?? '',
  cardinalityCheck: relation?.cardinalityCheck ?? true,
})
const error = ref(''),
  disabled = computed(() => !props.canWrite || props.pending || props.stale)
const parent = computed(() =>
  props.mappings.objects.find((item) => item.id === fields.objectMappingId),
)
const meta = useMetadataSelection(
  () => (props.kind === 'properties' ? (parent.value?.datasourceId ?? '') : fields.datasourceId),
  () =>
    props.kind === 'properties' ? (parent.value?.metaSnapshotId ?? '') : fields.metaSnapshotId,
)
const resources = computed(() => meta.snapshot.data.value?.resources ?? [])
const resource = computed(() =>
  resources.value.find(
    (item) =>
      item.name ===
      (props.kind === 'properties' ? parent.value?.sourceResource : fields.sourceResource),
  ),
)
const candidates = computed(() =>
  props.mappings.objects.filter(
    (item) =>
      item.datasourceId === fields.datasourceId && item.metaSnapshotId === fields.metaSnapshotId,
  ),
)
const from = computed(() =>
    candidates.value.find((item) => item.id === fields.fromObjectMappingId),
  ),
  to = computed(() => candidates.value.find((item) => item.id === fields.toObjectMappingId))
const fromFields = computed(
  () => resources.value.find((item) => item.name === from.value?.sourceResource)?.fields ?? [],
)
const toFields = computed(
  () => resources.value.find((item) => item.name === to.value?.sourceResource)?.fields ?? [],
)
function sourceChanged() {
  fields.metaSnapshotId = ''
  fields.sourceResource = ''
  fields.fromObjectMappingId = ''
  fields.toObjectMappingId = ''
}
function snapshotChanged() {
  fields.sourceResource = ''
  fields.fromObjectMappingId = ''
  fields.toObjectMappingId = ''
}
function objectChanged() {
  fields.sourceField = ''
  fields.targetPropertyId = ''
}
function save() {
  error.value = ''
  if (disabled.value) return
  try {
    if (!meta.snapshot.data.value) throw new Error('请先选择并读取同源元数据快照。')
    let body: Record<string, unknown>
    if (props.kind === 'objects') {
      if (
        !fields.datasourceId ||
        !fields.metaSnapshotId ||
        !resource.value ||
        !fields.targetClassId ||
        !fields.objectKeyExpr.trim()
      )
        throw new Error('源、快照、资源、目标类和对象标识模板必填。')
      const names = new Set(resource.value.fields.map((item) => item.name))
      const template = fields.objectKeyExpr.trim(),
        matches = [...template.matchAll(/\$\{([^}]+)\}/g)]
      if (
        !matches.length ||
        matches.some((item) => !names.has(item[1]!)) ||
        /[^\w:/.#-]/.test(template.replace(/\$\{[^}]+\}/g, ''))
      )
        throw new Error('对象标识只能使用当前资源的 ${字段} 与固定分隔符。')
      body = {
        datasourceId: fields.datasourceId,
        metaSnapshotId: fields.metaSnapshotId,
        sourceResource: fields.sourceResource,
        targetClassId: fields.targetClassId,
        objectKeyExpr: template,
        filter: objectJson(fields.filter, '过滤条件'),
      }
    } else if (props.kind === 'properties') {
      if (
        !parent.value ||
        !resource.value?.fields.some((item) => item.name === fields.sourceField) ||
        !fields.targetPropertyId
      )
        throw new Error('对象映射、来源字段与目标属性必填。')
      if (fields.transform === 'CODEBOOK' && !fields.codebookId)
        throw new Error('码表转换必须选择码表。')
      body = {
        objectMappingId: fields.objectMappingId,
        sourceField: fields.sourceField,
        targetPropertyId: fields.targetPropertyId,
        required: fields.required,
        transform: {
          kind: fields.transform,
          ...(fields.transform === 'CODEBOOK' ? { codebookId: fields.codebookId } : {}),
        },
      }
    } else {
      if (
        !from.value ||
        !to.value ||
        !fields.relationId ||
        !fields.fromKeyExpr ||
        !fields.toKeyExpr ||
        !fromFields.value.some((item) => item.name === fields.fromField) ||
        !toFields.value.some((item) => item.name === fields.toField)
      )
        throw new Error('关系、同源同快照的两端对象映射、连接字段和标识模板必填。')
      body = {
        datasourceId: fields.datasourceId,
        metaSnapshotId: fields.metaSnapshotId,
        relationId: fields.relationId,
        fromObjectMappingId: fields.fromObjectMappingId,
        toObjectMappingId: fields.toObjectMappingId,
        fromKeyExpr: fields.fromKeyExpr,
        toKeyExpr: fields.toKeyExpr,
        join: { fromField: fields.fromField, toField: fields.toField },
        cardinalityCheck: fields.cardinalityCheck,
      }
    }
    emit('save', body)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '请检查映射字段。'
  }
}
</script>
<template>
  <el-form label-position="top" data-editor="mapping" @submit.prevent="save">
    <template v-if="kind !== 'properties'">
      <el-form-item label="数据源">
        <el-select v-model="fields.datasourceId" :disabled="disabled" @change="sourceChanged">
          <el-option
            v-for="item in sources"
            :key="item.id"
            :value="item.id"
            :label="`${item.name} (${item.code})`"
          />
        </el-select>
      </el-form-item>
      <AsyncState
        :loading="meta.summaries.loading.value"
        :error="meta.summaries.error.value"
        @retry="meta.loadSummaries"
      >
        <el-form-item label="元数据快照">
          <el-select v-model="fields.metaSnapshotId" :disabled="disabled" @change="snapshotChanged">
            <el-option
              v-for="item in meta.summaries.data.value"
              :key="item.id"
              :value="item.id"
              :label="`${item.capturedAt} · ${item.sha256}`"
            />
          </el-select>
        </el-form-item>
      </AsyncState>
    </template>
    <el-form-item v-if="kind === 'properties'" label="所属对象映射">
      <el-select v-model="fields.objectMappingId" :disabled="disabled" @change="objectChanged">
        <el-option
          v-for="item in mappings.objects"
          :key="item.id"
          :value="item.id"
          :label="`${item.sourceResource} → ${classes.find((c) => c.id === item.targetClassId)?.name || item.targetClassId} (${item.id})`"
        />
      </el-select>
    </el-form-item>
    <AsyncState
      :loading="meta.snapshot.loading.value"
      :error="meta.snapshot.error.value"
      @retry="meta.loadSnapshot"
    >
      <template v-if="kind === 'objects'">
        <el-form-item label="来源资源">
          <el-select
            v-model="fields.sourceResource"
            :disabled="disabled"
            @change="fields.objectKeyExpr = ''"
          >
            <el-option
              v-for="item in resources"
              :key="item.name"
              :value="item.name"
              :label="item.name"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="目标本体类">
          <el-select v-model="fields.targetClassId" :disabled="disabled">
            <el-option v-for="item in classes" :key="item.id" :value="item.id" :label="item.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="对象标识模板">
          <el-input
            v-model="fields.objectKeyExpr"
            :disabled="disabled"
            placeholder="例如 device:${id}"
          />
        </el-form-item>
        <div class="row">
          <el-button
            v-for="item in resource?.fields"
            :key="item.name"
            text
            :disabled="disabled"
            @click="fields.objectKeyExpr += '${' + item.name + '}'"
          >
            插入 {{ item.name }}
          </el-button>
        </div>
        <el-form-item label="可选复杂过滤（JSON 对象）">
          <el-input v-model="fields.filter" type="textarea" :rows="3" :disabled="disabled" />
        </el-form-item>
      </template>
      <template v-else-if="kind === 'properties'">
        <p v-if="parent" class="muted">
          来源 {{ parent.datasourceId }} / {{ parent.sourceResource }} / 快照
          {{ parent.metaSnapshotId }}
        </p>
        <el-form-item label="来源字段">
          <el-select v-model="fields.sourceField" :disabled="disabled">
            <el-option
              v-for="item in resource?.fields"
              :key="item.name"
              :value="item.name"
              :label="`${item.name} (${item.type})`"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="目标本体属性">
          <el-select v-model="fields.targetPropertyId" :disabled="disabled">
            <el-option
              v-for="item in properties.filter(
                (item) => item.domainClassId === parent?.targetClassId,
              )"
              :key="item.id"
              :value="item.id"
              :label="`${item.name} (${item.rangeType})`"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="类型转换">
          <el-select v-model="fields.transform" :disabled="disabled">
            <el-option
              v-for="item in ['IDENTITY', 'TO_STRING', 'TO_NUMBER', 'TO_BOOLEAN', 'CODEBOOK']"
              :key="item"
              :value="item"
              :label="item"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="fields.transform === 'CODEBOOK'" label="转换码表">
          <el-select v-model="fields.codebookId" :disabled="disabled">
            <el-option
              v-for="item in codebooks"
              :key="item.id"
              :value="item.id"
              :label="item.name"
            />
          </el-select>
        </el-form-item>
        <el-checkbox v-model="fields.required" :disabled="disabled">必填属性</el-checkbox>
      </template>
      <template v-else>
        <el-form-item label="目标本体关系">
          <el-select v-model="fields.relationId" :disabled="disabled">
            <el-option
              v-for="item in relations"
              :key="item.id"
              :value="item.id"
              :label="item.name"
            />
          </el-select>
        </el-form-item>
        <div class="form-columns">
          <div>
            <el-form-item label="起点对象映射">
              <el-select v-model="fields.fromObjectMappingId" :disabled="disabled">
                <el-option
                  v-for="item in candidates"
                  :key="item.id"
                  :value="item.id"
                  :label="`${item.sourceResource} (${item.id})`"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="起点连接字段">
              <el-select v-model="fields.fromField" :disabled="disabled">
                <el-option
                  v-for="item in fromFields"
                  :key="item.name"
                  :value="item.name"
                  :label="item.name"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="起点标识模板">
              <el-input v-model="fields.fromKeyExpr" :disabled="disabled" />
            </el-form-item>
          </div>
          <div>
            <el-form-item label="终点对象映射">
              <el-select v-model="fields.toObjectMappingId" :disabled="disabled">
                <el-option
                  v-for="item in candidates"
                  :key="item.id"
                  :value="item.id"
                  :label="`${item.sourceResource} (${item.id})`"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="终点连接字段">
              <el-select v-model="fields.toField" :disabled="disabled">
                <el-option
                  v-for="item in toFields"
                  :key="item.name"
                  :value="item.name"
                  :label="item.name"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="终点标识模板">
              <el-input v-model="fields.toKeyExpr" :disabled="disabled" />
            </el-form-item>
          </div>
        </div>
        <el-checkbox v-model="fields.cardinalityCheck" :disabled="disabled">
          校验关系基数
        </el-checkbox>
      </template>
    </AsyncState>
    <p class="muted">
      标识模板仅允许 ${字段} 与固定分隔符。协议是否支持 JOIN、转换与引用一致性以服务端校验为准。
    </p>
    <p v-if="error || props.error" class="error-text" role="alert">{{ error || props.error }}</p>
    <p v-if="stale" class="readonly-note">旧表单已暂停提交，输入保留。请关闭并刷新再编辑。</p>
    <div class="row">
      <el-button
        type="primary"
        native-type="submit"
        :disabled="disabled || meta.snapshot.loading.value"
      >
        保存映射
      </el-button>
      <el-button :disabled="pending" @click="emit('cancel')">关闭表单</el-button>
    </div>
  </el-form>
</template>
