import {Database} from 'lucide-react';

export default function Empty({text}:{text:string}){return <div className="empty"><Database size={30}/><h3>还没有内容</h3><p>{text}</p></div>;}
