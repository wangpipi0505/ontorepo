import type { RangeType, PropertyFacets, Scalar } from '@onto/api-client'
/** 业务输入可采用任意 JSON 根；语法错误只返回固定字段提示，不回显输入片段。 */
export function parseJson(text: string, label: string): unknown {
  try {
    const value: unknown = JSON.parse(text)
    return value
  } catch {
    throw new Error(`${label}须为有效的 JSON。`)
  }
}
/** JSON 编辑器不执行表达式；非法输入保留原文并阻止提交，不以空对象兜底。 */
export function parseObject(text: string, label: string): Record<string, unknown> {
  try {
    const value = parseJson(text, label)
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error()
    return value as Record<string, unknown>
  } catch {
    throw new Error(`${label}须为有效的 JSON 对象。`)
  }
}
/** 保留 JSON 标量类型。服务端仍负责码表同空间、引用、单类唯一主键等权威校验。 */
export function parseFacets(type: RangeType, text: string, required: boolean): PropertyFacets {
  const value = parseObject(text, '高级分面')
  const valid = (item: unknown): item is Scalar => {
    if (type === 'INTEGER') return typeof item === 'number' && Number.isInteger(item)
    if (type === 'DECIMAL') return typeof item === 'number' && Number.isFinite(item)
    if (type === 'BOOLEAN') return typeof item === 'boolean'
    return typeof item === 'string' && (type !== 'DATE' || /^\d{4}-\d{2}-\d{2}$/.test(item))
  }
  if ('defaultValue' in value && !valid(value.defaultValue))
    throw new Error('默认值与属性类型不匹配。')
  if ('enumValues' in value && (!Array.isArray(value.enumValues) || !value.enumValues.every(valid)))
    throw new Error('枚举值与属性类型不匹配。')
  for (const key of ['minimum', 'maximum'])
    if (key in value && (typeof value[key] !== 'number' || !['INTEGER', 'DECIMAL'].includes(type)))
      throw new Error('数值范围仅用于数值类型。')
  if (
    typeof value.minimum === 'number' &&
    typeof value.maximum === 'number' &&
    value.minimum > value.maximum
  )
    throw new Error('最小范围不能大于最大范围。')
  if (value.primaryKey === true && !required) throw new Error('主键必须设为必填。')
  if (
    'defaultValue' in value &&
    Array.isArray(value.enumValues) &&
    !value.enumValues.includes(value.defaultValue)
  )
    throw new Error('默认值必须属于枚举。')
  if (
    typeof value.defaultValue === 'number' &&
    ((typeof value.minimum === 'number' && value.defaultValue < value.minimum) ||
      (typeof value.maximum === 'number' && value.defaultValue > value.maximum))
  )
    throw new Error('默认值超出范围。')
  return value as PropertyFacets
}
export function parseEntries(text: string) {
  const seen = new Set<string>()
  const entries = text
    .split(/\r?\n/)
    .filter((line) => line.trim())
    .map((line) => {
      const index = line.indexOf('=')
      const code = line.slice(0, index).trim(),
        label = line.slice(index + 1).trim()
      if (index <= 0 || !code || !label) throw new Error('每行填写 编码=名称，二者不可为空。')
      if (seen.has(code)) throw new Error(`编码重复：${code}`)
      seen.add(code)
      return { code, label }
    })
  if (!entries.length) throw new Error('至少填写一个码表条目。')
  return entries
}
export function canWaive(
  role: string,
  checks: { kind: string; conclusion: string; reason: string }[],
) {
  return (
    role === 'OWNER' &&
    checks.some(
      (item) =>
        item.kind === 'CONSISTENCY' &&
        item.conclusion === 'NOT_CHECKED' &&
        item.reason === 'SUBSET_EXCEEDED',
    )
  )
}
