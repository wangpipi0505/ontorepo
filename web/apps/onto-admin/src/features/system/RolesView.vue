<script setup lang="ts">
import { onMounted, ref } from 'vue'
import type { PermissionDefinition, SystemRole } from '@onto/api-client'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import { useSession } from '../../stores/session'
import { useResource } from '../../composables/useResource'
import { useMutation, confirmAction } from '../../composables/useMutation'
import RoleEditor from './RoleEditor.vue'
const session = useSession(),
  mutation = useMutation(),
  state = useResource<{ roles: SystemRole[]; permissions: PermissionDefinition[] }>({
    roles: [],
    permissions: [],
  })
const open = ref(false),
  selected = ref<SystemRole | null>(null)
function load() {
  return state.load(async () => {
    const [roles, permissions] = await Promise.all([
      session.api.get<SystemRole[]>('/system/roles'),
      session.has('system.permission.read')
        ? session.api.get<PermissionDefinition[]>('/system/permissions')
        : Promise.resolve([]),
    ])
    return { roles, permissions }
  })
}
function edit(role: SystemRole | null) {
  selected.value = role
  open.value = true
}
async function saved() {
  open.value = false
  await load()
}
async function remove(role: SystemRole) {
  if (role.builtIn || role.memberCount || !session.has('system.role.write')) return
  if (!(await confirmAction(`删除角色“${role.name}”？仅未分配用户的自定义角色允许删除。`))) return
  await mutation.run(async ({ isCurrent }) => {
    await session.api.delete(`/system/roles/${role.id}?expectedRevision=${role.revision}`)
    if (isCurrent()) {
      await session.refreshPermissions()
      await load()
    }
  })
}
onMounted(load)
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>角色与权限</h1>
      <p class="muted">按服务端功能目录组合权限，可为一个用户分配多个角色。</p>
    </div>
    <div class="row">
      <el-button @click="load">刷新</el-button>
      <el-button
        v-if="session.has('system.role.write') && session.has('system.permission.read')"
        type="primary"
        @click="edit(null)"
      >
        新建角色
      </el-button>
    </div>
  </div>
  <p v-if="mutation.error.value" class="error-text" role="alert">{{ mutation.error.value }}</p>
  <p v-if="!session.has('system.permission.read')" class="readonly-note">
    当前账号不能读取权限目录，角色配置只读。
  </p>
  <AsyncState :loading="state.loading.value" :error="state.error.value" @retry="load">
    <section class="panel overflow">
      <el-table :data="state.data.value.roles" row-key="id">
        <el-table-column prop="name" label="角色名称" min-width="150" />
        <el-table-column prop="code" label="角色编码" min-width="170" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">{{ row.status === 'ACTIVE' ? '启用' : '停用' }}</template>
        </el-table-column>
        <el-table-column label="类型" width="90">
          <template #default="{ row }">{{ row.builtIn ? '内置' : '自定义' }}</template>
        </el-table-column>
        <el-table-column prop="memberCount" label="成员数" width="90" />
        <el-table-column label="权限" min-width="160">
          <template #default="{ row }">
            <details>
              <summary>{{ row.permissions.length }} 项权限</summary>
              <p v-for="code in row.permissions" :key="code" class="mono">{{ code }}</p>
            </details>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160">
          <template #default="{ row }">
            <el-button
              v-if="session.has('system.permission.read')"
              link
              type="primary"
              @click="edit(row)"
            >
              {{ row.builtIn || !session.has('system.role.write') ? '查看' : '编辑' }}
            </el-button>
            <el-button
              v-if="!row.builtIn && session.has('system.role.write')"
              link
              type="danger"
              :disabled="!!row.memberCount || mutation.pending.value"
              @click="remove(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>
  </AsyncState>
  <el-dialog
    v-model="open"
    :title="selected ? '角色详情与权限配置' : '新建角色'"
    width="860px"
    destroy-on-close
    :show-close="false"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
  >
    <RoleEditor
      v-if="open"
      :role="selected"
      :definitions="state.data.value.permissions"
      @saved="saved"
      @close="open = false"
    />
  </el-dialog>
</template>
