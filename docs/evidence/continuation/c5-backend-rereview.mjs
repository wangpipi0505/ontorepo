// 新包独立复审；保留原三个反例脚本与旧JSON，不改变其原断言。
import {readFile,writeFile} from 'node:fs/promises';
import {execFileSync} from 'node:child_process';
import assert from 'node:assert/strict';
const env=JSON.parse((await readFile(new URL('../../../.runtime/local-env.json',import.meta.url),'utf8')).replace(/^\uFEFF/,''));
let token,space;const evidence={timestamp:new Date().toISOString(),packageSha:process.env.C5_REVIEW_SHA??null,checks:[]};
async function req(path,method='GET',body){const form=body instanceof FormData;const r=await fetch('http://127.0.0.1:18182/onto'+path,{method,headers:{...(token?{Authorization:`Bearer ${token}`} : {}),...(body&&!form?{'Content-Type':'application/json'}:{})},body:body===undefined?undefined:form?body:JSON.stringify(body),signal:AbortSignal.timeout(30000)});return {status:r.status,value:await r.json()};}
async function data(...args){const r=await req(...args);assert.equal(r.status,200,`${args[0]}: ${r.value.code}`);return r.value.data;}
async function write(path,body,method='POST'){space=await data(path,method,{spaceId:space.id,expectedDraftRev:space.draftRev,...body});}
async function upload(name,bytes){const f=new FormData();f.append('file',new Blob([bytes]),name);return req(`/data-files?spaceId=${space.id}&expectedDraftRev=${space.draftRev}`,'POST',f);}
async function record(name,details={}){evidence.checks.push({name,status:'PASSED',...details});await writeFile(new URL('c5-backend-rereview.json',import.meta.url),JSON.stringify(evidence,null,2)+'\n');}
async function createSpace(name){const code=`rereview-${Date.now().toString(36)}-${Math.random().toString(36).slice(2,6)}`;space=await data('/spaces','POST',{code,name,namespaceUri:`https://example.org/${code}/`,domain:'独立复审'});(evidence.spaceIds??=[]).push(space.id);}
function declaration(){return {inputSchema:{type:'object',properties:{},additionalProperties:false},outputSchema:{type:'object',required:['rows'],properties:{rows:{type:'array',items:{type:'object'}}}},assistantTool:{name:'独立复审',description:'冻结授权查询',guard_attrs:['region'],info_attrs:[],param_examples:[{}],exec:{readonly:true,side_effect:false,idempotent:true,cancellable:true},risk_level:'READ_ONLY',error_semantics:[{match:'HTTP 403',semantic:'NO_PERMISSION'}],return_spec:{modelSummary:{mode:'TEMPLATE',template:'独立复审'},structuredPath:'$.data',fileRefPaths:[],cardTemplate:'独立复审'}}};}
token=(await data('/auth/login','POST',{username:env.ONTO_BOOTSTRAP_USERNAME,password:env.ONTO_BOOTSTRAP_PASSWORD})).token;
await createSpace('属性与完整性复审');
await write('/classes',{name:'任务',parentId:null,description:'复审',deprecated:false});
const cl=(await data(`/classes?spaceId=${space.id}`))[0];
await write('/properties',{name:'标识',domainClassId:cl.id,rangeType:'STRING',required:true,description:'复审'});
const p=(await data(`/spaces/${space.id}/properties`))[0];
const u=await upload('review.csv',Buffer.from('id,alternate,region\nT1,X1,A\n'));assert.equal(u.status,200);space=u.value.data.space;
await write('/datasources',{code:'review_file',name:'独立CSV',kind:'FILE',connection:{dialect:'csv',fileId:u.value.data.file.id},accessScope:[u.value.data.file.resource]});
const s=(await data(`/datasources?spaceId=${space.id}`))[0];const sync=await data(`/datasources/${s.id}/sync`,'POST',{spaceId:space.id,expectedDraftRev:space.draftRev});space=sync.space;
const od={datasourceId:s.id,sourceResource:u.value.data.file.resource,targetClassId:cl.id,objectKeyExpr:'task:${id}',filter:null,metaSnapshotId:sync.snapshot.id};
await write('/mappings/objects',od);let maps=await data(`/mappings?spaceId=${space.id}`);const o=maps.objects[0];
const pd={objectMappingId:o.id,sourceField:'id',targetPropertyId:p.id,transform:{kind:'IDENTITY'},required:true};
await write('/mappings/properties',pd);maps=await data(`/mappings?spaceId=${space.id}`);const pm=maps.properties[0];
await write(`/mappings/properties/${pm.id}`,{...pd,sourceField:'alternate'},'PUT');
const dup=await req('/mappings/properties','POST',{spaceId:space.id,expectedDraftRev:space.draftRev,...pd,sourceField:'alternate'});assert.equal(dup.status,409);assert.equal(dup.value.code,'PROPERTY_MAPPING_DUPLICATE');
assert.equal((await data(`/mappings?spaceId=${space.id}`)).properties.length,1);
await record('C5-R01合法改字段成功且重复创建409不增实体',{duplicateCode:dup.value.code});
await write('/mappings/objects',{...od,objectKeyExpr:'second:${id}'});
const incomplete=await data(`/mappings/validate?spaceId=${space.id}`);assert.equal(incomplete.valid,false);const required=incomplete.checks.find(x=>x.kind==='REQUIRED_PROPERTY');assert.equal(required.passed,false);assert.ok(required.issues.some(x=>x.code==='REQUIRED_PROPERTY_MISSING'));assert.equal(incomplete.coverage.pending,1);
await record('C5-R02逐对象必填缺项准确拒绝',{coverage:incomplete.coverage,issues:required.issues});
await write('/codebooks',{code:'review_value',name:'复审码表',kind:'VALUE',entries:[{code:'X1',label:'标准X'}]});
const cb=(await data(`/codebooks?spaceId=${space.id}`))[0];await write(`/mappings/properties/${pm.id}`,{...pd,sourceField:'alternate',transform:{kind:'CODEBOOK',codebookId:cb.id}},'PUT');
const deniedBook=await req(`/codebooks/${cb.id}?spaceId=${space.id}&expectedDraftRev=${space.draftRev}`,'DELETE');assert.equal(deniedBook.status,409);assert.equal(deniedBook.value.code,'CODEBOOK_IN_USE');
await record('CODEBOOK映射引用阻删',{code:deniedBook.value.code});
// 对非主键模板使用真实受管文件，验证业务LIMIT前的重复和500预算。
let serial=0;
for(const [name,rows,key,expected]of[
 ['duplicate.csv',['T1,SAME,A','T2,SAME,A'],'alternate','OBJECT_KEY_DUPLICATE'],
 ['budget.csv',Array.from({length:500},(_,i)=>`T${i},K${i},A`),'alternate','OBJECT_KEY_CHECK_INCOMPLETE'],
 ['within-budget.csv',Array.from({length:499},(_,i)=>`T${i},K${i},A`),'alternate',null],
]){
 const uploaded=await upload(name,Buffer.from('id,alternate,region\n'+rows.join('\n')+'\n'));assert.equal(uploaded.status,200);space=uploaded.value.data.space;
 const code=`file_${++serial}`;await write('/datasources',{code,name,kind:'FILE',connection:{dialect:'csv',fileId:uploaded.value.data.file.id},accessScope:[uploaded.value.data.file.resource]});const src=(await data(`/datasources?spaceId=${space.id}`)).find(x=>x.code===code);
 const sn=await data(`/datasources/${src.id}/sync`,'POST',{spaceId:space.id,expectedDraftRev:space.draftRev});space=sn.space;
 await write('/mappings/objects',{datasourceId:src.id,sourceResource:uploaded.value.data.file.resource,targetClassId:cl.id,objectKeyExpr:`task:\${${key}}`,filter:null,metaSnapshotId:sn.snapshot.id});
 const om=(await data(`/mappings?spaceId=${space.id}`)).objects.find(x=>x.datasourceId===src.id);
 await write('/mappings/properties',{objectMappingId:om.id,sourceField:'id',targetPropertyId:p.id,transform:{kind:'IDENTITY'},required:true});
 await write(`/spaces/${space.id}/guards`,{guardCfg:{region:{type:'STRING',filter_binding:{table:uploaded.value.data.file.resource,column:'region',operator:'IN'}}}},'PUT');
 await write('/operations',{code,name,queryTemplate:'SELECT ?s WHERE { ?s ?p ?o }',maxRows:1,timeoutMs:5000});const op=(await data(`/operations?spaceId=${space.id}`)).find(x=>x.code===code);
 await write(`/operations/${op.id}/data-query`,{dataQuery:{mode:'OBJECT',objectMappingId:om.id,propertyIds:[p.id],filters:[]}},'PUT');await write(`/operations/${op.id}/assistant`,declaration(),'PUT');
 const c=await data(`/spaces/${space.id}/candidates`,'POST',{expectedDraftRev:space.draftRev});const x=await data(`/candidates/${c.id}/trial`,'POST',{operationId:op.id,input:{}});
 assert.equal(x.errorCode??null,expected);assert.equal(x.status,expected?'FAILED':'SUCCEEDED');
 if(!expected){assert.equal(x.rows.length,1);assert.equal(x.evidence.filter(x=>x.kind==='SOURCE_RECORD').length,1);}
 await record(`非主键身份${name}`,{resultStatus:x.status,errorCode:x.errorCode??null,rowCount:x.rows.length});
}
// 原R05同一真实DOCX+300个目录，修复后必须明确拒绝。
const original=new URL('../../fixtures/p3/domain.docx',import.meta.url);
const zip=execFileSync('python',['-c',"import sys,zipfile,io; b=io.BytesIO(); z=zipfile.ZipFile(b,'w',zipfile.ZIP_DEFLATED); src=zipfile.ZipFile(sys.argv[1]); [z.writestr(i,src.read(i)) for i in src.namelist()]; [z.writestr('review-dir-'+str(i)+'/',b'') for i in range(300)]; z.close(); sys.stdout.buffer.write(b.getvalue())",decodeURIComponent(original.pathname).replace(/^\//,'')]);
const over=await upload('over-directory-budget.docx',zip);assert.equal(over.status,400);await record('C5-R05超过256条目DOCX拒绝',{httpStatus:over.status,code:over.value.code});
// 原R03/R04关系反例；只读onto_sources，所有草稿写入新的独立空间。
await createSpace('关系与目标端依赖复审');
for(const name of ['算法','任务'])await write('/classes',{name,parentId:null,description:'复审',deprecated:false});
const cs=await data(`/classes?spaceId=${space.id}`);const ac=cs.find(x=>x.name==='算法'),tc=cs.find(x=>x.name==='任务');
for(const [name,c]of[['标准码',ac],['任务码',tc]])await write('/properties',{name,domainClassId:c.id,rangeType:'STRING',required:true,description:'复审'});
const ps=await data(`/spaces/${space.id}/properties`);const ap=ps.find(x=>x.domainClassId===ac.id),tp=ps.find(x=>x.domainClassId===tc.id);
const relDef={name:'算法对应唯一任务',fromClassId:ac.id,toClassId:tc.id,cardinality:'ONE_TO_ONE',transitive:false,symmetric:false,inverseName:null,description:'复审基数'};await write('/relations',relDef);const rel=(await data(`/relations?spaceId=${space.id}`))[0];
await write('/datasources',{code:'review_pg',name:'只读公开样例',kind:'RELATIONAL',connection:{dialect:'postgresql',url:`jdbc:postgresql://127.0.0.1:${env.ONTO_PG_PORT}/onto_sources`,user:env.ONTO_DB_USER,password:env.ONTO_DB_PASSWORD},accessScope:['onto_sample.tasks','onto_sample.algorithms']});
const src=(await data(`/datasources?spaceId=${space.id}`))[0];const sn=await data(`/datasources/${src.id}/sync`,'POST',{spaceId:space.id,expectedDraftRev:space.draftRev});space=sn.space;
for(const [resource,c,key]of[['onto_sample.algorithms',ac,'algorithm:${std_code}'],['onto_sample.tasks',tc,'task:${task_id}']])await write('/mappings/objects',{datasourceId:src.id,sourceResource:resource,targetClassId:c.id,objectKeyExpr:key,filter:null,metaSnapshotId:sn.snapshot.id});
maps=await data(`/mappings?spaceId=${space.id}`);const am=maps.objects.find(x=>x.targetClassId===ac.id),tm=maps.objects.find(x=>x.targetClassId===tc.id);
for(const [m,p,f]of[[am,ap,'std_code'],[tm,tp,'task_id']])await write('/mappings/properties',{objectMappingId:m.id,targetPropertyId:p.id,sourceField:f,required:true,transform:{kind:'IDENTITY'}});
await write('/mappings/relations',{datasourceId:src.id,relationId:rel.id,fromObjectMappingId:am.id,toObjectMappingId:tm.id,fromKeyExpr:am.objectKeyExpr,toKeyExpr:tm.objectKeyExpr,join:{fromField:'std_code',toField:'algo_code'},cardinalityCheck:true,metaSnapshotId:sn.snapshot.id});maps=await data(`/mappings?spaceId=${space.id}`);
await write(`/spaces/${space.id}/guards`,{guardCfg:{region:{type:'STRING',filter_bindings:['onto_sample.algorithms','onto_sample.tasks'].map(table=>({table,column:'region',operator:'IN'}))}}},'PUT');
const opDef={code:'cardinality_probe',name:'基数复审',queryTemplate:'SELECT ?s WHERE { ?s ?p ?o }',maxRows:1,timeoutMs:5000};await write('/operations',opDef);const op=(await data(`/operations?spaceId=${space.id}`))[0];
await write(`/operations/${op.id}/data-query`,{dataQuery:{mode:'RELATION',objectMappingId:am.id,relationMappingId:maps.relations[0].id,propertyIds:[ap.id,tp.id],filters:[]}},'PUT');await write(`/operations/${op.id}/assistant`,declaration(),'PUT');
for(const limit of[1,20]){if(limit!==1)await write(`/operations/${op.id}`,{...opDef,maxRows:limit},'PUT');const c=await data(`/spaces/${space.id}/candidates`,'POST',{expectedDraftRev:space.draftRev});const x=await data(`/candidates/${c.id}/trial`,'POST',{operationId:op.id,input:{}});assert.equal(x.status,'FAILED');assert.equal(x.errorCode,'CARDINALITY_VIOLATION');await record(`C5-R03业务LIMIT=${limit}均拒绝同一基数冲突`,{errorCode:x.errorCode});}
await write(`/relations/${rel.id}`,{...relDef,cardinality:'ONE_TO_MANY'},'PUT');await write(`/operations/${op.id}`,opDef,'PUT');
const clean=await data(`/spaces/${space.id}/candidates`,'POST',{expectedDraftRev:space.draftRev});const success=await data(`/candidates/${clean.id}/trial`,'POST',{operationId:op.id,input:{}});assert.equal(success.status,'SUCCEEDED');assert.equal(success.rows.length,1);assert.equal(success.evidence.filter(x=>x.kind==='SOURCE_RECORD').length,2);await record('合法基数仍返回一行且只保留双端两条来源证据');
const tpm=maps.properties.find(x=>x.objectMappingId===tm.id);const impact=await data(`/mappings/impact?spaceId=${space.id}&kind=properties&id=${tpm.id}`);assert.ok(impact.operations.some(x=>x.id===op.id));
const cat=await data(`/data-catalog?spaceId=${space.id}`);assert.ok(cat.bindings.find(x=>x.objectMappingId===tm.id).operationIds.includes(op.id));
const denied=await req(`/mappings/properties/${tpm.id}?spaceId=${space.id}&expectedDraftRev=${space.draftRev}`,'DELETE');assert.equal(denied.status,409);assert.equal(denied.value.code,'MAPPING_IN_USE');assert.ok((await data(`/mappings?spaceId=${space.id}`)).properties.some(x=>x.id===tpm.id));await record('C5-R04目标端影响目录均定位操作且引用阻删409',{code:denied.value.code,operations:impact.operations});
await data('/auth/logout','POST');console.log(JSON.stringify(evidence,null,2));
