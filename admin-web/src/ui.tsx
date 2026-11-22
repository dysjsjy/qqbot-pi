/**
 * [INPUT]: 依赖 React、自绘品牌 SVG、Lucide 与工作区状态。
 * [OUTPUT]: 提供品牌标识、侧栏、通知与空状态。
 * [POS]: 业务页面共享的视觉基础，不执行网络请求。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
import { type ReactNode } from "react";
import {
  AlertCircle,
  ArrowUpRight,
  CheckCircle2,
  Database,
  Layers3,
  LogOut,
  Plus,
  X,
} from "lucide-react";
import type { WorkspaceStatus } from "./api";
import brandMark from "./qunzhi-mark.svg";
import { navigation } from "./navigation";
export type { Page } from "./types";
import type { Page } from "./types";
export function BrandMark({ className = "" }: { className?: string }) {
  return (
    <img
      src={brandMark}
      className={`brand-symbol ${className}`}
      width="48"
      height="48"
      alt=""
      aria-hidden="true"
      draggable={false}
    />
  );
}
export function Brand() {
  return (
    <a href="/" className="brand" aria-label="群知首页">
      <BrandMark className="brand-mark" />
      <strong>
        群知<span>QUNZHI</span>
      </strong>
    </a>
  );
}
export function Ribbon({ children }: { children: ReactNode }) {
  return (
    <div className="ribbon">
      <div>{children}</div>
    </div>
  );
}
export function Sidebar({
  page,
  onPage,
  username,
  status,
  onLogout,
}: {
  page: Page;
  onPage: (page: Page) => void;
  username: string;
  status: WorkspaceStatus | null;
  onLogout: () => void;
}) {
  return (
    <aside className="sidebar">
      <Brand />
      <div className="workspace-label">
        <span>我的工作空间</span>
        <Layers3 size={13} />
      </div>
      <nav aria-label="功能导航">
        {navigation.map(({ key, title, icon: Icon }) => (
          <button
            key={key}
            className={page === key ? "active" : ""}
            aria-current={page === key ? "page" : undefined}
            onClick={() => onPage(key)}
          >
            <Icon size={17} />
            <span>{title}</span>
            {page === key && <span className="nav-dot" />}
          </button>
        ))}
      </nav>
      <div className="sidebar-bottom">
        <div className="side-story">
          <BrandMark className="story-symbol" />
          <p>
            好想法，
            <br />
            值得被记住。
          </p>
          <span>MAKE ROOM FOR WHAT MATTERS.</span>
          <div className="story-lines">
            <i />
            <i />
            <i />
          </div>
        </div>
        <div className="connection-summary">
          <span className="status-dot" />
          <span>
            {status
              ? `${status.counts?.groups ?? 0} 个会话正在采集`
              : "工作区状态加载中"}
          </span>
          <ArrowUpRight size={12} />
        </div>
        {status &&
          ((status.counts?.failed_index ?? 0) > 0 ||
            (status.counts?.unknown_deliveries ?? 0) > 0) && (
            <p className="side-warning">
              {status.counts.failed_index > 0
                ? `${status.counts.failed_index} 条索引待重试`
                : `${status.counts.unknown_deliveries} 条发送待核对`}
            </p>
          )}
        <div className="user-row">
          <span className="avatar">{username.slice(0, 1).toUpperCase()}</span>
          <span>
            <strong>{username}</strong>
            <small>管理员</small>
          </span>
          <button
            className="icon-button"
            aria-label="退出登录"
            title="退出登录"
            onClick={onLogout}
          >
            <LogOut size={16} />
          </button>
        </div>
      </div>
    </aside>
  );
}
export function Banner({
  children,
  error = false,
  onClose,
}: {
  children: ReactNode;
  error?: boolean;
  onClose?: () => void;
}) {
  return (
    <div
      role={error ? "alert" : "status"}
      className={`banner ${error ? "error" : "success"}`}
    >
      {error ? <AlertCircle size={17} /> : <CheckCircle2 size={17} />}
      <span>{children}</span>
      {onClose && (
        <button className="icon-button" aria-label="关闭通知" onClick={onClose}>
          <X size={15} />
        </button>
      )}
    </div>
  );
}
export function Empty({
  text,
  title = "留一点空间，给新的发现",
  action,
}: {
  text: string;
  title?: string;
  action?: ReactNode;
}) {
  return (
    <div className="empty">
      <span className="empty-symbol">
        <Database size={22} />
        <Plus size={12} />
      </span>
      <h3>{title}</h3>
      <p>{text}</p>
      {action}
    </div>
  );
}
