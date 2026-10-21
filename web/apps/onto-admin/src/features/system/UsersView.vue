<script setup lang="ts">
/** 系统账号维护与空间授权分离；管理员身份本身不会授予空间读写或数据范围。 */
import { onMounted, ref } from 'vue'
import type { SystemRole, SystemUser } from '@onto/api-client'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import { useSession } from '../../stores/session'
import { useResource } from '../../composables/useResource'
import UserEditor from './UserEditor.vue'
import PasswordReset from './PasswordReset.vue'
const session = useSession(),
  state = useResource<{ users: SystemUser[]; roles: SystemRole[] }>({ users: [], roles: [] })
const open = ref(false),
  selected = ref<SystemUser | null>(null),
  resetUser = ref<SystemUser | null>(null)
function load() {
  return state.load(async () => {
    const [users, roles] = await Promise.all([
      session.api.get<SystemUser[]>('/system/users'),
      session.has('system.role.read')
        ? session.api.get<SystemRole[]>('/system/roles')
        : Promise.resolve([]),
    ])
    return { users, roles }
  })
}
function edit(user: SystemUser | null) {
  selected.value = user
  open.value = true
}
async function saved() {
  open.value = false
  resetUser.value = null
  await load()
}
onMounted(load)
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>用户管理</h1>
      <p class="muted">管理本地账号、状态和角色分配。空间授权在各空间中单独维护。</p>
    </div>
    <div class="row">
      <el-button @click="load">刷新</el-button>
      <el-button v-if="session.has('system.user.write')" type="primary" @click="edit(null)">
        新建用户
      </el-button>
    </div>
  </div>
  <AsyncState :loading="state.loading.value" :error="state.error.value" @retry="load">
    <section class="panel overflow">
      <el-table :data="state.data.value.users" row-key="id">
        <el-table-column prop="username" label="账号" min-width="140" />
        <el-table-column prop="displayName" label="显示名称" min-width="160" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'">
              {{ row.status === 'ACTIVE' ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="角色" min-width="200">
          <template #default="{ row }">
            {{ row.roles.map((role: { name: string }) => role.name).join('、') || '无角色' }}
          </template>
        </el-table-column>
        <el-table-column prop="revision" label="修订" width="70" />
        <el-table-column v-if="session.has('system.user.write')" label="操作" width="180">
          <template #default="{ row }">
            <el-button link type="primary" @click="edit(row)">编辑</el-button>
            <el-button link type="danger" @click="resetUser = row">重置密码</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>
  </AsyncState>
  <el-dialog
    v-model="open"
    :title="selected ? '编辑用户' : '新建用户'"
    width="680px"
    destroy-on-close
    :show-close="false"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
  >
    <p v-if="!session.has('system.role.read')" class="readonly-note">
      当前角色不能读取角色目录，已有角色会保留；如需调整角色，请先取得角色读取权限。
    </p>
    <UserEditor
      v-if="open"
      :user="selected"
      :roles="state.data.value.roles"
      @saved="saved"
      @close="open = false"
    />
  </el-dialog>
  <el-dialog
    :model-value="!!resetUser"
    title="重置密码"
    width="600px"
    destroy-on-close
    :show-close="false"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
  >
    <PasswordReset v-if="resetUser" :user="resetUser" @saved="saved" @close="resetUser = null" />
  </el-dialog>
</template>
