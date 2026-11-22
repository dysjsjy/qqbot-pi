import type {ReactNode} from 'react';
import {useSyncExternalStore} from 'react';
import {LazyMotion,MotionConfig,domMin} from 'motion/react';

// Measure the small decorative background locally; omit drag/gesture/layout engines.
export const quickTransition={duration:.16,ease:'easeOut' as const};
export function MotionProvider({children}:{children:ReactNode}){
 const reduced=useStaticMotion();
 return <LazyMotion features={domMin} strict><MotionConfig reducedMotion={reduced?'always':'never'}>{children}</MotionConfig></LazyMotion>;
}
const preference=typeof window==='undefined'?null:window.matchMedia('(prefers-reduced-motion: reduce)');
const subscribe=(listener:()=>void)=>{preference?.addEventListener('change',listener);return()=>preference?.removeEventListener('change',listener);};
export function useStaticMotion(){
 // Subscribe for live OS changes, and read a fresh snapshot during client startup.
 return useSyncExternalStore(subscribe,()=>typeof window!=='undefined'&&window.matchMedia('(prefers-reduced-motion: reduce)').matches,()=>false);
}
