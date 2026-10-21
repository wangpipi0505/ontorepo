// 独立基数反例，只读本工程onto_sources；所有管理写入均属于新建审查空间。
import { readFile,writeFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
const env=JSON.parse((await readFile(new URL('../../../.runtime/local-env.json',import.meta.url),'utf8')).replace(/^\uFEFF/,''));
let token,space; const evidence={timestamp:new Date().toISOString(),checks:[]};
async function data(path,method='GET',body){const r=await fetch('http://127.0.0.1:18182/onto'+path,{method,headers:{...(token?{Authorization:`Bearer ${token}`} : {}),...(body?{'Content-Type':'application/json'}:{})},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(30000)});const e=await r.json();assert.equal(r.status,200,`${path}: ${e.code}`);return e.data;}
async function write(path,b,method='POST'){space=await data(path,method,{spaceId:space.id,expectedDraftRev:space.draftRev,...b});}
token=(await data('/auth/login','POST',{username:env.ONTO_BOOTSTRAP_USERNAME,password:env.ONTO_BOOTSTRAP_PASSWORD})).token;
const code=`card-review-${Date.now().toString(36)}`;
space=await data('/spaces','POST',{code,name:'独立关系基数反例',namespaceUri:`https://example.org/${code}/`,domain:'审查'}); evidence.spaceId=space.id;
for(const name of ['算法','任务'])await write('/classes',{name,parentId:null,description:'审查',deprecated:false});
const classes=await data(`/classes?spaceId=${space.id}`);const a=classes.find(x=>x.name==='算法'),t=classes.find(x=>x.name==='任务');
for(const [name,c]of[['标准码',a],['任务码',t]])await write('/properties',{name,domainClassId:c.id,rangeType:'STRING',required:true,description:'审查'});
const ps=await data(`/spaces/${space.id}/properties`); const ap=ps.find(x=>x.domainClassId===a.id),tp=ps.find(x=>x.domainClassId===t.id);
await write('/relations',{name:'算法对应唯一任务',fromClassId:a.id,toClassId:t.id,cardinality:'ONE_TO_ONE',transitive:false,symmetric:false,inverseName:null,description:'故意声明与样例不符的基数'});
const rel=(await data(`/relations?spaceId=${space.id}`))[0];
await write('/datasources',{code:'review_pg',name:'只读公开样例',kind:'RELATIONAL',connection:{dialect:'postgresql',url:`jdbc:postgresql://127.0.0.1:${env.ONTO_PG_PORT}/onto_sources`,user:env.ONTO_DB_USER,password:env.ONTO_DB_PASSWORD},accessScope:['onto_sample.tasks','onto_sample.algorithms']});
const src=(await data(`/datasources?spaceId=${space.id}`))[0];const sync=await data(`/datasources/${src.id}/sync`,'POST',{spaceId:space.id,expectedDraftRev:space.draftRev});space=sync.space;
for(const [resource,c,key]of[['onto_sample.algorithms',a,'algorithm:${std_code}'],['onto_sample.tasks',t,'task:${task_id}']])await write('/mappings/objects',{datasourceId:src.id,sourceResource:resource,targetClassId:c.id,objectKeyExpr:key,metaSnapshotId:sync.snapshot.id,filter:null});
let maps=await data(`/mappings?spaceId=${space.id}`);const am=maps.objects.find(x=>x.targetClassId===a.id),tm=maps.objects.find(x=>x.targetClassId===t.id);
for(const [m,p,f]of[[am,ap,'std_code'],[tm,tp,'task_id']])await write('/mappings/properties',{objectMappingId:m.id,targetPropertyId:p.id,sourceField:f,required:true,transform:{kind:'IDENTITY'}});
await write('/mappings/relations',{datasourceId:src.id,relationId:rel.id,fromObjectMappingId:am.id,toObjectMappingId:tm.id,fromKeyExpr:am.objectKeyExpr,toKeyExpr:tm.objectKeyExpr,join:{fromField:'std_code',toField:'algo_code'},cardinalityCheck:true,metaSnapshotId:sync.snapshot.id});
maps=await data(`/mappings?spaceId=${space.id}`);
await write(`/spaces/${space.id}/guards`,{guardCfg:{region:{type:'STRING',filter_bindings:['onto_sample.algorithms','onto_sample.tasks'].map(table=>({table,column:'region',operator:'IN'}))}}},'PUT');
const opBody={code:'cardinality_probe',name:'基数审查',queryTemplate:'SELECT ?s WHERE { ?s ?p ?o }',maxRows:1,timeoutMs:5000};
await write('/operations',opBody);const op=(await data(`/operations?spaceId=${space.id}`))[0];
await write(`/operations/${op.id}/data-query`,{dataQuery:{mode:'RELATION',objectMappingId:am.id,relationMappingId:maps.relations[0].id,propertyIds:[ap.id,tp.id],filters:[]}},'PUT');
await write(`/operations/${op.id}/assistant`,{inputSchema:{type:'object',properties:{},additionalProperties:false},outputSchema:{type:'object',required:['rows'],properties:{rows:{type:'array',items:{type:'object'}}}},assistantTool:{name:'基数审查',description:'独立基数检查',guard_attrs:['region'],info_attrs:[],param_examples:[{}],exec:{readonly:true,side_effect:false,idempotent:true,cancellable:true},risk_level:'READ_ONLY',error_semantics:[{match:'HTTP 403',semantic:'NO_PERMISSION'}],return_spec:{modelSummary:{mode:'TEMPLATE',template:'基数审查'},structuredPath:'$.data',fileRefPaths:[],cardTemplate:'基数审查'}}},'PUT');
for(const maxRows of [1,20]){
  if(maxRows!==1)await write(`/operations/${op.id}`,{...opBody,maxRows},'PUT');
  const c=await data(`/spaces/${space.id}/candidates`,'POST',{expectedDraftRev:space.draftRev});
  const x=await data(`/candidates/${c.id}/trial`,'POST',{operationId:op.id,input:{}});
  evidence.checks.push({maxRows,candidateId:c.id,status:x.status,errorCode:x.errorCode,rowCount:x.rows.length,rows:x.rows});
}
assert.equal(evidence.checks[0].status,'SUCCEEDED');assert.equal(evidence.checks[1].errorCode,'CARDINALITY_VIOLATION');
// 关系to端属性的影响定位和阻删必须覆盖当前关系操作。
const targetPropertyMapping=maps.properties.find(x=>x.objectMappingId===tm.id);
const impact=await data(`/mappings/impact?spaceId=${space.id}&kind=properties&id=${targetPropertyMapping.id}`);
evidence.toPropertyDependency={propertyMappingId:targetPropertyMapping.id,operationId:op.id,impact};
const beforeDelete=await data(`/operations/${op.id}/data-query?spaceId=${space.id}`);
assert.ok(beforeDelete.dataQuery.propertyIds.includes(tp.id));
space=await data(`/mappings/properties/${targetPropertyMapping.id}?spaceId=${space.id}&expectedDraftRev=${space.draftRev}`,'DELETE');
evidence.toPropertyDependency.deleteAccepted=true;
evidence.toPropertyDependency.afterValidation=await data(`/mappings/validate?spaceId=${space.id}`);
assert.deepEqual(impact.operations,[]);
assert.ok(evidence.toPropertyDependency.afterValidation.affectedOperations.some(x=>x.id===op.id));
await writeFile(new URL('c5-cardinality-probe.json',import.meta.url),JSON.stringify(evidence,null,2)+'\n');
await data('/auth/logout','POST');console.log(JSON.stringify(evidence,null,2));
