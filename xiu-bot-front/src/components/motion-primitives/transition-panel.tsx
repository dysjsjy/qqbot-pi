// Adapted from ibelick/motion-primitives @ 120f64f6ca60348e251f929e9c81f11ccbe45eda.
// Source: components/core/transition-panel.tsx. See SOURCE.md and LICENSE.md.
// Original SHA-256: 1b3c67058a09a560a034b7fe52ad1c205b589a63ed80055900e8a66830db0be2
// Local changes: Stable panel identity and immediate removal of the previous accessible panel; no exit retention.
import type {ReactNode} from 'react';
import {m} from 'motion/react';
import {quickTransition,useStaticMotion} from '../../ui/motion';

export function TransitionPanel({activeId,children}:{activeId:string;children:ReactNode}){
 const reduced=useStaticMotion();
 // No AnimatePresence: old panels leave the DOM immediately, including focusable content.
 return <m.div key={activeId} data-motion="panel" initial={reduced?false:{opacity:.65}} animate={{opacity:1}} transition={reduced?{duration:0}:quickTransition}>{children}</m.div>;
}
