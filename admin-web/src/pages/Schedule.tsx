/**
 * [INPUT]: 依赖统一 API、群配置与主动发送权限。
 * [OUTPUT]: 提供定时任务页面、日报配置与活跃榜入口。
 * [POS]: 日报和活跃榜配置独立；两者共用当前群选择。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
import {useEffect,useState} from 'react';
import {Clock3} from 'lucide-react';
import {api} from '../api';
import ActivitySchedule from './ActivitySchedule';
import type {Actions,Group,QqGateway,ScheduleSettings,SummaryReceipt,SummarySpeakerMode,SummaryStyleMode} from '../types';

const STYLE_OPTIONS:{value:SummaryStyleMode;title:string;desc:string}[]=[
 {value:'CASUAL',title:'日常群聊（推荐）',desc:'聚合多人热聊话题与共同活动，过滤个人琐事、口误与钓鱼段子'},
 {value:'KNOWLEDGE',title:'知识答疑',desc:'提炼有效问答结论、经验方法与待解问题，省去寒暄灌水'},
 {value:'WORK',title:'协作纪要',desc:'严格追踪决策结论、明确待办（责任人与期限）与阻塞分歧'}
];
const SPEAKER_OPTIONS:{value:SummarySpeakerMode;title:string;desc:string}[]=[
 {value:'KEY_ONLY',title:'仅关键角色（推荐）',desc:'仅点名话题发起人或待办负责人，避免传声筒式逐人报幕'},
 {value:'ANONYMOUS',title:'隐去群友昵称',desc:'正文统一以“群内 / 群友”概括，适合公开社群或隐私脱敏'},
 {value:'ACTIVE',title:'保留活跃参与者',desc:'自然保留主要互动成员昵称，适合熟人小群'}
];

export default function Schedule({group,busy,act,qq}:Actions&{group:Group;qq:QqGateway}){
 const[time,setTime]=useState('09:00'),[enabled,setEnabled]=useState(false),[styleMode,setStyleMode]=useState<SummaryStyleMode>('CASUAL'),[speakerMode,setSpeakerMode]=useState<SummarySpeakerMode>('KEY_ONLY'),[next,setNext]=useState(''),[result,setResult]=useState('');
 useEffect(()=>{api<ScheduleSettings>('/groups/'+group.id+'/schedule').then(settings=>{setTime(settings.local_time.slice(0,5));setEnabled(settings.enabled);setStyleMode(settings.style_mode??'CASUAL');setSpeakerMode(settings.speaker_mode??'KEY_ONLY');setNext(settings.next_run_at);}).catch(failure=>act(async()=>{throw failure;},''));},[group.id]);
 return <><section className="panel pad schedule"><span className="feature-icon"><Clock3 size={25}/></span><h2>每日群聊摘要</h2><p>按日期读取昨日全部已采集文本，按所选群聊场景提炼重点并排版发回原群。</p>
  {group.channel==='QQ'&&<div className="banner">官方模式仅汇总平台实际推送且已保存的消息。{qq.allowActiveMessages?'已允许主动发送，仍受开放平台权限和频控限制。':'主动发送默认关闭：完整报告可在执行记录中查看；自动发回群聊需要平台权限并设置 QQ_OFFICIAL_ALLOW_ACTIVE_MESSAGES=true。'}</div>}
  <form onSubmit={event=>{event.preventDefault();act(async()=>{const settings=await api<ScheduleSettings>('/groups/'+group.id+'/schedule',{method:'PUT',body:JSON.stringify({enabled,localTime:time,styleMode,speakerMode})});setStyleMode(settings.style_mode??styleMode);setSpeakerMode(settings.speaker_mode??speakerMode);setNext(settings.next_run_at);},'定时任务已保存');}}>
   <label className="check"><input type="checkbox" checked={enabled} onChange={event=>setEnabled(event.target.checked)}/>启用每日摘要</label>
   <label>每天执行时间<input type="time" required value={time} onChange={event=>setTime(event.target.value)}/></label>
   <fieldset className="schedule-fieldset"><legend>日报场景模式</legend><div className="schedule-options">{STYLE_OPTIONS.map(item=><label key={item.value} className={'schedule-option'+(styleMode===item.value?' selected':'')}><input type="radio" name="styleMode" value={item.value} checked={styleMode===item.value} onChange={()=>setStyleMode(item.value)}/><div><strong>{item.title}</strong><span>{item.desc}</span></div></label>)}</div></fieldset>
   <fieldset className="schedule-fieldset"><legend>成员署名方式</legend><div className="schedule-options">{SPEAKER_OPTIONS.map(item=><label key={item.value} className={'schedule-option'+(speakerMode===item.value?' selected':'')}><input type="radio" name="speakerMode" value={item.value} checked={speakerMode===item.value} onChange={()=>setSpeakerMode(item.value)}/><div><strong>{item.title}</strong><span>{item.desc}</span></div></label>)}</div></fieldset>
   <p className="muted">Asia/Shanghai（北京时间） · 分析昨日 00:00 至今日 00:00 的文本</p>{next&&<div className="schedule-next">下次计划：{new Date(next).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai'})}</div>}
   <div className="action-row"><button className="primary" disabled={busy}>保存任务</button><button type="button" className="secondary" disabled={busy} onClick={()=>act(async()=>{
    setResult('');const run=await api<SummaryReceipt>('/groups/'+group.id+'/summary/yesterday',{method:'POST'});
    const message=run.state==='DELIVERED'?'该日期日报已发送，不会重复发送':run.state==='DELIVERY_UNKNOWN'?'发送结果未知，请先核实群内消息':run.state==='GENERATED'?'报告已生成，正在发送未成功的分段':'昨日分析任务已提交，失败后可再次执行';
    setResult(message+'。任务：'+run.runId+'；请在执行记录中查看进度。');
   },'')}>立即分析昨日</button></div>
  </form>{result&&<div role="status" className="banner">{result}</div>}<div className="note">失败任务可重复执行，不限制重试次数；发送失败只补发未成功的分段。进行中或已发送的任务不会重复执行，发送结果未知时需先人工核实。关闭定时任务只阻止后续自动触发。</div>
 </section><ActivitySchedule group={group} qq={qq} busy={busy} act={act}/></>;
}
