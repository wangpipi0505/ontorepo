// 独立公开HTTP反例：凭据只在内存读取，证据只保存安全状态和本探针实体ID。
import { readFile, writeFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
const root = new URL('../../../', import.meta.url);
const env = JSON.parse((await readFile(new URL('.runtime/local-env.json', root), 'utf8')).replace(/^\uFEFF/, ''));
const base = 'http://127.0.0.1:18182/onto';
let token, space;
const evidence = { timestamp: new Date().toISOString(), checks: [] };
async function req(path, method='GET', body) {
  const form = body instanceof FormData;
  const r = await fetch(base + path, { method, headers: { ...(token ? {Authorization:`Bearer ${token}`} : {}), ...(body && !form ? {'Content-Type':'application/json'} : {}) }, body: body === undefined ? undefined : form ? body : JSON.stringify(body), signal: AbortSignal.timeout(30000) });
  const value = await r.json();
  return { status: r.status, value };
}
async function data(...args) { const r = await req(...args); assert.equal(r.status,200,`${args[0]}: ${r.value.code}`); return r.value.data; }
async function write(path, body, method='POST') { space = await data(path,method,{spaceId:space.id,expectedDraftRev:space.draftRev,...body}); }
token = (await data('/auth/login','POST',{username:env.ONTO_BOOTSTRAP_USERNAME,password:env.ONTO_BOOTSTRAP_PASSWORD})).token;
const code = `review-${Date.now().toString(36)}`;
space = await data('/spaces','POST',{code,name:'独立后端审查反例',namespaceUri:`https://example.org/${code}/`,domain:'审查'});
evidence.spaceId = space.id;
await write('/classes',{name:'任务',parentId:null,description:'独立反例',deprecated:false});
const cl = (await data(`/classes?spaceId=${space.id}`))[0];
await write('/properties',{name:'标识',domainClassId:cl.id,rangeType:'STRING',required:true,description:'独立反例'});
const p = (await data(`/spaces/${space.id}/properties`))[0];
const form = new FormData();
form.append('file',new Blob(['id,alternate,region\nT1,X1,A\n'],{type:'text/csv'}),'review.csv');
const uploaded = await data(`/data-files?spaceId=${space.id}&expectedDraftRev=${space.draftRev}`,'POST',form);
space = uploaded.space;
await write('/datasources',{code:'review_file',name:'审查CSV',kind:'FILE',connection:{dialect:'csv',fileId:uploaded.file.id},accessScope:[uploaded.file.resource]});
const source = (await data(`/datasources?spaceId=${space.id}`))[0];
const synced = await data(`/datasources/${source.id}/sync`,'POST',{spaceId:space.id,expectedDraftRev:space.draftRev});
space = synced.space;
const objectDef = {datasourceId:source.id,sourceResource:uploaded.file.resource,targetClassId:cl.id,objectKeyExpr:'task:${id}',filter:null,metaSnapshotId:synced.snapshot.id};
await write('/mappings/objects',objectDef);
let maps = await data(`/mappings?spaceId=${space.id}`);
const object = maps.objects[0];
const propDef = {objectMappingId:object.id,sourceField:'id',targetPropertyId:p.id,transform:{kind:'IDENTITY'},required:true};
await write('/mappings/properties',propDef);
maps = await data(`/mappings?spaceId=${space.id}`);
const propMap = maps.properties[0];
const update = await req(`/mappings/properties/${propMap.id}`,'PUT',{spaceId:space.id,expectedDraftRev:space.draftRev,...propDef,sourceField:'alternate'});
evidence.checks.push({name:'合法属性映射字段更新被自身拦截',status:update.status,code:update.value.code,message:update.value.message});
assert.equal(update.status,400);
const duplicate = await req('/mappings/properties','POST',{spaceId:space.id,expectedDraftRev:space.draftRev,...propDef});
evidence.checks.push({name:'同对象同属性同字段重复创建被接受',status:duplicate.status,code:duplicate.value.code});
assert.equal(duplicate.status,200); space=duplicate.value.data;
const duplicateValidation = await data(`/mappings/validate?spaceId=${space.id}`);
evidence.checks.push({name:'存在重复映射时完整性仍valid',valid:duplicateValidation.valid,checks:duplicateValidation.checks});
await write('/operations',{code:'review_query',name:'审查查询',queryTemplate:'SELECT ?s WHERE { ?s ?p ?o }',maxRows:10,timeoutMs:5000});
const op=(await data(`/operations?spaceId=${space.id}`))[0];
const query={mode:'OBJECT',objectMappingId:object.id,propertyIds:[p.id],filters:[]};
const binding=await req(`/operations/${op.id}/data-query`,'PUT',{spaceId:space.id,expectedDraftRev:space.draftRev,dataQuery:query});
evidence.checks.push({name:'重复映射阻断合法操作绑定',status:binding.status,code:binding.value.code,message:binding.value.message});
assert.equal(binding.status,400); assert.equal(binding.value.code,'AMBIGUOUS_PROPERTY_MAPPING');
// 清理本探针创建的重复项，继续验证每个对象映射的必填完整性。
maps=await data(`/mappings?spaceId=${space.id}`);
const duplicateMap=maps.properties.find(x=>x.id!==propMap.id);
space=await data(`/mappings/properties/${duplicateMap.id}?spaceId=${space.id}&expectedDraftRev=${space.draftRev}`,'DELETE');
await write('/mappings/objects',{...objectDef,objectKeyExpr:'other:${id}'});
const partialValidation=await data(`/mappings/validate?spaceId=${space.id}`);
evidence.checks.push({name:'同类第二对象缺必填属性仍完整',valid:partialValidation.valid,checks:partialValidation.checks,coverage:partialValidation.coverage});
// 输出不包含登录凭据和会话令牌。
await writeFile(new URL('c5-review-probe.json',import.meta.url),JSON.stringify(evidence,null,2)+'\n');
await data('/auth/logout','POST');
console.log(JSON.stringify(evidence,null,2));
