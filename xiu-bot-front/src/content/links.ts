export type PublicConfig = {VITE_REPOSITORY_URL?: string; VITE_DOCS_BASE_URL?: string; VITE_SITE_URL?: string};
export function httpsUrl(value: string | undefined): string {
 if (!value?.trim()) return '';
 try {
  const url = new URL(value.trim());
  if (url.protocol !== 'https:' || !url.hostname || url.username || url.password || url.href.includes('?') || url.href.includes('#')) return '';
  return url.href.replace(/\/+$/, '');
 } catch { return ''; }
}
export function repositoryUrl(value: string | undefined): string {
 const normalized = httpsUrl(value);
 if (!normalized) return '';
 const url = new URL(normalized);
 if (url.hostname !== 'github.com' || url.port) return '';
 const parts = url.pathname.replace(/\/+$/, '').replace(/\.git$/, '').slice(1).split('/');
 if (parts.length !== 2 || !parts.every(part => /^[a-zA-Z0-9_.-]+$/.test(part) && !['.', '..'].includes(part))) return '';
 return `https://github.com/${parts.join('/')}`;
}
export function resolveLinks(config: PublicConfig) {
 const repository = repositoryUrl(config.VITE_REPOSITORY_URL), docs = httpsUrl(config.VITE_DOCS_BASE_URL);
 return {repository,issues:repository ? `${repository}/issues` : '',site:httpsUrl(config.VITE_SITE_URL),doc:(path:string)=>docs ? `${docs}/${path}` : ''};
}
