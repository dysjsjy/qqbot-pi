/**
 * [INPUT]: 依赖管理 API 的响应与表单契约。
 * [OUTPUT]: 定义会话、活跃成员、回复策略、归档设置、定时与诊断类型。
 * [POS]: 前端跨页面契约，统一声明群范围和榜单的数据形状。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
export type Group={
 id:string;channel:string;account_id:string;external_id:string;name:string;
 enabled:boolean;reply_enabled:boolean;admin_ids:string;revision:number;
 connection_state:string;message_count:number;memory_count:number;last_received_at?:string;
 chat_type?:'GROUP'|'PRIVATE';target_id?:string;
};
export type QqGateway={accountId:string;allowActiveMessages:boolean;transport?:string};
export type Actions={busy:boolean;act:(work:()=>Promise<unknown>,success?:string)=>Promise<void>};
export type GroupMember={senderId:string;senderName:string;messageCount:number;lastAt:string|null;admin:boolean};
export type GroupMemberPage={members:GroupMember[];total:number;asOf:string;revision:number};
export type GroupMemberDetail={member:GroupMember;messages7d:number;messages30d:number;groupName:string;accountId:string;channel:string;revision:number;asOf:string};
export type ReplyPolicySettings={mode:'MENTION'|'PROBABILITY'|'ENERGY';probability:number;energy:number;energy_gain:number;energy_cost:number;revision:number};
export type ActivityPublishMode='CONSOLE_ONLY'|'GROUP';
export type ActivitySettings={enabled:boolean;local_time:string;zone:string;top_n:number;publish_mode:ActivityPublishMode;revision:number;next_run_at:string};
export type ActivityRun={id:string;business_date:string;starts_at:string;ends_at:string;snapshot_at:string;state:'GENERATED'|'NO_DATA';delivery_state:'NOT_REQUESTED'|'BLOCKED'|'QUEUED'|'DELIVERED'|'FAILED'|'UNKNOWN'|'CANCELLED';error_code:string|null;
 coverage:{partial:boolean;scopeNotice:string};ranking:{rank:number;nickname:string;messageCount:number}[]};
export type ActivityRunPage={runs:ActivityRun[];total:number};
export type Page='groups'|'memory'|'reply'|'schedule'|'runs'|'logs'|'traces';
export type SummaryStyleMode='CASUAL'|'KNOWLEDGE'|'WORK';
export type SummarySpeakerMode='KEY_ONLY'|'ANONYMOUS'|'ACTIVE';
export type ScheduleSettings={local_time:string;enabled:boolean;next_run_at:string;style_mode?:SummaryStyleMode;speaker_mode?:SummarySpeakerMode};
export type SummaryReceipt={runId:string;state:string;attempt:number};
export type RunCoverage={styleMode?:SummaryStyleMode;speakerMode?:SummarySpeakerMode;messages?:number;nonTextMessages?:number;gaps?:unknown[];[key:string]:unknown};
export type RunRecord={id:string;business_date:string;state:string;attempt:number;created_at:string;error_code?:string;report?:string;coverage?:RunCoverage};
export type MemorySettings={revision:number;mode:'RAW'|'SUMMARY';strategyVersion:1|2;summaryTriggerTokens:number;summaryCooldownMinutes:number};
export type MemoryPurpose='CHAT'|'MEMORY_QUERY'|'MEMORY_SUMMARY'|'DAILY_SUMMARY';
export type MemoryUsageTotals={coverage:'GENERATION_ONLY';currency:string;priceVersion:string;calls:number;dispatchedCalls:number;reportedCalls:number;unknownCalls:number;inputTokens:number;outputTokens:number;cacheReadTokens:number;cacheWriteTokens:number;reportedCost:number|null;estimatedCost:number|null};
export type MemoryUsage=MemoryUsageTotals&{date:string;zone:'Asia/Shanghai';byPurpose:Record<MemoryPurpose,MemoryUsageTotals>};
export type MemoryContextStats={runId:string;estimatedInputTokens:number;layerTokens:Record<'system'|'current'|'manual'|'history'|'evidence'|'summary'|'overhead',number>;sourceCount:number;manualHash:string;reasons:string[]};
export type TraceKind='INGRESS'|'AGENT'|'DELIVERY'|'BACKGROUND';
export type TraceSpan={id:string;runId:string;groupId:string|null;parentSpan:string|null;kind:TraceKind;name:string;seq:number;
 status:'OK'|'ERROR'|'CANCELLED';errorCode:string|null;startUs:number;durationUs:number;cpuUs:number;allocBytes:number;memoryBytes:number;
 estimatedInputTokens:number|null;inputTokens:number|null;outputTokens:number|null;cacheReadTokens:number|null;cacheWriteTokens:number|null;
 reasoningTokens:number|null;attributes:Record<string,unknown>};
export type TraceDetail={runId:string;groupId:string|null;startUs:number;endUs:number;spans:TraceSpan[]};
export type TraceStat={kind:TraceKind;name:string;count:number;errors:number;totalUs:number;avgUs:number;p50Us:number;p95Us:number;maxUs:number;avgCpuUs:number;avgAllocBytes:number};
