// Adapted from ibelick/motion-primitives @ 120f64f6ca60348e251f929e9c81f11ccbe45eda.
// Source: components/core/in-view.tsx. See SOURCE.md and LICENSE.md.
// Original SHA-256: 8504e2e6fd2126619be6170132c8557fb2070a6a5225587cd2813f1f83b1e468
// Local changes: Once-only reveal, readable initial content, immediate focus and live reduced motion.
import {useEffect,useRef,useState} from 'react';
import type {ReactNode} from 'react';
import {m,useInView} from 'motion/react';
import {useStaticMotion} from '../../ui/motion';

export function InView({children,className}:{children:ReactNode;className?:string}){
 const ref=useRef<HTMLDivElement>(null);const [focused,setFocused]=useState(false);
 useEffect(()=>{
  const revealAnchor=()=>{const section=ref.current?.closest('section[id]');if(section&&window.location.hash==='#'+section.id)setFocused(true);};
  revealAnchor();window.addEventListener('hashchange',revealAnchor);return()=>window.removeEventListener('hashchange',revealAnchor);
 },[]);
 const visible=useInView(ref,{once:true,margin:'0px 0px 40px 0px'});const reduced=useStaticMotion();
 // Content stays readable even before intersection; focus/reduced motion is immediate.
 const instant=reduced||focused;
 return <m.div ref={ref} className={className} data-motion="in-view" data-reduced={reduced} onFocusCapture={()=>setFocused(true)} initial={false} animate={{opacity:instant||visible?1:.72}} transition={{duration:instant?0:.2}}>{children}</m.div>;
}
