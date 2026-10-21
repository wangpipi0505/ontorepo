<script setup lang="ts">
/** 必需守卫和说明属性分开编辑；执行属性保持布尔类型，不从风险等级反向猜测。 */
import type { AssistantTool } from '@onto/api-client'
defineProps<{ errors: Record<string, string> }>()
const guards = defineModel<string>('guards', { required: true })
const info = defineModel<string>('info', { required: true })
const examples = defineModel<string>('examples', { required: true })
const exec = defineModel<NonNullable<AssistantTool['exec']>>('exec', { required: true })
const risk = defineModel<NonNullable<AssistantTool['risk_level']>>('risk', { required: true })
</script>
<template>
  <div class="form-columns">
    <el-form-item label="必需守卫属性" :error="errors.guards">
      <el-input v-model="guards" type="textarea" :rows="3" placeholder="每行一个，例如 region" />
    </el-form-item>
    <el-form-item label="说明属性">
      <el-input v-model="info" type="textarea" :rows="3" placeholder="每行一个，不参与权限检查" />
    </el-form-item>
  </div>
  <el-form-item label="参数示例（JSON 数组）" :error="errors.examples">
    <el-input v-model="examples" type="textarea" :rows="5" />
  </el-form-item>
  <el-form-item label="风险等级">
    <el-select v-model="risk">
      <el-option value="READ_ONLY" label="READ_ONLY · 只读" />
      <el-option value="CONFIRM_REQUIRED" label="CONFIRM_REQUIRED · 需确认" />
    </el-select>
  </el-form-item>
  <el-form-item label="执行属性">
    <div class="row">
      <el-checkbox v-model="exec.readonly">只读</el-checkbox>
      <el-checkbox v-model="exec.side_effect">有副作用</el-checkbox>
      <el-checkbox v-model="exec.idempotent">幂等</el-checkbox>
      <el-checkbox v-model="exec.cancellable">可取消</el-checkbox>
    </div>
  </el-form-item>
</template>
