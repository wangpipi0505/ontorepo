<script setup lang="ts">
/** 知识引用只展示当前空间读取的概念；别名去重后以真实字符串数组提交。 */
import { reactive } from 'vue'
import type { KnowledgeEntry, OntClass } from '@onto/api-client'
import { lines } from './fields'
export interface KnowledgeDraft {
  kind: 'TERM' | 'ENTITY' | 'RELATION'
  term: string
  aliases: string
  definition: string
  sourceRef: string
  conceptRef: string
  subjectRef: string
  predicate: string
  objectRef: string
}
const props = defineProps<{
  concepts: OntClass[]
  canWrite: boolean
  pending: boolean
  stale: boolean
  error: string
}>()
const emit = defineEmits<{ save: [body: Omit<KnowledgeEntry, 'id' | 'spaceId'>]; cancel: [] }>()
const form = defineModel<KnowledgeDraft>({ required: true })
const errors = reactive<Record<string, string>>({})
function save() {
  if (!props.canWrite || props.pending || props.stale) return
  Object.keys(errors).forEach((key) => delete errors[key])
  if (!form.value.term.trim()) errors.term = '请填写名称。'
  if (form.value.kind !== 'RELATION' && !form.value.definition.trim())
    errors.definition = '术语/实体定义不能为空。'
  if (
    form.value.kind === 'RELATION' &&
    (!form.value.subjectRef.trim() || !form.value.predicate.trim() || !form.value.objectRef.trim())
  )
    errors.relation = '事实关系必须填写主语、谓词和宾语。'
  if (!form.value.sourceRef.trim()) errors.source = '请填写可追溯来源。'
  if (form.value.conceptRef && !props.concepts.some((item) => item.id === form.value.conceptRef))
    errors.concept = '请选择当前空间的概念。'
  if (Object.keys(errors).length) return
  emit('save', {
    kind: form.value.kind,
    term: form.value.term.trim(),
    aliases: lines(form.value.aliases),
    definition: form.value.definition,
    sourceRef: form.value.sourceRef.trim(),
    conceptRef: form.value.kind === 'RELATION' ? null : form.value.conceptRef || null,
    subjectRef: form.value.subjectRef.trim() || null,
    predicate: form.value.predicate.trim() || null,
    objectRef: form.value.objectRef.trim() || null,
  })
}
</script>
<template>
  <el-form label-position="top" :disabled="!canWrite || pending" @submit.prevent="save">
    <el-form-item label="知识类型">
      <el-select v-model="form.kind">
        <el-option value="TERM" label="术语" />
        <el-option value="ENTITY" label="实体" />
        <el-option value="RELATION" label="事实关系" />
      </el-select>
    </el-form-item>
    <el-form-item label="名称/术语" :error="errors.term" required>
      <el-input v-model="form.term" />
    </el-form-item>
    <el-form-item label="别名（每行一个）">
      <el-input v-model="form.aliases" type="textarea" :rows="3" />
    </el-form-item>
    <div v-if="form.kind === 'RELATION'" class="form-columns">
      <el-form-item label="主语" :error="errors.relation" required>
        <el-input v-model="form.subjectRef" />
      </el-form-item>
      <el-form-item label="谓词" required>
        <el-input v-model="form.predicate" placeholder="例如 属于" />
      </el-form-item>
      <el-form-item label="宾语" required>
        <el-input v-model="form.objectRef" />
      </el-form-item>
    </div>
    <el-form-item label="定义" :error="errors.definition">
      <el-input v-model="form.definition" type="textarea" :rows="4" />
    </el-form-item>
    <el-form-item label="来源" :error="errors.source" required>
      <el-input v-model="form.sourceRef" />
    </el-form-item>
    <el-form-item label="关联概念" :error="errors.concept">
      <el-select v-model="form.conceptRef" filterable>
        <el-option value="" label="不关联概念" />
        <el-option v-for="item in concepts" :key="item.id" :value="item.id" :label="item.name" />
      </el-select>
    </el-form-item>
    <p v-if="stale" class="error-text" role="alert">
      表单已过期，输入已保留。请复制后关闭并刷新知识列表。
    </p>
    <p v-if="error" class="error-text" role="alert">{{ error }}</p>
    <div class="row">
      <el-button
        native-type="submit"
        type="primary"
        :disabled="!canWrite || stale"
        :loading="pending"
      >
        保存知识
      </el-button>
      <el-button :disabled="pending" @click="emit('cancel')">取消</el-button>
    </div>
  </el-form>
</template>
