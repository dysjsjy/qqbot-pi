import type {TraceKind} from './types';
export const kindLabels:Record<TraceKind,string>={INGRESS:'入口',AGENT:'智能体',DELIVERY:'投递',BACKGROUND:'后台'};
export const kindColors:Record<TraceKind,string>={INGRESS:'#2a78d6',AGENT:'#e0822d',DELIVERY:'#1a9c7a',BACKGROUND:'#8a5cd6'};
export const statusLabels:Record<string,string>={OK:'成功',ERROR:'失败',CANCELLED:'已取消'};
export const statusClass:Record<string,string>={OK:'green',ERROR:'red',CANCELLED:'gray'};
export function formatDuration(us:number){if(us<1000)return us+' µs';if(us<100000)return (us/1000).toFixed(1)+' ms';if(us<1000000)return Math.round(us/1000)+' ms';return (us/1000000).toFixed(2)+' s';}
export function formatBytes(value:number){if(value<1024)return value+' B';const units=['KB','MB','GB'];let size=value/1024,index=0;while(size>=1024&&index<units.length-1){size/=1024;index++;}return size.toFixed(1)+' '+units[index];}
export function formatCount(value:number|null|undefined){return value==null?'—':value.toLocaleString('en-US');}
export function formatTime(us:number){return new Date(us/1000).toLocaleString('zh-CN');}
