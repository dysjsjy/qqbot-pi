/**
 * [INPUT]: 依赖页面类型与 Lucide 图标。
 * [OUTPUT]: 提供七页标题、说明和导航定义。
 * [POS]: App 与 Sidebar 共用的导航源。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
import {Bot,Users,Database,Clock3,ListChecks,ScrollText,Activity} from 'lucide-react';
import type {LucideIcon} from 'lucide-react';
import type {Page} from './types';

type PageDefinition={key:Page;title:string;heading:string;description:string;icon:LucideIcon};
export const navigation:PageDefinition[]=[
 {key:'groups',title:'群管理',heading:'让讨论有迹可循',description:'管理群聊接入、活跃成员与机器人管理员。',icon:Users},
 {key:'memory',title:'群记忆',heading:'找回群里的每个答案',description:'在当前群的历史讨论中检索，答案始终附有来源。',icon:Database},
 {key:'reply',title:'回复方式',heading:'让对话自然发生',description:'为每个群选择合适的聊天节奏。',icon:Bot},
 {key:'schedule',title:'定时任务',heading:'让总结准时到来',description:'为每个群独立安排昨日群聊摘要和每日活跃榜。',icon:Clock3},
 {key:'runs',title:'执行记录',heading:'每次分析，都有记录',description:'查看分析进度、采集覆盖情况与报告发送结果。',icon:ListChecks},
 {key:'traces',title:'链路分析',heading:'链路分析',description:'查看每次回复的完整调用链，以及各段的耗时、CPU、内存和 token。',icon:Activity},
 {key:'logs',title:'运行日志',heading:'看清每一步运行状态',description:'查看连接、事件处理、模型调用和发送结果，按需筛选与导出。',icon:ScrollText},
];
