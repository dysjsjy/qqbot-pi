// Adapted from ibelick/motion-primitives @ 120f64f6ca60348e251f929e9c81f11ccbe45eda.
// Source: components/core/animated-group.tsx. See SOURCE.md and LICENSE.md.
// Original SHA-256: 0c162bbdd3c332e7aa113cd7ddfa7ac36de3184866cb82ad9140525561e08c35
// Local changes: Stable keys, React 19 types, readable opacity, bounded stagger and live reduced motion.
import {Children,isValidElement} from 'react';
import type {ReactNode} from 'react';
import {m} from 'motion/react';
import {useStaticMotion} from '../../ui/motion';

export function AnimatedGroup({children,className}:{children:ReactNode;className?:string}){
 const reduced=useStaticMotion();
 return <m.div className={className} data-motion="group" initial={reduced?false:'hidden'} animate="visible" variants={{visible:{transition:{staggerChildren:reduced?0:.03}}}}>
  {Children.map(children,child=><m.div className="motion-group-item" key={isValidElement(child)?child.key:undefined} variants={{hidden:{opacity:.6},visible:{opacity:1,transition:{duration:reduced?0:.18}}}}>{child}</m.div>)}
 </m.div>;
}
