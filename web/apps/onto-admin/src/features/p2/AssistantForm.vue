<script setup lang="ts">
/** 声明草稿允许不完整；此表单只校验结构，生成资格由冻结契约接口权威判断。 */
import { reactive, watch } from 'vue'
import type { AssistantDeclaration, AssistantTool } from '@onto/api-client'
import AssistantBehaviorFields from './AssistantBehaviorFields.vue'
import { FieldError, objectField, jsonArrayField, objectArrayField, lines } from './fields'
const props = defineProps<{
  value: AssistantDeclaration
  canWrite: boolean
  pending: boolean
  stale: boolean
  error: string
}>()
const emit = defineEmits<{ save: [value: Omit<AssistantDeclaration, 'operationId'>] }>()
const tool = props.value.assistantTool
const form = reactive({
  name: tool?.name || '',
  description: tool?.description || '',
  input: JSON.stringify(props.value.inputSchema ?? { type: 'object', properties: {} }, null, 2),
  output: JSON.stringify(props.value.outputSchema ?? { type: 'object', properties: {} }, null, 2),
  guards: tool?.guard_attrs?.join('\n') || '',
  info: tool?.info_attrs?.join('\n') || '',
  examples: JSON.stringify(tool?.param_examples ?? [], null, 2),
  exec: {
    ...(tool?.exec ?? { readonly: true, side_effect: false, idempotent: true, cancellable: true }),
  },
  risk: (tool?.risk_level ?? 'READ_ONLY') as NonNullable<AssistantTool['risk_level']>,
  semantics: JSON.stringify(tool?.error_semantics ?? [], null, 2),
  returns: JSON.stringify(tool?.return_spec ?? {}, null, 2),
})
const errors = reactive<Record<string, string>>({})
// 后端字段路径仍是权威原文；映射到表单位置以便修复，不改写成前端的成功提示。
watch(
  () => props.error,
  (message) => {
    const paths: Record<string, string[]> = {
      input: ['inputSchema', 'input_schema'],
      output: ['outputSchema', 'output_schema'],
      examples: ['param_examples'],
      guards: ['guard_attrs'],
      semantics: ['error_semantics'],
      returns: ['return_spec'],
      name: ['assistantTool.name'],
      description: ['assistantTool.description'],
    }
    for (const [field, names] of Object.entries(paths))
      if (message && names.some((name) => message.includes(name))) errors[field] = message
  },
)
function save() {
  if (!props.canWrite || props.pending || props.stale) return
  Object.keys(errors).forEach((key) => delete errors[key])
  try {
    const inputSchema = objectField(form.input, 'input', '输入 Schema')
    const outputSchema = objectField(form.output, 'output', '输出 Schema')
    const examples = jsonArrayField(form.examples, 'examples', '参数示例')
    const semantics = objectArrayField(form.semantics, 'semantics', '错误语义映射').map((item) => {
      if (
        typeof item.match !== 'string' ||
        typeof item.semantic !== 'string' ||
        !['PARAM_ERROR', 'NO_PERMISSION', 'QUOTA', 'RETRYABLE_FAILURE', 'RESULT_UNKNOWN'].includes(
          item.semantic,
        )
      )
        throw new FieldError('semantics', '每项需含 match 字符串与五类 semantic 枚举。')
      return { match: item.match, semantic: item.semantic }
    })
    emit('save', {
      inputSchema,
      outputSchema,
      assistantTool: {
        name: form.name,
        description: form.description,
        guard_attrs: lines(form.guards),
        info_attrs: lines(form.info),
        param_examples: examples,
        exec: { ...form.exec },
        risk_level: form.risk,
        error_semantics: semantics,
        return_spec: objectField(form.returns, 'returns', '返回约定'),
      },
    })
  } catch (cause) {
    if (cause instanceof FieldError) errors[cause.field] = cause.message
  }
}
</script>
<template>
  <el-form
    data-editor="assistant"
    label-position="top"
    :disabled="!canWrite || pending"
    @submit.prevent="save"
  >
    <div class="form-columns">
      <el-form-item label="助手名称" :error="errors.name">
        <el-input v-model="form.name" />
      </el-form-item>
      <el-form-item label="助手说明" :error="errors.description">
        <el-input v-model="form.description" type="textarea" :rows="2" />
      </el-form-item>
    </div>
    <div class="form-columns">
      <el-form-item label="输入 Schema" :error="errors.input">
        <el-input v-model="form.input" type="textarea" :rows="8" />
      </el-form-item>
      <el-form-item label="输出 Schema" :error="errors.output">
        <el-input v-model="form.output" type="textarea" :rows="8" />
      </el-form-item>
    </div>
    <AssistantBehaviorFields
      v-model:guards="form.guards"
      v-model:info="form.info"
      v-model:examples="form.examples"
      v-model:exec="form.exec"
      v-model:risk="form.risk"
      :errors="errors"
    />
    <div class="form-columns">
      <el-form-item label="错误语义映射（JSON 数组）" :error="errors.semantics">
        <el-input v-model="form.semantics" type="textarea" :rows="6" />
      </el-form-item>
      <el-form-item label="返回约定（JSON 对象）" :error="errors.returns">
        <el-input v-model="form.returns" type="textarea" :rows="6" />
      </el-form-item>
    </div>
    <p class="muted">
      参数示例外层为数组，每项可为对象、数组、标量或 null，按输入 Schema
      填写。可保存未配齐的草稿，完整声明仍须在候选契约生成时通过检查。
    </p>
    <p v-if="stale" class="error-text" role="alert">
      此声明已过期，输入已保留。请复制后刷新声明，载入最新内容再编辑。
    </p>
    <p v-if="error" class="error-text" role="alert">{{ error }}</p>
    <el-button
      v-if="canWrite || stale"
      native-type="submit"
      type="primary"
      :disabled="!canWrite || stale"
      :loading="pending"
    >
      保存声明草稿
    </el-button>
  </el-form>
</template>
