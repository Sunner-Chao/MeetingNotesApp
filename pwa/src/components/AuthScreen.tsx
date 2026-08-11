import { useState, type FormEvent } from "react";
import { ArrowRight, Eye, EyeOff, Server } from "lucide-react";
import type { RuntimeConfig } from "../types";
import { BrandMark } from "./BrandMark";

interface AuthScreenProps {
  config: RuntimeConfig;
  busy: boolean;
  onAuthenticate: (config: RuntimeConfig, username: string, password: string, register: boolean) => Promise<void>;
}

export function AuthScreen({ config, busy, onAuthenticate }: AuthScreenProps) {
  const [register, setRegister] = useState(false);
  const [apiBase, setApiBase] = useState(config.apiBase);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [validationError, setValidationError] = useState("");

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const normalizedUsername = username.trim();
    if (normalizedUsername.length < 3) {
      setValidationError("用户名至少需要 3 个字符");
      return;
    }
    if (password.length < 8) {
      setValidationError("密码至少需要 8 个字符");
      return;
    }
    setValidationError("");
    await onAuthenticate({ ...config, apiBase: apiBase.trim() }, normalizedUsername, password, register);
  };

  return (
    <main className="auth-page">
      <section className="auth-panel" aria-labelledby="auth-title">
        <div className="auth-brand">
          <BrandMark size={84} />
          <h1 id="auth-title">智悟本</h1>
          <p>智慧&nbsp;&nbsp;领悟&nbsp;&nbsp;本源</p>
          <strong>智能体 · 小Woo</strong>
        </div>

        <div className="segmented auth-mode" role="group" aria-label="账户操作">
          <button className={!register ? "active" : ""} type="button" onClick={() => setRegister(false)}>登录</button>
          <button className={register ? "active" : ""} type="button" onClick={() => setRegister(true)}>注册</button>
        </div>

        <form className="auth-form" onSubmit={submit} noValidate>
          <label>
            <span>用户名</span>
            <input
              value={username}
              onChange={(event) => { setUsername(event.target.value); setValidationError(""); }}
              autoComplete="username"
              minLength={3}
              maxLength={32}
              required
            />
          </label>
          <label>
            <span>密码</span>
            <div className="password-field">
              <input
                type={showPassword ? "text" : "password"}
                value={password}
                onChange={(event) => { setPassword(event.target.value); setValidationError(""); }}
                autoComplete={register ? "new-password" : "current-password"}
                minLength={8}
                maxLength={128}
                required
              />
              <button type="button" className="icon-button" onClick={() => setShowPassword((value) => !value)} title={showPassword ? "隐藏密码" : "显示密码"}>
                {showPassword ? <EyeOff /> : <Eye />}
              </button>
            </div>
          </label>

          {validationError && <p className="field-error" role="alert">{validationError}</p>}

          <details className="service-details">
            <summary><Server /> 服务地址</summary>
            <label>
              <span>账户服务</span>
              <input
                type="url"
                value={apiBase}
                onChange={(event) => setApiBase(event.target.value)}
                placeholder="留空使用当前服务器"
                inputMode="url"
              />
            </label>
          </details>

          <button className="primary-button auth-submit" type="submit" disabled={busy || !username.trim() || !password}>
            <span>{busy ? "正在连接" : register ? "创建账户" : "进入智悟本"}</span>
            <ArrowRight />
          </button>
        </form>
      </section>
    </main>
  );
}
