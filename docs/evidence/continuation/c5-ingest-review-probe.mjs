// 独立解析/确认反例，仅公开API与自建空间；XML拒绝探针不包含外部地址。
import {readFile,writeFile} from 'node:fs/promises';
import {execFileSync} from 'node:child_process';
import assert from 'node:assert/strict';
const env=JSON.parse((await readFile(new URL('../../../.runtime/local-env.json',import.meta.url),'utf8')).replace(/^\uFEFF/,''));
let token,space;const evidence={timestamp:new Date().toISOString(),checks:[]};
async function req(path,method='GET',body){const form=body instanceof FormData;const r=await fetch('http://127.0.0.1:18182/onto'+path,{method,headers:{...(token?{Authorization:`Bearer ${token}`} : {}),...(body&&!form?{'Content-Type':'application/json'}:{})},body:body===undefined?undefined:form?body:JSON.stringify(body),signal:AbortSignal.timeout(30000)});return {status:r.status,value:await r.json()};}
async function data(...args){const r=await req(...args);assert.equal(r.status,200,`${args[0]}: ${r.value.code}`);return r.value.data;}
async function upload(name,bytes){const form=new FormData();form.append('file',new Blob([bytes]),name);return req(`/data-files?spaceId=${space.id}&expectedDraftRev=${space.draftRev}`,'POST',form);}
const unauth=await req('/spaces');assert.equal(unauth.status,401);evidence.checks.push({name:'匿名管理读取拒绝',status:unauth.status});
token=(await data('/auth/login','POST',{username:env.ONTO_BOOTSTRAP_USERNAME,password:env.ONTO_BOOTSTRAP_PASSWORD})).token;
const code=`ingest-review-${Date.now().toString(36)}`;space=await data('/spaces','POST',{code,name:'独立抽取边界复核',namespaceUri:`https://example.org/${code}/`,domain:'审查'});evidence.spaceId=space.id;
let firstBatch;
for(const name of ['tasks.csv','tasks.json','tasks.xlsx','domain.txt','domain.pdf','domain.docx']){
 const up=await upload(name,await readFile(new URL(`../../fixtures/p3/${name}`,import.meta.url)));assert.equal(up.status,200);space=up.value.data.space;
 const sourceKind=/\.(csv|json|xlsx)$/.test(name)?'FILE':'DOCUMENT';
 const x=await data('/suggestions/extract','POST',{spaceId:space.id,expectedDraftRev:space.draftRev,sourceKind,fileId:up.value.data.file.id});space=x.space;
 const items=await data(`/suggestions?spaceId=${space.id}&batchId=${x.batchId}`);assert.ok(items.length);assert.ok(items.every(x=>x.status==='PENDING'&&x.location.sourceName===name));
 if(name.endsWith('.pdf'))assert.ok(items.every(x=>Number.isInteger(x.location.page)));
 if(/\.(txt|docx)$/.test(name))assert.ok(items.every(x=>!Object.hasOwn(x.location,'page')));
 evidence.checks.push({name:`${name}真实解析仅生成定位PENDING`,count:items.length,status:'PASSED'});
 if(name==='tasks.csv')firstBatch=items;
}
assert.equal((await data(`/classes?spaceId=${space.id}`)).length,0);
const orphan=firstBatch.find(x=>x.kind==='PROPERTY');
const failure=await req('/suggestions/decide','POST',{spaceId:space.id,expectedDraftRev:space.draftRev,decisions:[{id:orphan.id,decision:'ACCEPT'}]});
assert.equal(failure.value.code,'SUGGESTION_DEPENDENCY_UNRESOLVED');assert.equal((await data(`/classes?spaceId=${space.id}`)).length,0);
evidence.checks.push({name:'缺依赖确认拒绝且无模型写入',status:failure.status,code:failure.value.code});
space=await data('/suggestions/decide','POST',{spaceId:space.id,expectedDraftRev:space.draftRev,decisions:firstBatch.toReversed().map(x=>({id:x.id,decision:'ACCEPT'}))});
assert.equal((await data(`/classes?spaceId=${space.id}`)).length,1);
const repeated=await req('/suggestions/decide','POST',{spaceId:space.id,expectedDraftRev:space.draftRev,decisions:[{id:orphan.id,decision:'ACCEPT'}]});assert.equal(repeated.value.code,'SUGGESTION_ALREADY_DECIDED');
evidence.checks.push({name:'逆序批量依赖接受且重复确认拒绝',status:repeated.status,code:repeated.value.code});
const masquerade=await upload('fake.docx',Buffer.from('plain text'));assert.equal(masquerade.status,400);evidence.checks.push({name:'纯文本冒充DOCX拒绝',status:masquerade.status});
// 增加300个空目录，文件正文完全沿用真实DOCX；不制造解压炸弹。
const original=new URL('../../fixtures/p3/domain.docx',import.meta.url);
const zip=execFileSync('python',['-c',"import sys,zipfile,io; b=io.BytesIO(); z=zipfile.ZipFile(b,'w',zipfile.ZIP_DEFLATED); src=zipfile.ZipFile(sys.argv[1]); [z.writestr(i,src.read(i)) for i in src.namelist()]; [z.writestr('review-dir-'+str(i)+'/',b'') for i in range(300)]; z.close(); sys.stdout.buffer.write(b.getvalue())",decodeURIComponent(original.pathname).replace(/^\//,'')]);
const overEntries=await upload('over-directory-budget.docx',zip);evidence.checks.push({name:'超过256个Office条目仍被接受',extraDirectoryEntries:300,status:overEntries.status,code:overEntries.value.code});assert.equal(overEntries.status,200);space=overEntries.value.data.space;
await writeFile(new URL('c5-ingest-review-probe.json',import.meta.url),JSON.stringify(evidence,null,2)+'\n');await data('/auth/logout','POST');console.log(JSON.stringify(evidence,null,2));
