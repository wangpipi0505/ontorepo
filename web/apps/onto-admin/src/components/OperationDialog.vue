<script setup lang="ts">
/** 维护受限模型查询与资源上限；模板是可编辑起点，后端负责语法、授权及查询边界校验。 */
import { computed, reactive, watch } from 'vue'
import type { Operation, OperationKind, Space } from '@onto/api-client'
import { useReferences } from '../features/p1/useReferences'
import { useDraftRevision } from '../composables/useDraftRevision'
import { useSession } from '../stores/session'
import { useWorkspace } from '../stores/workspace'
import { useMutation, notifySaved } from '../composables/useMutation'
import { queryTemplates } from '../composables/queryTemplates'
const props = defineProps<{ item: Operation | null }>()
const open = defineModel<boolean>({ required: true }),
  emit = defineEmits<{ saved: [] }>()
const workspace = useWorkspace(),
  api = useSession().api,
  { pending, error, run } = useMutation()
const draft = useDraftRevision()
const references = useReferences(['rules', 'strategies'])
const form = reactive({
  code: '',
  name: '',
  description: '',
  precondition: '',
  kind: 'OBJECT_QUERY' as OperationKind,
  queryTemplate: '',
  maxRows: 100,
  timeoutMs: 3000,
  strategyRef: '',
  ruleIds: [] as string[],
})
const isQuery = computed(() => form.kind === 'OBJECT_QUERY' || form.kind === 'RELATION_QUERY')
watch(open, (value) => {
  if (value) {
    draft.start(workspace.space?.draftRev || 0)
    Object.assign(form, {
      code: props.item?.code || '',
      name: props.item?.name || '',
      description: props.item?.description || '',
      precondition: props.item?.precondition || '',
      queryTemplate: props.item?.queryTemplate || queryTemplates[0]!.query,
      maxRows: props.item?.maxRows ?? 100,
      timeoutMs: props.item?.timeoutMs ?? 3000,
      kind: props.item?.kind || 'OBJECT_QUERY',
      strategyRef: props.item?.strategyRef || '',
      ruleIds: [...(props.item?.ruleIds || [])],
    })
    error.value = ''
    void references.load()
  }
})
function applyTemplate(index: number) {
  const value = queryTemplates[index]
  if (value) {
    form.queryTemplate = value.query
    if (!props.item) {
      form.code = value.code
      form.name = value.name
    }
  }
}
async function save() {
  const space = workspace.space
  if (!space || !workspace.canWrite) return
  if (!form.name.trim() || !form.code.trim() || (isQuery.value && !form.queryTemplate.trim())) {
    error.value = '请填写操作编码、名称；查询类操作还须填写 SELECT 语句。'
    return
  }
  if (!/^[A-Za-z][A-Za-z0-9_-]{0,79}$/.test(form.code.trim())) {
    error.value = '操作编码须以字母开头，仅含字母、数字、下划线或短横线，最多 80 字符。'
    return
  }
  if (
    !Number.isInteger(form.maxRows) ||
    form.maxRows < 1 ||
    form.maxRows > 500 ||
    !Number.isInteger(form.timeoutMs) ||
    form.timeoutMs < 100 ||
    form.timeoutMs > 10000
  ) {
    error.value = '请填写有效的整数行数上限和超时时间。'
    return
  }
  const ok = await run(({ isCurrent }) =>
    draft.submit(async (expectedDraftRev) => {
      const body = {
        ...form,
        queryTemplate: isQuery.value ? form.queryTemplate : '',
        strategyRef: form.strategyRef || null,
        spaceId: space.id,
        expectedDraftRev,
      }
      const next = props.item
        ? await api.put<Space>(`/operations/${props.item.id}`, body)
        : await api.post<Space>('/operations', body)
      if (isCurrent()) workspace.accept(next)
    }),
  )
  if (ok) {
    open.value = false
    notifySaved()
    emit('saved')
  }
}
</script>
<template>
  <el-dialog
    v-model="open"
    :title="item ? '编辑模型操作' : '新建模型操作'"
    width="780px"
    :show-close="!pending"
    :close-on-click-modal="!pending"
    :close-on-press-escape="!pending"
  >
    <el-form label-position="top" @submit.prevent="save">
      <div class="form-columns">
        <el-form-item label="操作编码" required>
          <el-input v-model="form.code" maxlength="80" placeholder="例如 list_classes" />
        </el-form-item>
        <el-form-item label="操作名称" required>
          <el-input v-model="form.name" maxlength="200" />
        </el-form-item>
      </div>
      <el-form-item label="业务说明（选填）">
        <el-input
          v-model="form.description"
          type="textarea"
          :rows="2"
          maxlength="4000"
          placeholder="说明该操作解决的业务问题与返回口径"
        />
      </el-form-item>
      <el-form-item label="执行条件（选填）">
        <el-input
          v-model="form.precondition"
          type="textarea"
          :rows="2"
          maxlength="4000"
          placeholder="说明调用前必须满足的条件、所需授权或数据范围"
        />
      </el-form-item>
      <el-form-item label="操作类型" required>
        <el-select v-model="form.kind">
          <el-option value="OBJECT_QUERY" label="对象查询" />
          <el-option value="RELATION_QUERY" label="关系查询" />
          <el-option value="RULE_CHECK" label="规则检查" />
          <el-option value="SCORING" label="评分" />
        </el-select>
      </el-form-item>
      <div v-if="isQuery" class="row spread template-toolbar">
        <span class="muted">可编辑查询模板</span>
        <div class="row">
          <el-button
            v-for="(template, index) in queryTemplates"
            :key="template.code"
            size="small"
            @click="applyTemplate(index)"
          >
            {{ template.label }}
          </el-button>
        </div>
      </div>
      <el-form-item v-if="isQuery" label="SPARQL SELECT" required>
        <el-input
          v-model="form.queryTemplate"
          type="textarea"
          class="query-editor"
          :rows="10"
          maxlength="10000"
          show-word-limit
          spellcheck="false"
        />
      </el-form-item>
      <p v-if="isQuery" class="muted">
        查询范围是冻结候选中的对象与属性。仅支持 SELECT；外部 SERVICE、FROM 及扩展函数会被拒绝。
      </p>
      <p v-else class="muted">规则检查与评分使用候选中冻结的规则和试执行输入。</p>
      <p v-if="form.kind === 'RELATION_QUERY'" class="readonly-note">
        P1 仅查询包内模型关系；外部实例关系查询由后续数据映射阶段提供。
      </p>
      <p v-if="references.error.value" class="error-text" role="alert">
        绑定选项读取失败：{{ references.error.value }}
        <el-button text @click="references.load">重试</el-button>
      </p>
      <el-form-item label="绑定执行策略">
        <el-select v-model="form.strategyRef" clearable filterable>
          <el-option
            v-for="item in references.data.value.strategies || []"
            :key="item.id"
            :value="item.id"
            :label="item.name"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="绑定规则">
        <el-select v-model="form.ruleIds" multiple filterable>
          <el-option
            v-for="item in references.data.value.rules || []"
            :key="item.id"
            :value="item.id"
            :label="item.name"
          />
        </el-select>
      </el-form-item>
      <div class="form-columns">
        <el-form-item label="最多返回行数（1～500）" required>
          <el-input-number v-model="form.maxRows" :min="1" :max="500" />
        </el-form-item>
        <el-form-item label="超时毫秒（100～10000）" required>
          <el-input-number v-model="form.timeoutMs" :min="100" :max="10000" :step="100" />
        </el-form-item>
      </div>
      <p v-if="draft.stale.value" class="error-text" role="alert">
        表单已过期。可复制当前输入，关闭后刷新本页，再重新打开以载入最新内容。
      </p>
      <p v-if="error" class="error-text" role="alert">{{ error }}</p>
      <div class="row">
        <el-button
          native-type="submit"
          type="primary"
          :loading="pending"
          :disabled="
            !workspace.canWrite ||
            draft.stale.value ||
            references.loading.value ||
            !!references.error.value
          "
        >
          保存操作
        </el-button>
        <el-button :disabled="pending" @click="open = false">取消</el-button>
      </div>
    </el-form>
  </el-dialog>
</template>
<style scoped>
.form-columns {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
}
.template-toolbar {
  margin-bottom: 12px;
}
@media (max-width: 550px) {
  .form-columns {
    grid-template-columns: 1fr;
    gap: 0;
  }
}
</style>
