import { describe, expect, it } from 'vitest'
import { parseFacets, parseObject, canWaive, parseEntries } from './inputs'

describe('P1 编辑边界', () => {
  it('分面保持标量真实类型，不把文本自动转成数字', () => {
    expect(
      parseFacets('INTEGER', '{"defaultValue":3,"minimum":0,"enumValues":[3,4]}', true),
    ).toEqual({ defaultValue: 3, minimum: 0, enumValues: [3, 4] })
    expect(() => parseFacets('INTEGER', '{"defaultValue":"3"}', true)).toThrow('类型')
    expect(() => parseFacets('BOOLEAN', '{"enumValues":[true,"false"]}', false)).toThrow('类型')
  })
  it('拒绝倒置范围、无效默认值与非必填主键', () => {
    expect(() => parseFacets('DECIMAL', '{"minimum":5,"maximum":1}', false)).toThrow('范围')
    expect(() => parseFacets('INTEGER', '{"defaultValue":8,"enumValues":[1,2]}', true)).toThrow(
      '枚举',
    )
    expect(() => parseFacets('STRING', '{"primaryKey":true}', false)).toThrow('必填')
  })
  it('JSON输入拒绝数组、null和非法文本，且不伪造空对象成功', () => {
    for (const text of ['[]', 'null', '{']) expect(() => parseObject(text, '输入')).toThrow('输入')
    expect(parseObject('{"count":0}', '输入')).toEqual({ count: 0 })
  })
  it('码表条目拒绝空编码与重复编码', () => {
    expect(parseEntries('kg=千克\nm=米')).toEqual([
      { code: 'kg', label: '千克' },
      { code: 'm', label: '米' },
    ])
    expect(() => parseEntries('kg=千克\nkg=公斤')).toThrow('重复')
    expect(() => parseEntries('=空')).toThrow('编码')
  })
  it('只有OWNER且真实超范围未检查时显示豁免，禁止错误和超时', () => {
    const check = {
      kind: 'CONSISTENCY',
      conclusion: 'NOT_CHECKED',
      reason: 'SUBSET_EXCEEDED',
      message: '',
      details: {},
    }
    expect(canWaive('OWNER', [check])).toBe(true)
    expect(canWaive('EDITOR', [check])).toBe(false)
    expect(canWaive('OWNER', [{ ...check, reason: 'TIMEOUT' }])).toBe(false)
    expect(canWaive('OWNER', [{ ...check, conclusion: 'INCONSISTENT' }])).toBe(false)
  })
})
