export interface ParameterRow {
  name: string
  type: 'string' | 'number' | 'boolean' | 'json'
  value: string
}
/** 业务参数逐字段维护，只有嵌套值才使用单字段 JSON；不混入身份或可信属性。 */
export function parameterObject(rows: ParameterRow[]): Record<string, unknown> {
  const result: Record<string, unknown> = {}
  for (const row of rows) {
    const name = row.name.trim()
    if (
      !name ||
      Object.hasOwn(result, name) ||
      ['__proto__', 'prototype', 'constructor'].includes(name)
    )
      throw new Error('参数名称不能为空、重复或使用保留名称。')
    if (row.type === 'number') {
      if (!row.value.trim() || !Number.isFinite(Number(row.value)))
        throw new Error(`${name} 必须为有效数值。`)
      result[name] = Number(row.value)
    } else if (row.type === 'boolean') {
      if (!['true', 'false'].includes(row.value)) throw new Error(`${name} 请选择 true 或 false。`)
      result[name] = row.value === 'true'
    } else if (row.type === 'json') {
      try {
        result[name] = JSON.parse(row.value) as unknown
      } catch {
        throw new Error(`${name} 必须为有效 JSON 值。`)
      }
    } else result[name] = row.value
  }
  return result
}
export function parameterRows(object: Record<string, unknown> | null): ParameterRow[] {
  return Object.entries(object ?? {}).map(([name, value]) => ({
    name,
    type:
      typeof value === 'string'
        ? 'string'
        : typeof value === 'number'
          ? 'number'
          : typeof value === 'boolean'
            ? 'boolean'
            : 'json',
    value: typeof value === 'string' ? value : JSON.stringify(value),
  }))
}
