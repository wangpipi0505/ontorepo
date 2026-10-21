<script setup lang="ts">
/** 服务端目录决定可选权限；界面不提供任意代码输入，管理权限明确说明授权能力。 */
import { computed } from 'vue'
import type { PermissionDefinition } from '@onto/api-client'
const model = defineModel<string[]>({ required: true })
const props = defineProps<{ definitions: PermissionDefinition[]; disabled: boolean }>()
const groups = computed(() => [...new Set(props.definitions.map((entry) => entry.group))])
</script>
<template>
  <div class="stack">
    <p class="readonly-note">
      用户、角色管理权限可调整其他账号与权限配置，请仅授予可信管理人员。功能权限仍受空间身份和数据范围限制。
    </p>
    <el-checkbox-group v-model="model" :disabled="disabled">
      <fieldset v-for="group in groups" :key="group" class="permission-group">
        <legend>{{ group }}</legend>
        <label
          v-for="permission in definitions.filter((item) => item.group === group)"
          :key="permission.code"
          class="permission-item"
        >
          <el-checkbox :value="permission.code">{{ permission.name }}</el-checkbox>
          <span class="muted">{{ permission.description }}</span>
          <code>{{ permission.code }}</code>
        </label>
      </fieldset>
    </el-checkbox-group>
  </div>
</template>
<style scoped>
.permission-group {
  /* 复选框组会清零字号和行高以消除行内空隙；分组内的标题、说明和权限编码需要恢复文本排版。 */
  font-size: 0.875rem;
  line-height: 1.6;
  border: 1px solid var(--line);
  border-radius: 6px;
  margin: 12px 0;
  padding: 12px;
}
.permission-item {
  display: grid;
  grid-template-columns: minmax(140px, 1fr) 2fr;
  gap: 4px 12px;
  padding: 6px;
}
.permission-item code {
  grid-column: 1 / -1;
  font-size: 11px;
  overflow-wrap: anywhere;
}
@media (max-width: 600px) {
  .permission-item {
    display: flex;
    flex-direction: column;
  }
}
</style>
