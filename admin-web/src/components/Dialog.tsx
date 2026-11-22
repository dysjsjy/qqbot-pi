/**
 * [INPUT]: 依赖React、Motion 与浏览器原生 dialog。
 * [OUTPUT]: 提供焦点约束、忙碌关闭保护和触发器焦点恢复。
 * [POS]: 业务对话框容器；背景交互由浏览器隔离。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
import {useLayoutEffect,useRef} from 'react';
import type {ReactNode} from 'react';
import {m} from 'motion/react';
import {quickTransition,useStaticMotion} from '../ui/motion';

// Native modal dialogs provide focus containment, inert background and top-layer rendering.
// No exit retention: closing the state immediately removes all dialog actions.
export default function Dialog({label,busy=false,onClose,returnFocus,children}:{label:string;busy?:boolean;onClose:()=>void;returnFocus:HTMLElement|null;children:ReactNode}){
 const ref=useRef<HTMLDialogElement>(null);const reduced=useStaticMotion();
 useLayoutEffect(()=>{
  const dialog=ref.current!;const previous=returnFocus??(document.activeElement instanceof HTMLElement?document.activeElement:null);
  dialog.showModal();
  return()=>{dialog.close();queueMicrotask(()=>{
   if(document.querySelector('dialog[open]'))return;
   const target=previous?.isConnected&&!previous.matches(':disabled')?previous:document.querySelector<HTMLElement>('.heading h1');
   target?.focus({preventScroll:true});
  });};
 },[]);
 return <m.dialog ref={ref} className="modal" aria-label={label} aria-modal="true" aria-busy={busy} onCancel={event=>{event.preventDefault();if(!busy)onClose();}} onKeyDown={event=>{
  if(event.key!=='Tab')return;
  const controls=Array.from(event.currentTarget.querySelectorAll<HTMLElement>('button,input,select,textarea,a[href],[tabindex]')).filter(node=>!node.matches(':disabled')&&node.tabIndex>=0&&node.getClientRects().length>0);
  const first=controls[0],last=controls.at(-1);
  if(!first){event.preventDefault();event.currentTarget.focus();}
  else if(event.shiftKey&&document.activeElement===first){event.preventDefault();last?.focus();}
  else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first.focus();}
 }} initial={reduced?false:{opacity:.7}} animate={{opacity:1}} transition={reduced?{duration:0}:quickTransition}>{children}</m.dialog>;
}
