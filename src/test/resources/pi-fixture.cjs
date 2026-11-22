const readline=require('node:readline'),fs=require('node:fs'),path=require('node:path');
const session=path.join(process.cwd(),'session.jsonl');
const emit=o=>process.stdout.write(JSON.stringify(o)+'\n');
readline.createInterface({input:process.stdin}).on('line',line=>{
 const c=JSON.parse(line);
 if(c.type==='prompt'){
  emit({type:'response',id:'unrelated',success:true});
  emit({type:'response',id:c.id,success:true,data:{disposition:'started'}});
  process.stderr.write('private diagnostic, never user output\n');
  if(c.message==='slow')return;
  if(['stream','thinking-stream','stall','heartbeat','endless'].includes(c.message)){
   let count=0;
   const timer=setInterval(()=>{
    count++;
    if(c.message==='heartbeat')emit({type:'response',id:'unrelated',success:true});
    else if(c.message!=='stall'||count===1)emit({type:'message_update',message:{role:'assistant'},assistantMessageEvent:{type:c.message==='thinking-stream'?'thinking_delta':'text_delta',delta:'有效进展'}});
    if(count===8&&['stream','thinking-stream'].includes(c.message)){
     clearInterval(timer);
     emit({type:'message_end',message:{role:'assistant',stopReason:'stop',content:[{type:'thinking',thinking:'never deliver'},{type:'text',text:'流式最终回答'}]}});
     emit({type:'agent_settled',aborted:false});
    }
   },250);
   return;
  }
  if(c.message==='model-error'){
   emit({type:'message_end',message:{role:'assistant',stopReason:'error',content:[{type:'text',text:'半成品不能发送'}]}});
   emit({type:'agent_settled',aborted:false});return;
  }
  if(c.message==='thinking-only'){
   emit({type:'message_end',message:{role:'assistant',stopReason:'stop',content:[{type:'thinking',thinking:'never deliver'}]}});
   emit({type:'agent_settled',aborted:false});return;
  }
  fs.writeFileSync(session,'checkpoint');
  emit({type:'message_end',message:{role:'assistant',stopReason:'stop',content:[{type:'thinking',thinking:'private reasoning'},{type:'text',text:'中文回答🙂'}]}});
  emit({type:'agent_end'});
  setTimeout(()=>emit({type:'agent_settled',aborted:false}),50);
 }
 if(c.type==='get_state')emit({type:'response',id:c.id,success:true,data:{sessionFile:session}});
 if(c.type==='abort')process.exit(0);
});
