// Adapted from ibelick/motion-primitives @ 120f64f6ca60348e251f929e9c81f11ccbe45eda.
// Source: components/core/animated-background.tsx. See SOURCE.md and LICENSE.md.
// Original SHA-256: fa592713c408eaf58b21976c20737d84239c42a0b7e47cf9e7843121b1c0852c
// Local changes: Controlled selection, native events, local geometry, ordinary CSS, domMin and live reduced motion.
import {Children,cloneElement,isValidElement,useLayoutEffect,useRef,useState} from 'react';
import type {HTMLAttributes,ReactNode} from 'react';
import {m} from 'motion/react';
import {quickTransition,useStaticMotion} from '../../ui/motion';

type ItemProps=HTMLAttributes<HTMLElement>&{'data-id':string;children?:ReactNode};
export function AnimatedBackground({children,value,className='selection-background'}:{children:ReactNode;value:string;className?:string}){
 const ref=useRef<HTMLDivElement>(null);const reduced=useStaticMotion();
 const [box,setBox]=useState<{left:number;top:number;width:number;height:number}|null>(null);
 useLayoutEffect(()=>{
  const group=ref.current!;
  const measure=()=>{
   const active=Array.from(group.querySelectorAll<HTMLElement>('[data-id]')).find(item=>item.dataset.id===value);
   if(!active){setBox(null);return;}
   const outer=group.getBoundingClientRect(),inner=active.getBoundingClientRect();
   const next={left:inner.left-outer.left,top:inner.top-outer.top,width:inner.width,height:inner.height};
   setBox(previous=>previous&&Object.keys(next).every(key=>previous[key as keyof typeof next]===next[key as keyof typeof next])?previous:next);
  };
  measure();const observer=new ResizeObserver(measure);observer.observe(group);
  for(const item of group.children)observer.observe(item);
  return()=>observer.disconnect();
 },[value]);
 return <div className="motion-background-group" ref={ref}>
  {box&&(reduced?<span aria-hidden="true" className={className} style={box}/>:<m.span aria-hidden="true" className={className} initial={false} animate={box} transition={quickTransition}/>)}
  {Children.map(children,child=>isValidElement<ItemProps>(child)?cloneElement(child,{className:[child.props.className,'motion-choice'].filter(Boolean).join(' ')},<span className="motion-choice-label">{child.props.children}</span>):child)}
 </div>;
}
