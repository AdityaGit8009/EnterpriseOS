export type Row={id:string;version?:number;[key:string]:any};
export type Principal={userId:string;tenantId:string;name:string;email:string;role:string;customRoleId?:string;customRoleName?:string;permissions?:string[]};
export type Snapshot=Record<string,Row[]>;
export const roles=['ADMIN','HR','MANAGER','MEMBER','AUDITOR','SALES','PROCUREMENT','FINANCE','PAYROLL'];
export const kinds=['departments','branches','teams','designations','employees','shifts','shiftAssignments','leavePolicies','projects','sprints','milestones','tasks','timeEntries'];
export const uid=()=>globalThis.crypto?.randomUUID?.()||`${Date.now()}-${Math.random().toString(36).slice(2)}`;
export const today=()=>new Date().toISOString().slice(0,10);
export function storageGet(key:string){try{return localStorage.getItem(key)}catch{return null}}
export function storageSet(key:string,value:string){try{localStorage.setItem(key,value)}catch{}}
export function storageRemove(key:string){try{localStorage.removeItem(key)}catch{}}
export const isAdmin=(p:Principal)=>p.role==='ADMIN';
export const canManage=(p:Principal)=>['ADMIN','HR'].includes(p.role);
export const canApprove=(p:Principal)=>['ADMIN','HR','MANAGER'].includes(p.role);
export function emptySnapshot():Snapshot{return Object.fromEntries([...kinds,'users','tenants','profile','attendance','corrections','leaveRequests','ledger','comments','invitations','audit','balances','featureFlags','myPermissions'].map(k=>[k,[]]));}
export async function api(path:string,method='GET',body?:any){const result=await fetch('/api/v1'+path,{method,credentials:'include',headers:body?{'Content-Type':'application/json'}:undefined,body:body?JSON.stringify(body):undefined});let data:any;try{data=await result.json()}catch{data=null}if(!result.ok){const error=new Error(data?.message||`Request failed (${result.status})`);(error as any).status=result.status;throw error};return data;}

export function canAccess(principal:Principal,method:string,path:string):boolean{
 if(!principal.customRoleId||!path.startsWith('/org/')&&!path.startsWith('/api/v1/org/'))return true;
 const target=(path.startsWith('/api/v1/')?path:'/api/v1'+path).split('?')[0];
 if(target==='/api/v1/org/my-permissions')return true;
 return (principal.permissions||[]).some(permission=>{const separator=permission.indexOf(' ');if(separator<0||permission.slice(0,separator)!==method.toUpperCase())return false;const template=permission.slice(separator+1);const escaped=template.split(/(\{[^}]+\})/).map(segment=>/^\{[^}]+\}$/.test(segment)?'[^/]+':segment.replace(/[.*+?^${}()|[\]\\]/g,'\\$&')).join('');return new RegExp('^'+escaped+'$').test(target)});
}
export async function loadLive(p:Principal):Promise<{snapshot:Snapshot;errors:string[]}>{
 const result=emptySnapshot(),errors:string[]=[];let effective={...p};
 if(p.role!=='PLATFORM_ADMIN')try{const mine=await api('/org/my-permissions');if(mine&&!Array.isArray(mine)){result.myPermissions=[{id:'my-permissions',...mine}];effective={...p,customRoleId:mine.customRoleId||'',customRoleName:mine.name,permissions:mine.permissions||[]}}}catch(e){if((e as any).status===401)throw e;errors.push('my permissions: '+(e as Error).message)}
 const endpoints:Record<string,string>=p.role==='PLATFORM_ADMIN'?{tenants:'/platform/tenants'}:{users:'/org/users',profile:'/org/profile',featureFlags:'/org/feature-flags',...(['ADMIN'].includes(p.role)?{invitations:'/org/invitations'}:{}),...(['ADMIN','AUDITOR'].includes(p.role)?{audit:'/org/audit-events'}:{}),attendance:'/org/attendance',corrections:'/org/attendance/corrections',leaveRequests:'/org/leave/requests',ledger:'/org/leave/ledger',balances:'/org/leave/balances',...Object.fromEntries(kinds.map(k=>[k,'/org/records/'+k]))};
 await Promise.all(Object.entries(endpoints).filter(([,path])=>canAccess(effective,'GET',path)).map(async([key,path])=>{try{const data=await api(path);result[key]=Array.isArray(data)?data:data?[data]:[]}catch(e){errors.push(key+': '+(e as Error).message)}}));
 result.comments=result.tasks.flatMap(t=>(t.comments||[]).map((c:Row)=>({...c,taskId:t.id})));
 return {snapshot:result,errors};
}
export async function download(path:string,name:string){const response=await fetch('/api/v1'+path,{credentials:'include'});if(!response.ok){let message='Download failed ('+response.status+')';try{message=(await response.json()).message||message}catch{}throw new Error(message)}const url=URL.createObjectURL(await response.blob());const link=document.createElement('a');link.href=url;link.download=name;link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}
export function fileDataUrl(file:File,maxBytes=5*1024*1024):Promise<string>{if(file.size>maxBytes)return Promise.reject(new Error('File exceeds the '+Math.round(maxBytes/1024/1024)+' MB limit.'));return new Promise((resolve,reject)=>{const reader=new FileReader();reader.onload=()=>resolve(String(reader.result));reader.onerror=()=>reject(new Error('The file could not be read.'));reader.readAsDataURL(file)})}
