// A reproducible local staging read workload, not a contractual capacity/SLA claim.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const base=process.env.EOS_LOAD_URL||'http://localhost:5174';
const total=Math.max(20,Math.min(10000,Number(process.env.EOS_LOAD_REQUESTS||300)));
const endpoints=['/org/records/projects','/org/records/tasks','/org/attendance','/org/business/records/leads','/org/reports'];
async function main(){
 const email=process.env.EOS_LOAD_EMAIL,password=process.env.EOS_LOAD_PASSWORD;
 if(!email||!password)throw Error('Set EOS_LOAD_EMAIL and EOS_LOAD_PASSWORD for an isolated staging account.');
 const response=await fetch(base+'/api/v1/auth/login',{method:'POST',headers:{Origin:base,'Content-Type':'application/json'},body:JSON.stringify({email,password})});
 assert.equal(response.status,200,'Staging login must succeed');const cookie=response.headers.get('set-cookie').split(';')[0];
 const results=[];
 for(const concurrency of [1,8,24,64]){
  let next=0,failures=0;const durations=[],start=performance.now();
  await Promise.all(Array.from({length:concurrency},async()=>{while(next<total){const index=next++,started=performance.now();try{const result=await fetch(base+'/api/v1'+endpoints[index%endpoints.length],{headers:{Cookie:cookie}});await result.arrayBuffer();if(!result.ok)failures++;}catch{failures++}durations.push(performance.now()-started)}}));
  durations.sort((a,b)=>a-b);const elapsed=performance.now()-start,percentile=p=>Math.round(durations[Math.min(durations.length-1,Math.ceil(durations.length*p)-1)]*100)/100;
  results.push({concurrency,requests:total,failures,elapsedMillis:Math.round(elapsed),requestsPerSecond:Math.round(total/(elapsed/1000)*100)/100,p50Millis:percentile(.5),p95Millis:percentile(.95),p99Millis:percentile(.99)});
 }
 await fetch(base+'/api/v1/auth/logout',{method:'POST',headers:{Origin:base,Cookie:cookie}});
 const evidence={testedAt:new Date().toISOString(),base,endpoints,results,scope:'Local synthetic authenticated read workload at four concurrency levels, including 64 concurrent requests above the 10-connection database pool. No production availability or capacity target is inferred.'};
 const file=path.resolve(__dirname,'../tmp/load-evidence.json');fs.mkdirSync(path.dirname(file),{recursive:true});fs.writeFileSync(file,JSON.stringify(evidence,null,2));console.log(JSON.stringify(results));if(results.some(r=>r.failures))process.exitCode=1;
}
main().catch(e=>{console.error(e.message);process.exitCode=1});
