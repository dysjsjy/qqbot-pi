import {defineConfig,loadEnv} from 'vite';
import {httpsUrl} from './src/content/links.ts';

export default defineConfig(({mode})=>{
 const env=loadEnv(mode,process.cwd(),'VITE_');
 const site=httpsUrl(env.VITE_SITE_URL);
 const escape=(value:string)=>value.replace(/[&<>"']/g,char=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]!));
 return {plugins:[{name:'static-site-metadata',transformIndexHtml(html:string){
  const absolute=site?`${site}/`:'';
  const tags=absolute?`<link rel="canonical" href="${escape(absolute)}" />\n<meta property="og:url" content="${escape(absolute)}" />\n<meta property="og:image" content="${escape(absolute+'images/share-card.svg')}" />\n<meta name="twitter:image" content="${escape(absolute+'images/share-card.svg')}" />`:'';
  return html.replace('</head>',`${tags}\n</head>`);
 }}]};
});