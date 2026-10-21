<script setup lang="ts">
/** 主体检查与行标签过滤可并存；多表绑定显式列出每张 JOIN 表，不猜测同名列。 */
import { watch } from 'vue'
import type { GuardFilterBinding, GuardType } from '@onto/api-client'
export interface GuardRow {
  name: string
  type: GuardType
  kind: 'NONE' | 'PRINCIPAL_ATTRIBUTE' | 'FILTER' | 'PRINCIPAL_AND_FILTER'
  attribute: string
  table: string
  column: string
  operator: GuardFilterBinding['operator']
  filterMode: 'SINGLE' | 'MULTIPLE'
  bindings: GuardFilterBinding[]
  conflictingFilterForms: boolean
}
const row = defineModel<GuardRow>({ required: true })
defineProps<{ error?: string }>()
defineEmits<{ remove: [] }>()
watch(
  () => row.value.filterMode,
  (mode) => {
    if (mode === 'MULTIPLE' && !row.value.bindings.length)
      row.value.bindings.push({
        table: row.value.table,
        column: row.value.column,
        operator: row.value.operator,
      })
  },
)
function addBinding() {
  if (row.value.bindings.length < 16)
    row.value.bindings.push({ table: '', column: '', operator: 'IN' })
}
function chooseMode() {
  row.value.conflictingFilterForms = false
}
</script>
<template>
  <article class="guard-entry">
    <div class="form-columns">
      <el-form-item label="守卫名称" data-field="guard-name" :error="error">
        <el-input v-model="row.name" placeholder="例如 region" />
      </el-form-item>
      <el-form-item label="守卫类型">
        <el-select v-model="row.type">
          <el-option
            v-for="type in ['STRING', 'INTEGER', 'DECIMAL', 'BOOLEAN']"
            :key="type"
            :value="type"
            :label="type"
          />
        </el-select>
      </el-form-item>
    </div>
    <el-form-item label="绑定方式" data-field="binding-kind">
      <el-select v-model="row.kind">
        <el-option value="NONE" label="未绑定（仅保存草稿）" />
        <el-option value="PRINCIPAL_ATTRIBUTE" label="主体属性检查" />
        <el-option value="FILTER" label="数据源列过滤" />
        <el-option value="PRINCIPAL_AND_FILTER" label="主体属性检查 + 数据源列过滤" />
      </el-select>
    </el-form-item>
    <el-form-item
      v-if="row.kind === 'PRINCIPAL_ATTRIBUTE' || row.kind === 'PRINCIPAL_AND_FILTER'"
      label="可信主体属性名称"
      data-field="principal-attribute"
    >
      <el-input v-model="row.attribute" placeholder="由空间授权维护，例如 region" />
    </el-form-item>
    <template v-if="row.kind === 'FILTER' || row.kind === 'PRINCIPAL_AND_FILTER'">
      <el-form-item label="源过滤形状" data-field="filter-mode">
        <el-select v-model="row.filterMode" @change="chooseMode">
          <el-option value="SINGLE" label="单表绑定（保留旧单项协议）" />
          <el-option value="MULTIPLE" label="多表绑定（逐表授权）" />
        </el-select>
      </el-form-item>
      <div v-if="row.filterMode === 'SINGLE'" class="stack">
        <div class="form-columns">
          <el-form-item label="源资源" data-field="filter-table">
            <el-input v-model="row.table" />
          </el-form-item>
          <el-form-item label="过滤列" data-field="filter-column">
            <el-input v-model="row.column" />
          </el-form-item>
        </div>
        <el-form-item label="过滤运算" data-field="filter-operator">
          <el-select v-model="row.operator">
            <el-option value="IN" label="IN" />
            <el-option value="EQ" label="EQ" />
          </el-select>
        </el-form-item>
      </div>
      <template v-else>
        <div
          v-for="(binding, index) in row.bindings"
          :key="index"
          data-binding-row
          class="source-binding"
        >
          <h3>源表绑定 {{ index + 1 }}</h3>
          <div class="form-columns">
            <el-form-item label="源资源" data-field="filter-table">
              <el-input v-model="binding.table" placeholder="表名、受管文件名、接口路径或对象键" />
            </el-form-item>
            <el-form-item label="过滤列" data-field="filter-column">
              <el-input v-model="binding.column" placeholder="例如 region" />
            </el-form-item>
          </div>
          <el-form-item label="过滤运算" data-field="filter-operator">
            <el-select v-model="binding.operator">
              <el-option value="IN" label="IN" />
              <el-option value="EQ" label="EQ" />
            </el-select>
          </el-form-item>
          <el-button text type="danger" @click="row.bindings.splice(index, 1)">
            移除此源表绑定
          </el-button>
        </div>
        <el-button :disabled="row.bindings.length >= 16" @click="addBinding">
          添加源表绑定
        </el-button>
      </template>
      <p v-if="row.conflictingFilterForms" class="error-text">
        读取到互斥的两种源过滤配置，请明确选择单表或多表形状后保存。
      </p>
      <p class="muted">
        同一守卫最多绑定 16 个不同资源。源资源可为 schema.table、受管 UUID.csv、HTTP /items 或
        Parquet 对象键；原文由服务端与冻结元数据及授权范围精确核验，不能作为 SQL
        片段执行。列仅填单列名，JOIN 每张表都须显式绑定同一授权属性。纯模型查询不能应用源过滤。
      </p>
    </template>
    <p v-if="row.kind === 'NONE'" class="readonly-note">
      未绑定守卫不能通过契约生成与执行。可保存草稿，补齐绑定后再验证。
    </p>
    <p v-if="error" class="error-text" role="alert">{{ error }}</p>
    <el-button type="danger" text @click="$emit('remove')">移除此守卫</el-button>
  </article>
</template>
<style scoped>
.guard-entry {
  padding: 18px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  min-width: 0;
  margin-bottom: 16px;
}
.source-binding {
  padding: 14px;
  margin-bottom: 12px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
}
</style>
