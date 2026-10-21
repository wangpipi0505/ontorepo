<script setup lang="ts">
import type { ParameterRow } from './parameters'
const rows = defineModel<ParameterRow[]>({ required: true })
defineProps<{ disabled?: boolean; addLabel?: string }>()
function update(index: number, key: keyof ParameterRow, value: string) {
  rows.value = rows.value.map((row, i) => (i === index ? { ...row, [key]: value } : row))
}
</script>
<template>
  <div class="stack">
    <div v-for="(row, index) in rows" :key="index" class="record stack">
      <div class="form-columns">
        <label>
          字段名称
          <input
            :value="row.name"
            :data-testid="`parameter-name-${index}`"
            :disabled="disabled"
            @input="update(index, 'name', ($event.target as HTMLInputElement).value)"
          />
        </label>
        <label>
          值类型
          <select
            :value="row.type"
            :data-testid="`parameter-type-${index}`"
            :disabled="disabled"
            @change="update(index, 'type', ($event.target as HTMLSelectElement).value)"
          >
            <option value="string">文本</option>
            <option value="number">数值</option>
            <option value="boolean">布尔值</option>
            <option value="json">嵌套对象或数组（JSON）</option>
          </select>
        </label>
      </div>
      <label>
        字段值
        <select
          v-if="row.type === 'boolean'"
          :value="row.value"
          :data-testid="`parameter-value-${index}`"
          :disabled="disabled"
          @change="update(index, 'value', ($event.target as HTMLSelectElement).value)"
        >
          <option value="">请选择</option>
          <option value="true">true</option>
          <option value="false">false</option>
        </select>
        <textarea
          v-else-if="row.type === 'json'"
          :value="row.value"
          rows="3"
          :data-testid="`parameter-value-${index}`"
          :disabled="disabled"
          @input="update(index, 'value', ($event.target as HTMLTextAreaElement).value)"
        />
        <input
          v-else
          :value="row.value"
          :data-testid="`parameter-value-${index}`"
          :disabled="disabled"
          @input="update(index, 'value', ($event.target as HTMLInputElement).value)"
        />
      </label>
      <div>
        <el-button :disabled="disabled" @click="rows = rows.filter((_, i) => i !== index)">
          移除此字段
        </el-button>
      </div>
    </div>
    <div>
      <el-button
        :disabled="disabled"
        @click="rows = [...rows, { name: '', type: 'string', value: '' }]"
      >
        {{ addLabel ?? '添加字段' }}
      </el-button>
    </div>
  </div>
</template>
