import {test,expect} from '@playwright/test';
test('admin workflow: login, four pages, safe source text, idempotent summary, clear and logout',async({page})=>{
 let signed=false,cleared=false,submissions=0,csrfCalls=0;
 const failures=[];page.on('pageerror',e=>failures.push(e.message));
 const group={id:'g1',channel:'QQ',account_id:'10001',external_id:'12345',name:'产品研发群',enabled:true,reply_enabled:true,admin_ids:'u1',revision:0,connection_state:'UNKNOWN',message_count:120,memory_count:117};
 await page.route('**/api/admin/**',async route=>{
  const path=new URL(route.request().url()).pathname.replace('/api/admin',''),method=route.request().method();
  const send=(json,status=200)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify(json)});
  if(path==='/csrf'){csrfCalls++;return send({token:'csrf-fixture'});}
  if(path==='/login'){signed=true;return send({authenticated:true});}
  if(!signed)return send({},401);
  if(path==='/me')return send({username:'admin'});
  if(path==='/groups')return send([group]);
  if(path.endsWith('/memory/status'))return send({pending:3,failed:0,indexed:cleared?0:117});
  if(path.endsWith('/memory/search'))return send([{id:'source-1',name:'小明',time:'2026-11-06T08:00:00Z',body:'<script>window.injected=true</script> 周五发布',score:0.91}]);
  if(path.endsWith('/memory')&&method==='DELETE'){expect(JSON.parse(route.request().postData()).confirmName).toBe(group.name);cleared=true;return send({});}
  if(path.endsWith('/schedule'))return send({enabled:true,local_time:'09:00:00',next_run_at:'2026-11-08T01:00:00Z'});
  if(path.endsWith('/summary/yesterday')){submissions++;return send({runId:'r1'});}
  if(path==='/runs')return send([{id:'r1',business_date:'2026-11-06',state:'DELIVERY_UNKNOWN',attempt:1,created_at:'2026-11-07T01:00:00Z'}]);
  if(path==='/runs/r1')return send({id:'r1',business_date:'2026-11-06',state:'DELIVERY_UNKNOWN',attempt:1,coverage:{messages:120,gaps:[]},report:'昨日讨论：周五发布 [source-1]'});
  if(path==='/logout'){signed=false;return route.fulfill({status:204});}
  return send({});
 });
 await page.goto('/');await page.getByLabel('管理员账号').fill('admin');await page.getByLabel('密码',{exact:true}).fill('test-password');await page.getByRole('button',{name:'进入管理台'}).click();
 await expect(page.getByRole('heading',{name:'让讨论有迹可循'})).toBeVisible();await expect(page.getByText('产品研发群',{exact:true})).toBeVisible();
 await page.screenshot({path:'../plan/1107/evidence/admin-groups.png',fullPage:true});
 await page.getByRole('button',{name:'群记忆',exact:true}).click();await page.getByPlaceholder('例如：上次讨论的发布安排是什么？').fill('发布安排');await page.getByRole('button',{name:'检索',exact:true}).click();
 await expect(page.getByText('<script>window.injected=true</script> 周五发布',{exact:true})).toBeVisible();expect(await page.evaluate(()=>window.injected)).toBeUndefined();
 await page.getByRole('button',{name:'定时任务',exact:true}).click();await expect(page.getByRole('heading',{name:'每日群聊摘要'})).toBeVisible();
 page.on('dialog',dialog=>dialog.type()==='prompt'?dialog.accept(group.name):dialog.accept());
 await page.getByRole('button',{name:'立即分析昨日'}).click();await expect.poll(()=>submissions).toBe(1);
 await page.getByRole('button',{name:'执行记录',exact:true}).click();await expect(page.getByText('发送结果未知',{exact:true})).toBeVisible();await page.getByRole('button',{name:'查看报告'}).click();await expect(page.getByText('昨日讨论：周五发布 [source-1]',{exact:true})).toBeVisible();await expect(page.getByRole('button',{name:'重试分析一次'})).toHaveCount(0);
 await page.screenshot({path:'../plan/1107/evidence/admin-report.png',fullPage:true});
 await page.getByRole('button',{name:'群记忆',exact:true}).click();await page.getByRole('button',{name:'清除本群记忆'}).click();await expect.poll(()=>cleared).toBe(true);
 await page.getByRole('button',{name:'退出登录'}).click();await expect(page.getByRole('heading',{name:'欢迎回到群知'})).toBeVisible();expect(csrfCalls).toBeGreaterThanOrEqual(2);expect(failures).toEqual([]);
});
