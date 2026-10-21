import type { JsonObject, JsonValue } from '@onto/api-client'
import { parseJson, parseObject } from '../p1/inputs'

/** 用户编辑错误只定位到具体字段，原始文本继续保留供修复或复制。 */
export class FieldError extends Error {
  constructor(
    public readonly field: string,
    message: string,
  ) {
    super(message)
  }
}
export function objectField(text: string, field: string, label: string): JsonObject {
  try {
    return parseObject(text, label)
  } catch (cause) {
    throw new FieldError(field, cause instanceof Error ? cause.message : `${label}格式错误。`)
  }
}
/** 操作输入由服务器 Schema 校验根类型；客户端仅校验 JSON 语法并定位字段。 */
export function jsonField(text: string, field: string, label: string): unknown {
  try {
    return parseJson(text, label)
  } catch {
    throw new FieldError(field, `${label}须为有效的 JSON。`)
  }
}
/** 参数示例只校验外层数组；数组内各个 JSON 根值交由输入 Schema 权威校验。 */
export function jsonArrayField(text: string, field: string, label: string): JsonValue[] {
  let value: unknown
  try {
    value = parseJson(text, label)
  } catch {
    throw new FieldError(field, `${label}须为有效的 JSON 数组。`)
  }
  if (!Array.isArray(value)) throw new FieldError(field, `${label}须为 JSON 数组。`)
  return value as JsonValue[]
}
export function objectArrayField(text: string, field: string, label: string): JsonObject[] {
  let value: unknown
  try {
    value = JSON.parse(text)
  } catch {
    throw new FieldError(field, `${label}须为有效的 JSON 数组。`)
  }
  if (
    !Array.isArray(value) ||
    value.some((item) => !item || typeof item !== 'object' || Array.isArray(item))
  )
    throw new FieldError(field, `${label}须为 JSON 数组，每项为对象。`)
  return value as JsonObject[]
}
export function lines(text: string) {
  return [
    ...new Set(
      text
        .split(/\r?\n/)
        .map((item) => item.trim())
        .filter(Boolean),
    ),
  ]
}
