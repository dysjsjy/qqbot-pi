/**
 * [INPUT]: 依赖 React 根节点、motion 运行边界与 App。
 * [OUTPUT]: 挂载控制台并在热更新时复用根节点。
 * [POS]: 浏览器入口；会话与业务导航由 App 提供。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
import React from 'react';
import {createRoot,type Root} from 'react-dom/client';
import {MotionProvider} from './ui/motion';
import App from './App';
import './style.css';
import './console-features.css';

const root=(import.meta.hot?.data.root as Root|undefined)??createRoot(document.getElementById('root')!);
if(import.meta.hot)import.meta.hot.data.root=root;
root.render(<React.StrictMode><MotionProvider><App/></MotionProvider></React.StrictMode>);
