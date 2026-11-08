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
  fs.writeFileSync(session,'checkpoint');
  emit({type:'message_end',message:{role:'assistant',stopReason:'stop',content:[{type:'thinking',thinking:'private reasoning'},{type:'text',text:'中文回答🙂'}]}});
  emit({type:'agent_end'});
  setTimeout(()=>emit({type:'agent_settled',aborted:false}),50);
 }
 if(c.type==='get_state')emit({type:'response',id:c.id,success:true,data:{sessionFile:session}});
 if(c.type==='abort')process.exit(0);
});
