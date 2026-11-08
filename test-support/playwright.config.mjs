import {defineConfig} from '@playwright/test';
export default defineConfig({
  testDir:'.',testMatch:'admin.spec.mjs',workers:1,
  use:{baseURL:'http://127.0.0.1:5173',headless:true,viewport:{width:1440,height:1000},launchOptions:process.env.PW_CHROMIUM_EXECUTABLE?{executablePath:process.env.PW_CHROMIUM_EXECUTABLE}:{}},
  webServer:{command:'npm.cmd run dev -- --port 5173 --strictPort',cwd:'../admin-web',url:'http://127.0.0.1:5173',reuseExistingServer:true},
  reporter:[['list'],['html',{open:'never'}]],
});
