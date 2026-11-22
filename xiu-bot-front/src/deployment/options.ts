export const deployments=[
 {key:'sqlite',label:'Docker · SQLite',config:'.env.example',command:'docker compose up -d --build'},
 {key:'local',label:'Windows 本地',config:'.env.qq-official.example',command:'powershell -NoProfile -ExecutionPolicy Bypass -File .\\scripts\\start.ps1 -Build'},
] as const;
export type DeploymentKey=typeof deployments[number]['key'];
