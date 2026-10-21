<script setup lang="ts">
/** 只有 OWNER 维护守卫；草稿允许未绑定，但不把“已保存”描述成可执行。 */
import { computed, onMounted, ref } from 'vue'
import type { Guards, GuardDefinition, Space } from '@onto/api-client'
import { useSession } from '../../stores/session'
import { useDraftDocument } from './useDraftDocument'
import GuardBindingFields, { type GuardRow } from './GuardBindingFields.vue'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
const api = useSession().api
const editor = useDraftDocument((id) => api.get<Guards>(`/spaces/${id}/guards`))
const rows = ref<GuardRow[]>([]),
  errors = ref<Record<number, string>>({})
const owner = computed(() => editor.workspace.space?.role === 'OWNER')
const canWrite = computed(() => owner.value && editor.workspace.canWrite)
async function load() {
  const snapshot = await editor.load()
  if (snapshot) {
    rows.value = Object.entries(snapshot.items.guardCfg).map(([name, value]) => ({
      name,
      type: value.type,
      kind: value.authorization_rule
        ? value.filter_binding || value.filter_bindings
          ? 'PRINCIPAL_AND_FILTER'
          : 'PRINCIPAL_ATTRIBUTE'
        : value.filter_binding || value.filter_bindings
          ? 'FILTER'
          : 'NONE',
      attribute: value.authorization_rule?.attribute || '',
      table: value.filter_binding?.table || '',
      column: value.filter_binding?.column || '',
      operator: value.filter_binding?.operator ?? 'IN',
      filterMode: value.filter_bindings ? 'MULTIPLE' : 'SINGLE',
      bindings: (value.filter_bindings ?? []).map((binding) => ({ ...binding })),
      conflictingFilterForms: !!value.filter_binding && !!value.filter_bindings,
    }))
    errors.value = {}
  }
}
function add() {
  if (canWrite.value)
    rows.value.push({
      name: '',
      type: 'STRING',
      kind: 'NONE',
      attribute: '',
      table: '',
      column: '',
      operator: 'IN',
      filterMode: 'SINGLE',
      bindings: [],
      conflictingFilterForms: false,
    })
}
async function save() {
  if (!canWrite.value || editor.draft.stale.value || editor.mutation.pending.value) return
  errors.value = {}
  const guardCfg: Record<string, GuardDefinition> = {}
  rows.value.forEach((row, index) => {
    const name = row.name.trim()
    if (!name || Object.hasOwn(guardCfg, name)) {
      errors.value[index] = '名称不能为空或重复。'
      return
    }
    const value: GuardDefinition = { type: row.type }
    if (row.kind === 'PRINCIPAL_ATTRIBUTE' || row.kind === 'PRINCIPAL_AND_FILTER') {
      if (!row.attribute.trim()) errors.value[index] = '请填写可信主体属性名称。'
      value.authorization_rule = { kind: 'PRINCIPAL_ATTRIBUTE', attribute: row.attribute.trim() }
    }
    if (row.kind === 'FILTER' || row.kind === 'PRINCIPAL_AND_FILTER') {
      // 保留旧单项形状与运算符；显式选多表后才写数组，不静默丢弃主体检查。
      const bindings =
        row.filterMode === 'SINGLE'
          ? [{ table: row.table, column: row.column, operator: row.operator }]
          : row.bindings
      const normalized = bindings.map((binding) => ({
        ...binding,
        // 历史 table 字段是原始资源标识；不得 trim 文件键或端点，避免更改匹配目标。
        table: binding.table,
        column: binding.column.trim(),
      }))
      if (row.conflictingFilterForms)
        errors.value[index] = '单项与多表绑定互斥，请明确选择一种形状。'
      else if (normalized.length < 1 || normalized.length > 16)
        errors.value[index] = '每项守卫的源表绑定须为 1–16 项。'
      else if (
        normalized.some(
          (binding) =>
            !binding.table.trim() ||
            binding.table.length > 512 ||
            /[\u0000-\u001f\u007f-\u009f]/.test(binding.table),
        )
      )
        errors.value[index] = '源资源必须非空、长度不超过 512 且不含控制字符。'
      else if (normalized.some((binding) => !/^[A-Za-z_][A-Za-z0-9_]*$/.test(binding.column)))
        errors.value[index] = '过滤列必须是单列标识，不能包含 SQL 表达式。'
      else if (normalized.some((binding) => binding.operator !== 'IN' && binding.operator !== 'EQ'))
        errors.value[index] = '过滤运算仅允许 IN 或 EQ。'
      else if (new Set(normalized.map((binding) => binding.table)).size !== normalized.length)
        errors.value[index] = '同一守卫不能重复绑定同一张源表。'
      if (row.filterMode === 'SINGLE') value.filter_binding = normalized[0]
      else value.filter_bindings = normalized
    }
    Object.defineProperty(guardCfg, name, { value, enumerable: true })
  })
  if (Object.keys(errors.value).length) return
  const id = editor.workspace.space?.id
  if (
    id &&
    (await editor.save((expectedDraftRev) =>
      api.put<Space>(`/spaces/${id}/guards`, { expectedDraftRev, guardCfg }),
    ))
  )
    await load()
}
onMounted(load)
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>空间守卫</h1>
      <p class="muted">维护服务必须执行的权限检查与数据过滤绑定。</p>
    </div>
    <el-button :disabled="editor.mutation.pending.value" @click="load">刷新守卫</el-button>
  </div>
  <p v-if="!owner" class="readonly-note">仅空间所有者（OWNER）可维护守卫配置。</p>
  <AsyncState
    :loading="editor.resource.loading.value"
    :error="editor.resource.error.value"
    @retry="load"
  >
    <section v-if="editor.resource.data.value" class="panel">
      <el-form
        label-position="top"
        :disabled="!canWrite || editor.mutation.pending.value"
        @submit.prevent="save"
      >
        <GuardBindingFields
          v-for="(_, index) in rows"
          :key="index"
          v-model="rows[index]!"
          :error="errors[index]"
          @remove="rows.splice(index, 1)"
        />
        <p v-if="!rows.length" class="muted">
          暂无守卫。声明中要求的每一项守卫都必须在这里配置可执行绑定。
        </p>
        <el-button v-if="canWrite" @click="add">添加守卫</el-button>
        <p v-if="editor.draft.stale.value" class="error-text" role="alert">
          表单已过期，输入已保留；请复制后刷新守卫再编辑。
        </p>
        <p v-if="editor.mutation.error.value" class="error-text" role="alert">
          {{ editor.mutation.error.value }}
        </p>
        <div v-if="owner" class="row save-row">
          <el-button
            native-type="submit"
            type="primary"
            :disabled="!canWrite || editor.draft.stale.value"
            :loading="editor.mutation.pending.value"
          >
            保存守卫草稿
          </el-button>
        </div>
      </el-form>
    </section>
  </AsyncState>
</template>
<style scoped>
.save-row {
  margin-top: 20px;
}
</style>
