<script setup lang="ts">
/** OWNER 专用配置表单：读取 DTO 不含凭据；null 明确保留已有连接。 */
import { computed, reactive, ref, watch } from 'vue'
import type { Datasource, ManagedFile, SourceKind, SourceWrite } from '@onto/api-client'
import { connectionFields, dialects, lines, objectJson, sourceLabels } from './sourceFields'
const props = defineProps<{
  value: Datasource | null
  files?: ManagedFile[]
  canWrite: boolean
  pending: boolean
  stale: boolean
  error?: string
}>()
const emit = defineEmits<{ save: [value: SourceWrite]; cancel: [] }>()
const fields = reactive({
  code: '',
  name: '',
  kind: 'RELATIONAL' as SourceKind,
  description: '',
  tenant: '',
  scope: '',
  dialect: 'postgresql',
  endpoints: '',
  headers: '',
  method: 'GET' as 'GET' | 'POST',
  body: '',
  pageSize: '100',
  maxPages: '1',
})
const connection = reactive<Record<string, string>>({}),
  replace = ref(false),
  localError = ref('')
watch(
  () => props.value,
  (value) => {
    Object.assign(fields, {
      code: value?.code ?? '',
      name: value?.name ?? '',
      kind: value?.kind ?? 'RELATIONAL',
      description: value?.description ?? '',
      tenant: value?.tenant ?? '',
      scope: value?.accessScope.join('\n') ?? '',
      dialect: value?.dialect || 'postgresql',
      endpoints: '',
      headers: '',
      method: 'GET',
      body: '',
      pageSize: '100',
      maxPages: '1',
    })
    for (const key of Object.keys(connection)) delete connection[key]
    replace.value = !value
    localError.value = ''
  },
  { immediate: true },
)
watch(
  () => fields.kind,
  (kind) => {
    fields.dialect = dialects[kind]?.[0] ?? ''
  },
)
watch(
  () => connection.fileId,
  (id) => {
    const file = props.files?.find((item) => item.id === id)
    if (file) {
      fields.scope = file.resource
      fields.dialect = /\.xlsx$/i.test(file.name)
        ? 'excel'
        : /\.json$/i.test(file.name)
          ? 'json'
          : 'csv'
    }
  },
)
const disabled = computed(() => !props.canWrite || props.pending || props.stale)
function save() {
  localError.value = ''
  if (disabled.value) return
  try {
    if (!fields.code.trim() || !fields.name.trim() || !lines(fields.scope).length)
      throw new Error('编码、名称与访问范围必填。')
    let config: Record<string, unknown> | null = null
    if (replace.value) {
      config = { dialect: fields.dialect }
      for (const field of connectionFields[fields.kind]) {
        // 凭据是精确字节语义，密码首尾空格不能被表单擅自规范化。
        const raw = connection[field.key] ?? ''
        const value = field.secret ? raw : raw.trim()
        if (field.required && !value.trim()) throw new Error(`${field.label}必填。`)
        if (value)
          config[field.key] =
            field.key === 'contactPoints'
              ? lines(value)
              : field.key === 'port' || (fields.kind === 'KEY_VALUE' && field.key === 'database')
                ? Number(value)
                : value
      }
      if (fields.kind === 'FILE') {
        const file = props.files?.find((item) => item.id === connection.fileId)
        const scope = lines(fields.scope)
        if (!file || scope.length !== 1 || scope[0] !== file.resource)
          throw new Error('受管文件访问范围必须精确匹配所选文件的服务端资源名。')
      }
      if (fields.kind === 'HTTP') {
        config.endpoints = lines(fields.endpoints)
        const headers = objectJson(fields.headers, '请求头')
        if (headers) config.headers = headers
        const pageSize = Number(fields.pageSize),
          maxPages = Number(fields.maxPages)
        if (
          !Number.isInteger(pageSize) ||
          pageSize < 1 ||
          pageSize > 1000 ||
          !Number.isInteger(maxPages) ||
          maxPages < 1 ||
          maxPages > 100
        )
          throw new Error('分页配置必须是整数：每页条数 1–1000，最大页数 1–100。')
        config.method = fields.method
        config.pageSize = pageSize
        config.maxPages = maxPages
        if (fields.method === 'POST') {
          const body = objectJson(fields.body, 'POST 请求体')
          if (!body) throw new Error('POST 请求体必须是有效 JSON 对象。')
          config.body = body
        }
      }
    }
    emit('save', {
      code: fields.code.trim(),
      name: fields.name.trim(),
      kind: fields.kind,
      connection: config,
      accessScope: lines(fields.scope),
      description: fields.description.trim(),
      tenant: fields.tenant.trim(),
    })
  } catch (cause) {
    localError.value = cause instanceof Error ? cause.message : '请检查源配置。'
  }
}
</script>
<template>
  <el-form label-position="top" data-editor="source" @submit.prevent="save">
    <div class="form-columns">
      <el-form-item label="源编码">
        <el-input v-model="fields.code" :disabled="disabled" />
      </el-form-item>
      <el-form-item label="源名称">
        <el-input v-model="fields.name" :disabled="disabled" />
      </el-form-item>
    </div>
    <el-form-item label="源类别">
      <el-select v-model="fields.kind" :disabled="disabled || !!value">
        <el-option v-for="(label, kind) in sourceLabels" :key="kind" :label="label" :value="kind" />
      </el-select>
    </el-form-item>
    <div class="form-columns">
      <el-form-item label="租户/业务归属（可选）">
        <el-input v-model="fields.tenant" :disabled="disabled" placeholder="例如 生产/租户A" />
      </el-form-item>
      <el-form-item label="数据源说明（可选）">
        <el-input v-model="fields.description" :disabled="disabled" maxlength="4000" />
      </el-form-item>
    </div>
    <el-form-item label="访问范围（资源名，每行一个）">
      <el-input v-model="fields.scope" type="textarea" :disabled="disabled" :rows="3" />
    </el-form-item>
    <el-checkbox v-if="value" v-model="replace" :disabled="disabled">
      明确修改连接配置（整体替换，需重新输入凭据）
    </el-checkbox>
    <p class="muted">
      旧凭据不会回显。未勾选修改时保留服务端加密连接。访问范围同时约束元数据与实际查询。
    </p>
    <template v-if="replace">
      <el-form-item label="协议 / 方言">
        <el-select v-model="fields.dialect" :disabled="disabled">
          <el-option
            v-for="item in dialects[fields.kind]"
            :key="item"
            :value="item"
            :label="item"
          />
        </el-select>
      </el-form-item>
      <el-form-item
        v-for="item in connectionFields[fields.kind]"
        :key="item.key"
        :label="item.label"
      >
        <el-select v-if="item.key === 'fileId'" v-model="connection.fileId" :disabled="disabled">
          <el-option
            v-for="file in files?.filter((file) => /\.(csv|json|xlsx)$/i.test(file.name))"
            :key="file.id"
            :value="file.id"
            :label="`${file.name} (${file.resource})`"
          />
        </el-select>
        <el-input
          v-else
          v-model="connection[item.key]"
          :type="item.secret ? 'password' : 'text'"
          :show-password="item.secret"
          autocomplete="new-password"
          :disabled="disabled"
        />
      </el-form-item>
      <p v-if="fields.kind === 'FILE'" class="muted">
        先上传受管 CSV / JSON / XLSX，再复制返回的文件 ID；不能输入本地绝对路径。PDF / DOCX / TXT
        仅用于建模抽取。
      </p>
      <template v-if="fields.kind === 'HTTP'">
        <el-form-item label="请求方法">
          <el-select v-model="fields.method" :disabled="disabled">
            <el-option value="GET" label="GET" />
            <el-option value="POST" label="POST · 固定 JSON 模板" />
          </el-select>
        </el-form-item>
        <el-form-item
          v-if="fields.method === 'POST'"
          label="固定 POST 请求体（JSON 对象，可能含敏感值）"
        >
          <el-input
            v-model="fields.body"
            type="password"
            show-password
            autocomplete="new-password"
            :disabled="disabled"
          />
        </el-form-item>
        <div class="form-columns">
          <el-form-item label="每页条数（1–1000）">
            <el-input
              v-model="fields.pageSize"
              type="number"
              min="1"
              max="1000"
              step="1"
              :disabled="disabled"
            />
          </el-form-item>
          <el-form-item label="最大页数（1–100）">
            <el-input
              v-model="fields.maxPages"
              type="number"
              min="1"
              max="100"
              step="1"
              :disabled="disabled"
            />
          </el-form-item>
        </div>
        <p class="muted">
          仅允许 GET 或固定 JSON 对象 POST；分页参数名为空时只读一页。POST
          页码由服务端绑定，服务端仍校验每页与总页数上限。
        </p>
        <el-form-item label="允许接口路径（每行一个）">
          <el-input v-model="fields.endpoints" type="textarea" :disabled="disabled" />
        </el-form-item>
        <el-form-item label="可选请求头（JSON 对象，可能含凭据）">
          <el-input
            v-model="fields.headers"
            type="password"
            show-password
            :disabled="disabled"
            autocomplete="new-password"
          />
        </el-form-item>
      </template>
    </template>
    <p v-if="localError || error" class="error-text" role="alert">{{ localError || error }}</p>
    <p v-if="stale" class="readonly-note">此表单修订已过期。输入仍保留，请关闭并刷新后重新编辑。</p>
    <div class="row">
      <el-button type="primary" native-type="submit" :disabled="disabled">保存数据源</el-button>
      <el-button :disabled="pending" @click="emit('cancel')">关闭表单</el-button>
    </div>
  </el-form>
</template>
