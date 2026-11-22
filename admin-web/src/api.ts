/**
 * [INPUT]: 依赖同源管理 API、浏览器会话与 CSRF 令牌。
 * [OUTPUT]: 提供统一请求、登录、群与工作区接口类型。
 * [POS]: 前端唯一 HTTP 边界；业务页面不直接执行 fetch。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
export type { Group } from "./types";
import type { QqGateway } from "./types";
export type WorkspaceStatus = {
  counts: {
    registered_groups: number;
    groups: number;
    total_messages: number;
    indexed_memories: number;
    pending_index: number;
    failed_index: number;
    active_runs: number;
    unknown_deliveries: number;
    scheduled_groups: number;
  };
  gateways: { QQ: QqGateway & { enabled: boolean; configured: boolean }; WEIXIN: { enabled: boolean; configured: boolean } };
  ragEnabled: boolean;
  database: string;
  timezone: string;
  updatedAt: string;
  preview?: boolean;
};
let csrf = "";
export class ApiError extends Error {
  constructor(message: string, public readonly status: number) {
    super(message);
    this.name = "ApiError";
  }
}
export async function refreshCsrf() {
  const r = await fetch("/api/admin/csrf", { credentials: "same-origin" });
  if (!r.ok) throw new Error("无法建立安全会话");
  csrf = (await r.json()).token;
}
export async function api<T = any>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const method = options.method ?? "GET";
  if (method !== "GET" && !csrf) await refreshCsrf();
  const response = await fetch("/api/admin" + path, {
    ...options,
    credentials: "same-origin",
    headers: {
      ...(options.body ? { "Content-Type": "application/json" } : {}),
      ...(method !== "GET" ? { "X-CSRF-TOKEN": csrf } : {}),
      ...options.headers,
    },
  });
  if (response.status === 401) {
    csrf = "";
    window.dispatchEvent(new Event("session-expired"));
    throw new ApiError("登录已失效，请重新登录", 401);
  }
  if (!response.ok) {
    let detail = "";
    try {
      detail = (await response.json()).message ?? "";
    } catch {}
    throw new ApiError(
      detail ||
        ({
          "403": "无权执行此操作",
          "409": "操作冲突，请刷新后重试",
          "429": "请求过于频繁，请稍后重试",
        }[String(response.status)] ??
          `请求失败 (${response.status})`),
      response.status,
    );
  }
  return response.status === 204 ||
    response.headers.get("content-length") === "0"
    ? (undefined as T)
    : response.text().then((t) => (t ? JSON.parse(t) : (undefined as T)));
}
export async function login(username: string, password: string) {
  await refreshCsrf();
  const form = new URLSearchParams({ username, password });
  const r = await fetch("/api/admin/login", {
    method: "POST",
    credentials: "same-origin",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
      "X-CSRF-TOKEN": csrf,
    },
    body: form,
  });
  if (!r.ok)
    throw new Error(
      r.status === 429 ? "登录过于频繁，请稍后重试" : "用户名或密码不正确",
    );
  await refreshCsrf();
}
