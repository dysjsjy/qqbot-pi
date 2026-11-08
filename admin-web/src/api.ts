let csrf='';
export async function refreshCsrf(){const r=await fetch('/api/admin/csrf',{credentials:'same-origin'});if(!r.ok)throw new Error('无法建立安全会话');csrf=(await r.json()).token;}
export async function api<T=any>(path:string,options:RequestInit={}):Promise<T>{
 const method=options.method??'GET';if(method!=='GET'&&!csrf)await refreshCsrf();
 const response=await fetch('/api/admin'+path,{...options,credentials:'same-origin',headers:{...(options.body?{'Content-Type':'application/json'}:{}),...(method!=='GET'?{'X-CSRF-TOKEN':csrf}:{}),...options.headers}});
 if(response.status===401){csrf='';throw new Error('登录已失效，请重新登录');}
 if(!response.ok){let detail='';try{detail=(await response.json()).message??'';}catch{}throw new Error(detail||({'403':'无权执行此操作','409':'操作冲突，请刷新后重试','429':'请求过于频繁，请稍后重试'}[String(response.status)]??`请求失败 (${response.status})`));}
 return response.status===204||response.headers.get('content-length')==='0'?undefined as T:response.text().then(t=>t?JSON.parse(t):undefined as T);
}
export async function login(username:string,password:string){await refreshCsrf();const form=new URLSearchParams({username,password});const r=await fetch('/api/admin/login',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/x-www-form-urlencoded','X-CSRF-TOKEN':csrf},body:form});if(!r.ok)throw new Error(r.status===429?'登录过于频繁，请稍后重试':'用户名或密码不正确');await refreshCsrf();}
