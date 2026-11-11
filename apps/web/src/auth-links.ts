export type AuthLink={flow:'accept'|'reset'|'verify-company';token:string};

/** Consume email-link parameters once; keep the credential only in form memory. */
export function consumeAuthLink(locationLike:{href:string},historyLike:{replaceState:(state:any,title:string,url:string)=>void}):AuthLink|null {
 const url=new URL(locationLike.href);
 const flow=url.searchParams.get('flow');
 const token=url.searchParams.get('token')||'';
 const recognized=flow==='invite'||flow==='reset'||flow==='verify-company';
 if(url.searchParams.has('token')||recognized){
  url.searchParams.delete('token');
  if(recognized)url.searchParams.delete('flow');
  historyLike.replaceState(null,'',url.pathname+url.search+url.hash);
 }
 if(!recognized)return null;
 return {flow:flow==='invite'?'accept':flow==='verify-company'?'verify-company':'reset',token:token.length<=256?token:''};
}
