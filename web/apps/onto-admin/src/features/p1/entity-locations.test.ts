import { describe, expect, it } from 'vitest'
import { entityLocations } from './entityLocations'
describe('冲突关联条目定位', () => {
  it('属性携带所属类，规则与操作定位独立页面，缺失标识不伪造目标', () => {
    const result = entityLocations({
      properties: [{ id: 'p', name: '分值', domainClassId: 'c' }],
      rules: [{ id: 'r', name: '评分' }],
      operations: [{ id: 'o', name: '执行评分' }],
    })
    expect(result.p).toEqual({ id: 'p', name: '分值', path: 'model', classId: 'c' })
    expect(result.r.path).toBe('rules')
    expect(result.o.path).toBe('operations')
    expect(result.missing).toBeUndefined()
  })
})
