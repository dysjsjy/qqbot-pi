import {useEffect,useRef,useState} from 'react';
import {deployments} from './options';
import type {DeploymentKey} from './options';

export function useDeployment(){
 const[key,setKey]=useState<DeploymentKey>('sqlite'),[copyStatus,setCopyStatus]=useState(''),[copying,setCopying]=useState(false);
 const version=useRef(0);
 const selected=deployments.find(option=>option.key===key)!;
 useEffect(()=>()=>{version.current++;},[]);
 const select=(next:DeploymentKey)=>{version.current++;setKey(next);setCopyStatus('');setCopying(false);};
 const copy=async()=>{
  const request=++version.current;setCopying(true);setCopyStatus('');
  try{await navigator.clipboard.writeText(selected.command);if(request===version.current)setCopyStatus('启动命令已复制');}
  catch{if(request===version.current)setCopyStatus('浏览器不允许复制，请选中命令手动复制');}
  finally{if(request===version.current)setCopying(false);}
 };
 return{selected,select,copy,copying,copyStatus};
}
