const fs = require('node:fs');
const path = require('node:path');
const readline = require('node:readline');
const emit = event => process.stdout.write(JSON.stringify(event) + '\n');
const limit = Number(process.env.QQBOT_PI_MAX_OUTPUT_TOKENS);
emit({type:'qqbot_runtime_ready',maxOutputTokens:limit});
readline.createInterface({input:process.stdin}).on('line', line => {
    const command = JSON.parse(line);
    if (command.type === 'abort') process.exit(0);
    if (command.type !== 'prompt') return;
    const marker = /^hold:(.*)$/.exec(command.message);
    if (marker) fs.writeFileSync(path.join(process.cwd(), marker[1] + '.started'), String(process.pid));
    emit({type:'response',id:command.id,success:true,data:{disposition:'started'}});
    if (command.message === 'slow' || marker) return;
    const usage = command.message === 'no-usage' ? undefined : command.message === 'zero-usage'
        ? {input:0,output:0,cacheRead:0,cacheWrite:0}
        : {input:100,output:10,cacheRead:20,cacheWrite:5,reasoning:3};
    if (command.message === 'partial-usage') delete usage.cacheWrite;
    if (command.message === 'zero-cache-write') usage.cacheWrite = 0;
    const event = {type:'message_end',message:{role:'assistant',stopReason:command.message === 'usage-error' ? 'error' : 'stop',usage,content:[{type:'text',text:'合成正常答复'}]}};
    emit(event);
    if (command.message === 'duplicate-usage') emit(event);
    emit({type:'agent_settled',aborted:false});
});
