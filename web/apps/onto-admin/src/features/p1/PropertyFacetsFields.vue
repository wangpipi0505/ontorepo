<script setup lang="ts">
/** 分面子组件不创建 form；默认值/枚举采用 JSON 以保留数字、布尔与文本的真实类型。 */
import { computed, reactive, watch } from 'vue'
import type { Codebook, PropertyFacets, RangeType } from '@onto/api-client'
import { parseFacets } from './inputs'
const props = defineProps<{
  facets?: PropertyFacets
  books: Codebook[]
  rangeType: RangeType
  required: boolean
}>()
const form = reactive({
  unitCodebookId: '',
  unitCode: '',
  codebookId: '',
  defaultValue: '',
  enumValues: '',
  minimum: undefined as number | null | undefined,
  maximum: undefined as number | null | undefined,
  primaryKey: false,
})
watch(
  () => props.facets,
  (facets) =>
    Object.assign(form, {
      unitCodebookId: facets?.unitCodebookId || '',
      unitCode: facets?.unitCode || '',
      codebookId: facets?.codebookId || '',
      defaultValue: facets?.defaultValue === undefined ? '' : JSON.stringify(facets.defaultValue),
      enumValues: facets?.enumValues === undefined ? '' : JSON.stringify(facets.enumValues),
      minimum: facets?.minimum,
      maximum: facets?.maximum,
      primaryKey: facets?.primaryKey || false,
    }),
  { immediate: true },
)
const units = computed(() => props.books.filter((book) => book.kind === 'UNIT'))
const unitEntries = computed(
  () => units.value.find((book) => book.id === form.unitCodebookId)?.entries || [],
)
function read(): PropertyFacets {
  const facets: Record<string, unknown> = { primaryKey: form.primaryKey }
  if (form.unitCodebookId) {
    facets.unitCodebookId = form.unitCodebookId
    facets.unitCode = form.unitCode
  }
  if (form.codebookId) facets.codebookId = form.codebookId
  try {
    if (form.defaultValue.trim()) facets.defaultValue = JSON.parse(form.defaultValue)
    if (form.enumValues.trim()) facets.enumValues = JSON.parse(form.enumValues)
  } catch {
    throw new Error('默认值和枚举须为有效 JSON；文本请加双引号。')
  }
  // 真实 el-input-number 清空时发出 null；空值表示移除可选分面，0 仍是有效数值边界。
  if (form.minimum != null) facets.minimum = form.minimum
  if (form.maximum != null) facets.maximum = form.maximum
  return parseFacets(props.rangeType, JSON.stringify(facets), props.required)
}
defineExpose({ read })
</script>
<template>
  <fieldset class="facet-fields">
    <legend>高级分面</legend>
    <div class="form-columns">
      <el-form-item label="单位码表">
        <el-select v-model="form.unitCodebookId" clearable @change="form.unitCode = ''">
          <el-option v-for="book in units" :key="book.id" :value="book.id" :label="book.name" />
        </el-select>
      </el-form-item>
      <el-form-item label="单位编码">
        <el-select v-model="form.unitCode" clearable :disabled="!form.unitCodebookId">
          <el-option
            v-for="entry in unitEntries"
            :key="entry.code"
            :value="entry.code"
            :label="`${entry.code} · ${entry.label}`"
          />
        </el-select>
      </el-form-item>
    </div>
    <el-form-item label="取值码表">
      <el-select v-model="form.codebookId" clearable>
        <el-option
          v-for="book in books.filter((item) => item.kind === 'VALUE')"
          :key="book.id"
          :value="book.id"
          :label="book.name"
        />
      </el-select>
    </el-form-item>
    <el-form-item label="默认值（JSON 标量）">
      <el-input v-model="form.defaultValue" placeholder='例如 3、true 或 "文本"' />
    </el-form-item>
    <el-form-item label="枚举值（JSON 数组）">
      <el-input v-model="form.enumValues" placeholder='例如 [1,2] 或 ["甲","乙"]' />
    </el-form-item>
    <div class="form-columns">
      <el-form-item label="最小数值">
        <el-input-number
          v-model="form.minimum"
          :disabled="!['INTEGER', 'DECIMAL'].includes(rangeType)"
        />
      </el-form-item>
      <el-form-item label="最大数值">
        <el-input-number
          v-model="form.maximum"
          :disabled="!['INTEGER', 'DECIMAL'].includes(rangeType)"
        />
      </el-form-item>
    </div>
    <el-checkbox v-model="form.primaryKey">主键（必须为必填，同对象至多一个）</el-checkbox>
  </fieldset>
</template>
<style scoped>
.facet-fields {
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 16px;
  margin: 0 0 18px;
}
.facet-fields legend {
  color: var(--muted);
  font-size: 13px;
  padding: 0 8px;
}
</style>
