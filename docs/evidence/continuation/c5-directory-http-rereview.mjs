// R05最终包公开HTTP边界复核：单次33MiB目录负载，仅比预算多1MiB。
import {readFile,writeFile} from 'node:fs/promises';
import {execFileSync} from 'node:child_process';
import assert from 'node:assert/strict';
const env=JSON.parse((await readFile(new URL('../../../.runtime/local-env.json',import.meta.url),'utf8')).replace(/^\uFEFF/,''));
let token,space;const evidence={packageSha:process.env.C5_REVIEW_SHA,timestamp:new Date().toISOString(),checks:[]};
async function req(path,method='GET',body){const form=body instanceof FormData;const r=await fetch('http://127.0.0.1:18182/onto'+path,{method,headers:{...(token?{Authorization:`Bearer ${token}`} : {}),...(body&&!form?{'Content-Type':'application/json'}:{})},body:body===undefined?undefined:form?body:JSON.stringify(body),signal:AbortSignal.timeout(30000)});return {status:r.status,body:await r.json()};}
async function data(...args){const r=await req(...args);assert.equal(r.status,200);return r.body.data;}
token=(await data('/auth/login','POST',{username:env.ONTO_BOOTSTRAP_USERNAME,password:env.ONTO_BOOTSTRAP_PASSWORD})).token;
const code=`dir-review-${Date.now().toString(36)}`;space=await data('/spaces','POST',{code,name:'目录解压预算复审',namespaceUri:`https://example.org/${code}/`,domain:'复审'});evidence.spaceId=space.id;
const original=new URL('../../fixtures/p3/domain.docx',import.meta.url);
for(const [name,mode,want]of[['real.docx','plain',200],['empty-directory.docx','empty',200],['nonempty-directory.docx','payload',400]]){
 const bytes=execFileSync('python',['-c',"import sys,zipfile,io; b=io.BytesIO(); z=zipfile.ZipFile(b,'w',zipfile.ZIP_DEFLATED); src=zipfile.ZipFile(sys.argv[1]); [z.writestr(i,src.read(i)) for i in src.namelist()]; mode=sys.argv[2]; z.writestr('review-dir/',bytes(33*1024*1024) if mode=='payload' else b'') if mode!='plain' else None; z.close(); sys.stdout.buffer.write(b.getvalue())",decodeURIComponent(original.pathname).replace(/^\//,''),mode]);
 const form=new FormData();form.append('file',new Blob([bytes]),name);const r=await req(`/data-files?spaceId=${space.id}&expectedDraftRev=${space.draftRev}`,'POST',form);assert.equal(r.status,want);if(r.status===200)space=r.body.data.space;
 evidence.checks.push({name,status:'PASSED',archiveBytes:bytes.length,httpStatus:r.status,code:r.body.code});
}
await data('/auth/logout','POST');await writeFile(new URL('c5-directory-http-rereview.json',import.meta.url),JSON.stringify(evidence,null,2)+'\n');console.log(JSON.stringify(evidence,null,2));
