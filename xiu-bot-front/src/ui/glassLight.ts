import type {PointerEvent} from 'react';

export function updateGlassLight(event:PointerEvent<HTMLDivElement>){
 if(event.pointerType!=='mouse'||!(event.target instanceof Element))return;
 const surface=event.target.closest<HTMLElement>('.glass');
 if(!surface)return;
 const bounds=surface.getBoundingClientRect();
 surface.style.setProperty('--glass-x',`${event.clientX-bounds.left}px`);
 surface.style.setProperty('--glass-y',`${event.clientY-bounds.top}px`);
}
