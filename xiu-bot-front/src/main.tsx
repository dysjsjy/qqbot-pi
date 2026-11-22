import {MotionProvider} from './ui/motion';
import {StrictMode} from 'react';
import {createRoot} from 'react-dom/client';
import App from './App';
import './style.css';

createRoot(document.getElementById('root')!).render(<StrictMode><MotionProvider><App/></MotionProvider></StrictMode>);
