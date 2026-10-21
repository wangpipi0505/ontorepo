/**
 * 对齐详设 §8.8 原始 JSON 与实际契约测试夹具，防止复制后的样例悄然漂移。
 * 本脚本仅证明可解析与内容一致；逐项 Schema/守卫/枚举校验由 ContractTest 执行。
 */
import assert from 'node:assert/strict'
import { readFile, writeFile } from 'node:fs/promises'
import { createHash } from 'node:crypto'

const root = new URL('../', import.meta.url)
const design = await readFile(new URL('../../文档/设计/详细设计/领域本体构建/详细设计70-领域本体构建工具（模块2）.md', root), 'utf8')
const start = design.indexOf('### 8.8 ')
const end = design.indexOf('### 8.9 ', start)
assert.ok(start >= 0 && end > start, '详设缺少 §8.8/§8.9 边界，须人工核验章节变更')
const samples = [...design.slice(start, end).matchAll(/```json[ \t]*\r?\n([\s\S]*?)\r?\n```/g)]
assert.equal(samples.length, 4, '§8.8 应含四段完整 JSON 样例')
const checks = []
for (const [index, sample] of samples.entries()) {
  const parsed = JSON.parse(sample[1])
  const fixture = JSON.parse(await readFile(new URL(`onto-contract/src/test/resources/sample-${index + 1}.json`, root), 'utf8'))
  assert.deepEqual(fixture, parsed, `样例${index + 1}与真实测试夹具不一致`)
  checks.push({ sample: index + 1, parse: 'PASSED', fixtureAlignment: 'PASSED',
    sourceSha256: createHash('sha256').update(sample[1]).digest('hex') })
}
// 复审可指定独立证据目录，避免覆盖上一轮已引用的验收记录。
const evidenceDir = new URL(process.env.ONTO_EVIDENCE_DIR ?? 'docs/evidence/continuation/', root)
await writeFile(new URL('design-sample-alignment.json', evidenceDir), JSON.stringify({
  checkedAt: new Date().toISOString(), section: '8.8', checks,
  semanticValidation: 'onto-contract ContractTest.fourSamplesParseAndEveryContractStageIsValidated + importedSampleMapsEveryManifestFieldAndConsumerDefault；见最新 reactor 测试结果',
}, null, 2) + '\n')
console.log('详设 §8.8 四段 JSON 均可解析，且与实际逐项校验夹具内容一致。')
