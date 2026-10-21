import { defineStore } from 'pinia'
import { ref } from 'vue'
import { ApiClient, type User, type LoginResult } from '@onto/api-client'
/** 会话仅活在本标签页内存；刷新页面需要重新登录。 */
export const useSession = defineStore('session', () => {
  const token = ref<string | null>(null)
  const user = ref<User | null>(null)
  const expiredNotice = ref(false)
  const permissions = ref<string[] | null>(null)
  let permissionGeneration = 0
  function has(code: string) {
    return permissions.value?.includes(code) === true
  }
  async function refreshPermissions() {
    const current = token.value,
      ticket = ++permissionGeneration
    if (!current) {
      permissions.value = null
      return
    }
    try {
      const result = await api.get<string[]>('/auth/permissions')
      if (!Array.isArray(result) || result.some((code) => typeof code !== 'string'))
        throw new Error('权限响应无法读取，请重新登录。')
      if (ticket === permissionGeneration && token.value === current) permissions.value = result
    } catch (cause) {
      if (ticket === permissionGeneration && token.value === current) permissions.value = null
      throw cause
    }
  }
  function clear() {
    token.value = null
    user.value = null
    permissions.value = null
    permissionGeneration++
  }
  const api = new ApiClient(
    () => token.value,
    () => {
      // 仅已登录会话的当前令牌 401 提示过期；登录失败不能误报，迟到旧令牌已由客户端隔离。
      if (token.value) expiredNotice.value = true
      clear()
    },
  )
  function consumeExpiredNotice() {
    const value = expiredNotice.value
    expiredNotice.value = false
    return value
  }
  async function login(username: string, password: string) {
    const result = await api.post<LoginResult>('/auth/login', { username, password })
    permissions.value = null
    token.value = result.token
    user.value = result.user
    expiredNotice.value = false
    try {
      await refreshPermissions()
    } catch (cause) {
      clear()
      throw cause
    }
  }
  async function logout() {
    try {
      await api.post('/auth/logout', {})
    } finally {
      expiredNotice.value = false
      clear()
    }
  }
  return {
    token,
    user,
    permissions,
    has,
    refreshPermissions,
    api,
    login,
    logout,
    clear,
    consumeExpiredNotice,
  }
})
