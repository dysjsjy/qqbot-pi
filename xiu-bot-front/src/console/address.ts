export function normalizeAdminAddress(address:string){
 try{
  const url=new URL(address);
  return ['http:','https:'].includes(url.protocol)&&!url.username&&!url.password?url.href:'';
 }catch{return '';}
}

export function defaultAdminAddress(){return import.meta.env.VITE_ADMIN_URL||`${location.protocol}//${location.hostname}:${import.meta.env.VITE_ADMIN_PORT||'8082'}`;}
