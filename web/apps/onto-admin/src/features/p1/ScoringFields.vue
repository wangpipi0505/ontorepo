<script setup lang="ts">
import { reactive, watch } from 'vue'
import { parseObject } from './inputs'
const props = defineProps<{
  value: Record<string, unknown> | null
  properties: { id: string; name: string }[]
}>()
const form = reactive({
  metrics: '[]',
  missingValue: 'REJECT',
  forbiddenProperties: [] as string[],
  explain: true,
})
watch(
  () => props.value,
  (value) =>
    Object.assign(form, {
      metrics: JSON.stringify(value?.metrics || [], null, 2),
      missingValue: value?.missingValue || 'REJECT',
      forbiddenProperties: value?.forbiddenProperties || [],
      explain: value?.explain ?? true,
    }),
  { immediate: true },
)
/** 指标保留结构化 JSON，避免将区间边界、权重转为字符串；服务端负责完整评分校验。 */
function read() {
  const value = parseObject(`{"metrics":${form.metrics}}`, '评分指标')
  if (!Array.isArray(value.metrics) || !value.metrics.length)
    throw new Error('至少填写一个评分指标。')
  return {
    ...value,
    missingValue: form.missingValue,
    tieBreak: 'STABLE_ID',
    forbiddenProperties: form.forbiddenProperties,
    explain: form.explain,
  }
}
defineExpose({ read })
</script>
<template>
  <fieldset class="scoring-fields">
    <legend>评分配置</legend>
    <el-form-item label="指标及分段（JSON 数组）" required>
      <el-input
        v-model="form.metrics"
        type="textarea"
        :rows="8"
        placeholder='[{"propertyId":"属性UUID","weight":1,"bands":[{"min":0,"max":100,"score":1}]}]'
      />
    </el-form-item>
    <p class="muted">权重非负、总和为 1；同指标分段不得重叠。平分时按稳定标识排序。</p>
    <el-form-item label="缺值处理">
      <el-select v-model="form.missingValue">
        <el-option value="REJECT" label="拒绝评分" />
        <el-option value="ZERO" label="按零处理" />
      </el-select>
    </el-form-item>
    <el-form-item label="禁算字段">
      <el-select v-model="form.forbiddenProperties" multiple filterable>
        <el-option v-for="item in properties" :key="item.id" :value="item.id" :label="item.name" />
      </el-select>
    </el-form-item>
    <el-checkbox v-model="form.explain">输出评分解释</el-checkbox>
  </fieldset>
</template>
<style scoped>
.scoring-fields {
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 16px;
  margin: 0 0 18px;
}
.scoring-fields legend {
  font-size: 13px;
  color: var(--muted);
}
</style>
