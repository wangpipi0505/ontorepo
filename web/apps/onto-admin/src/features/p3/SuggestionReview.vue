<script setup lang="ts">
/** 默认既不勾选也不确认；显式接受/拒绝后才提交原子批次，409 保留已编辑载荷。 */
import { computed, reactive, ref } from 'vue'
import type { OntClass, Suggestion, SuggestionDecision } from '@onto/api-client'
import SuggestionPayloadFields from './SuggestionPayloadFields.vue'
import SourceEvidence from './SourceEvidence.vue'
const props = defineProps<{
  items: Suggestion[]
  classes?: OntClass[]
  canWrite: boolean
  pending: boolean
  stale: boolean
  error?: string
}>()
const emit = defineEmits<{ decide: [value: SuggestionDecision[]] }>()
const rows = reactive(
  props.items.map((item) => ({
    ...item,
    payload: { ...item.payload },
    checked: false,
    decision: '' as '' | 'ACCEPT' | 'REJECT',
  })),
)
const error = ref(''),
  disabled = computed(() => !props.canWrite || props.pending || props.stale)
const labels = { CLASS: '类', PROPERTY: '属性', RELATION: '关系' }
function bulk(decision: 'ACCEPT' | 'REJECT') {
  if (disabled.value) return
  for (const row of rows) if (row.checked && row.status === 'PENDING') row.decision = decision
}
function submit() {
  error.value = ''
  if (disabled.value) return
  const chosen = rows.filter((item) => item.status === 'PENDING' && item.decision)
  if (!chosen.length) {
    error.value = '请先选择候选的接受或拒绝决定。'
    return
  }
  if (
    chosen.some(
      (item) =>
        item.decision === 'ACCEPT' &&
        (typeof item.payload.name !== 'string' || !item.payload.name.trim()),
    )
  ) {
    error.value = '接受候选必须填写名称。'
    return
  }
  emit(
    'decide',
    chosen.map((item) =>
      item.decision === 'ACCEPT'
        ? { id: item.id, decision: 'ACCEPT', payload: { ...item.payload } }
        : { id: item.id, decision: 'REJECT' },
    ),
  )
}
</script>
<template>
  <el-form label-position="top" data-editor="suggestion-review" @submit.prevent="submit">
    <div class="row">
      <el-button :disabled="disabled" @click="bulk('ACCEPT')">将勾选项设为接受</el-button>
      <el-button :disabled="disabled" @click="bulk('REJECT')">将勾选项设为拒绝</el-button>
    </div>
    <p class="muted">
      规则先验分值是抽取规则预设的排序参考，未经统计校准，不表示当前证据的正确概率。请结合下方真实来源定位审阅；同批依赖类需同时或已经接受。
    </p>
    <div class="stack">
      <article v-for="row in rows" :key="row.id" class="panel stack">
        <div class="row spread">
          <el-checkbox
            v-model="row.checked"
            class="suggestion-selection"
            :disabled="disabled || row.status !== 'PENDING'"
          >
            {{ labels[row.kind] }} · {{ row.id }}
          </el-checkbox>
          <span class="pill">
            {{ row.sourceKind }} · {{ row.status }} · 规则先验分值 {{ row.confidence }}
          </span>
        </div>
        <p class="muted">批次 {{ row.batchId }}</p>
        <SourceEvidence :location="row.location" />
        <SuggestionPayloadFields
          v-model="row.payload"
          :kind="row.kind"
          :classes="classes ?? []"
          :suggestions="items"
          :batch-id="row.batchId"
          :suggestion-id="row.id"
          :disabled="disabled || row.status !== 'PENDING'"
        />
        <el-form-item v-if="row.status === 'PENDING'" label="人工决定">
          <el-select v-model="row.decision" :disabled="disabled">
            <el-option value="" label="暂不决定" />
            <el-option value="ACCEPT" label="接受（写入模型）" />
            <el-option value="REJECT" label="拒绝（保留审计）" />
          </el-select>
        </el-form-item>
      </article>
    </div>
    <p v-if="!rows.length" class="muted">暂无候选。先选择真实来源生成预览。</p>
    <p v-if="error || props.error" class="error-text" role="alert">{{ error || props.error }}</p>
    <p v-if="stale" class="readonly-note">当前批次修订已过期，编辑内容保留。请刷新后重新审阅。</p>
    <el-button
      type="primary"
      native-type="submit"
      :disabled="disabled || !rows.some((row) => row.status === 'PENDING')"
    >
      提交人工批量决定
    </el-button>
  </el-form>
</template>
<style scoped>
/* 候选使用完整稳定标识；允许标签换行，避免窄屏复选框的默认 nowrap 撑出卡片。 */
.suggestion-selection {
  max-width: 100%;
  min-width: 0;
  height: auto;
  margin-right: 0;
}
.suggestion-selection :deep(.el-checkbox__label) {
  min-width: 0;
  white-space: normal;
  overflow-wrap: anywhere;
}
</style>
