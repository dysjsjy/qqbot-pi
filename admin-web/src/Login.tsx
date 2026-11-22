/**
 * [INPUT]: 依赖统一登录 API、自绘品牌组件与 React 表单状态。
 * [OUTPUT]: 提供管理员登录页和真实登录错误反馈。
 * [POS]: 未认证入口；登录成功后将会话交给 useConsole。
 * [PROTOCOL]: Update this header on change, then check AGENTS.md
 */
import { useState } from "react";
import {
  ArrowRight,
  ArrowUpRight,
  BookOpen,
  Clock3,
  Eye,
  EyeOff,
  LockKeyhole,
  MessageSquare,
  ShieldCheck,
  Users,
} from "lucide-react";
import { login } from "./api";
import { Brand, BrandMark, Ribbon } from "./ui";
export function Login({ onLogin }: { onLogin: () => Promise<void> }) {
  const [user, setUser] = useState("admin");
  const [password, setPassword] = useState("");
  const [show, setShow] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  return (
    <div className="shell login-shell">
      <aside className="sidebar login-sidebar">
        <Brand />
        <div className="workspace-label">为每一次交流，留下更多</div>
        <nav aria-label="产品能力">
          <a href="#login-form">
            <Users size={17} />
            <span>群聊工作空间</span>
            <ArrowUpRight size={13} />
          </a>
          <a href="#features">
            <BookOpen size={17} />
            <span>共同记忆</span>
          </a>
          <a href="#features">
            <Clock3 size={17} />
            <span>每日洞察</span>
          </a>
        </nav>
        <div className="sidebar-bottom">
          <div className="side-story">
            <BrandMark className="story-symbol" />
            <p>
              从一次讨论，
              <br />
              到无数次启发。
            </p>
            <span>GOOD IDEAS DESERVE TO STAY.</span>
            <div className="story-lines">
              <i />
              <i />
              <i />
            </div>
          </div>
          <div className="login-side-note">
            <ShieldCheck size={15} />
            <span>专属于你的群聊知识空间</span>
          </div>
        </div>
      </aside>
      <main className="workspace-main">
        <Ribbon>
          <span>你好，群知</span> 让群里的每一个好想法，都有下文。{" "}
          <ArrowRight size={13} />
        </Ribbon>
        <section className="login-hero paper-glow">
          <p className="eyebrow">
            A LITTLE CONVERSATION. A LASTING CONNECTION.
          </p>
          <h1>
            让每一次讨论，
            <br />
            都有值得留下的答案<span className="title-period">.</span>
          </h1>
          <p className="intro-copy">
            连接你的群聊，记住共同的灵感。
            <br />
            从零散的消息，到触手可及的知识。
          </p>
          <form
            id="login-form"
            className="login-composer"
            onSubmit={async (event) => {
              event.preventDefault();
              setBusy(true);
              setError("");
              try {
                await login(user, password);
                await onLogin();
              } catch (failure) {
                setError((failure as Error).message);
              } finally {
                setBusy(false);
              }
            }}
          >
            <div className="composer-heading">
              <BrandMark className="small-mark" />
              <h2>欢迎回到群知</h2>
              <span>YOUR PRIVATE WORKSPACE</span>
            </div>
            <div className="login-fields">
              <label>
                管理员账号
                <input
                  autoComplete="username"
                  required
                  value={user}
                  onChange={(event) => setUser(event.target.value)}
                  placeholder="输入管理员账号"
                />
              </label>
              <label>
                密码
                <div className="password-field">
                  <input
                    type={show ? "text" : "password"}
                    autoComplete="current-password"
                    required
                    value={password}
                    onChange={(event) => setPassword(event.target.value)}
                    placeholder="输入登录密码"
                  />
                  <button
                    className="icon-button"
                    type="button"
                    aria-label={show ? "隐藏密码" : "显示密码"}
                    onClick={() => setShow(!show)}
                  >
                    {show ? <EyeOff size={16} /> : <Eye size={16} />}
                  </button>
                </div>
              </label>
            </div>
            {error && (
              <p role="alert" className="form-error">
                {error}
              </p>
            )}
            <div className="composer-actions">
              <span>
                <LockKeyhole size={13} /> 安全连接，安心记录
              </span>
              <button className="primary" disabled={busy}>
                {busy ? "正在打开…" : "进入管理台"}
                <ArrowUpRight size={16} />
              </button>
            </div>
          </form>
          <p className="login-caption">
            管理员凭据由服务部署配置提供 <span>·</span> 只有启用的群聊才会被记录
          </p>
        </section>
        <div className="feature-strip" id="features">
          <article>
            <MessageSquare size={19} />
            <h3>连接群聊</h3>
            <p>
              QQ 与微信，在同一个工作空间。
              <br />
              让讨论自在发生。
            </p>
          </article>
          <article>
            <BookOpen size={19} />
            <h3>记住灵感</h3>
            <p>
              用自然语言，找回曾经的答案。
              <br />
              让知识有据可循。
            </p>
          </article>
          <article>
            <Clock3 size={19} />
            <h3>每日洞察</h3>
            <p>
              在你设定的时间，送达昨日摘要。
              <br />
              让好想法继续生长。
            </p>
          </article>
        </div>
        <footer>
          <span>
            <b>群知</b> · 让知识留在讨论之后
          </span>
          <span>
            MADE FOR YOUR COMMUNITIES <BrandMark className="footer-mark" />
          </span>
        </footer>
      </main>
    </div>
  );
}
