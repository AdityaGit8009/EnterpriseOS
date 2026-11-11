// Run ONLY against the fresh isolated database started by tmp/start-http-test.ps1.
const assert=require('node:assert/strict'),fs=require('node:fs');
const base='http://localhost:5174',password='SyntheticHttpOnly!123';
async function main(){
 let cookie='',checks=0;
 async function call(route,method='GET',body,expected=200){const r=await fetch(base+'/api/v1'+route,{method,headers:{Origin:base,'Content-Type':'application/json',...(cookie?{Cookie:cookie}:{})},...(body?{body:JSON.stringify(body)}:{})});const data=await r.json();assert.equal(r.status,expected,route+': '+JSON.stringify(data));checks++;if(r.headers.get('set-cookie'))cookie=r.headers.get('set-cookie').split(';')[0];return data}
 const status=await call('/setup');assert.equal(status.required,true,'Refusing to initialize a used database');
 await call('/setup','POST',{companyName:'Isolated HTTP verification',ownerName:'Synthetic owner',ownerEmail:'http-owner@example.test',ownerPassword:password,platformEmail:'http-platform@example.test',platformPassword:password,timezone:'Asia/Kolkata'});
 const owner=await call('/auth/login','POST',{email:'http-owner@example.test',password});
 const project=await call('/org/records/projects','POST',{name:'HTTP verification project',ownerId:owner.userId,status:'ACTIVE',memberIds:[],startDate:'2026-11-01',dueDate:'2026-12-01'});
 const task=await call('/org/records/tasks','POST',{name:'Persistent task',projectId:project.id,status:'TODO',priority:'MEDIUM',assigneeId:owner.userId,startDate:'2026-11-01',dueDate:'2026-11-20'});
 await call('/org/tasks/'+task.id+'/comments','POST',{body:'Authenticated HTTP comment'});
 const history=await call('/org/records/tasks/'+task.id+'/history');assert.ok(history.some(row=>row.action==='COMMENT'));
 const old=cookie;await call('/auth/refresh','POST');assert.notEqual(cookie,old);
 const active=cookie;cookie=old;await call('/me','GET',null,401);cookie=active;
 for(const endpoint of ['/org/records/tasks','/org/business/records/leads','/org/workforce/projects/'+project.id+'/risks','/org/workflows/definitions','/org/documents','/org/reports','/org/permission-catalog'])await call(endpoint);
 await call('/me/sessions','DELETE');await call('/me','GET',null,401);
 fs.writeFileSync('tmp/http-workflows.json',JSON.stringify({checkedAt:new Date().toISOString(),checks,scope:'Fresh isolated MySQL schema; synthetic account and records; actual HTTP proxy/API authentication, persistence, comment history and session revocation'},null,2));console.log(checks+' authenticated HTTP workflow checks passed.');
}
main().catch(error=>{console.error(error.message);process.exitCode=1});
