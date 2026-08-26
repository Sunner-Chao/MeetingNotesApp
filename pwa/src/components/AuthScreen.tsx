import { useEffect, useState, type FormEvent } from "react";
import { ArrowLeft, ArrowRight, Eye, EyeOff, Mail, Server, Smartphone } from "lucide-react";
import type { AuthCodeDelivery, RuntimeConfig } from "../types";
import { BrandMark } from "./BrandMark";

interface AuthScreenProps {
  config: RuntimeConfig;
  busy: boolean;
  onLogin: (config: RuntimeConfig, username: string, password: string) => Promise<void>;
  onRequestRegistrationCode: (config: RuntimeConfig, email: string) => Promise<AuthCodeDelivery | undefined>;
  onRegister: (config: RuntimeConfig, username: string, email: string, password: string, code: string, referralCode?: string) => Promise<void>;
}

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export function AuthScreen({ config, busy, onLogin, onRequestRegistrationCode, onRegister }: AuthScreenProps) {
  const [mode, setMode] = useState<"login" | "register">("login");
  const [registrationStep, setRegistrationStep] = useState<"details" | "code">("details");
  const [apiBase, setApiBase] = useState(config.apiBase);
  const [username, setUsername] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [verificationCode, setVerificationCode] = useState("");
  const [referralCode, setReferralCode] = useState("");
  const [delivery, setDelivery] = useState<AuthCodeDelivery>();
  const [resendSeconds, setResendSeconds] = useState(0);
  const [showPassword, setShowPassword] = useState(false);
  const [validationError, setValidationError] = useState("");

  useEffect(() => {
    if (resendSeconds <= 0) return;
    const timer = window.setTimeout(() => setResendSeconds((current) => Math.max(0, current - 1)), 1000);
    return () => window.clearTimeout(timer);
  }, [resendSeconds]);

  const runtimeConfig = (): RuntimeConfig => ({ ...config, apiBase: apiBase.trim() });

  const validateCredentials = () => {
    if (!username.trim()) return "请输入用户名";
    if (password.length < 8 || password.length > 128) return "密码需要 8 至 128 个字符";
    return "";
  };

  const requestCode = async () => {
    const credentialError = validateCredentials();
    if (credentialError) {
      setValidationError(credentialError);
      return;
    }
    const normalizedEmail = email.trim().toLowerCase();
    if (!EMAIL_PATTERN.test(normalizedEmail)) {
      setValidationError("请输入有效的邮箱地址");
      return;
    }
    setValidationError("");
    const result = await onRequestRegistrationCode(runtimeConfig(), normalizedEmail);
    if (!result) return;
    setDelivery(result);
    setEmail(normalizedEmail);
    setVerificationCode("");
    setResendSeconds(Math.max(0, result.retry_after));
    setRegistrationStep("code");
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const normalizedUsername = username.trim();
    const credentialError = validateCredentials();
    if (credentialError) {
      setValidationError(credentialError);
      return;
    }

    if (mode === "login") {
      setValidationError("");
      await onLogin(runtimeConfig(), normalizedUsername, password);
      return;
    }

    if (registrationStep === "details") {
      await requestCode();
      return;
    }

    if (!/^\d{6}$/.test(verificationCode)) {
      setValidationError("请输入 6 位邮箱验证码");
      return;
    }
    setValidationError("");
    await onRegister(runtimeConfig(), normalizedUsername, email, password, verificationCode, referralCode);
  };

  const switchMode = (nextMode: "login" | "register") => {
    setMode(nextMode);
    setValidationError("");
  };

  const submitLabel = busy
    ? mode === "login" ? "正在登录" : registrationStep === "details" ? "正在发送" : "正在注册"
    : mode === "login" ? "进入智悟本" : registrationStep === "details" ? "发送邮箱验证码" : "验证并创建账户";

  const submitDisabled = busy || !username.trim() || !password
    || (mode === "register" && registrationStep === "details" && !email.trim())
    || (mode === "register" && registrationStep === "code" && verificationCode.length !== 6);

  return (
    <main className="auth-page">
      <section className="auth-panel" aria-labelledby="auth-title">
        <div className="auth-brand">
          <BrandMark size={84} />
          <h1 id="auth-title">智悟本</h1>
          <p>智慧&nbsp;&nbsp;领悟&nbsp;&nbsp;本源</p>
          <strong>智悟本</strong>
        </div>

        <div className="segmented auth-mode" role="group" aria-label="账户操作">
          <button className={mode === "login" ? "active" : ""} type="button" disabled={busy} onClick={() => switchMode("login")}>登录</button>
          <button className={mode === "register" ? "active" : ""} type="button" disabled={busy} onClick={() => switchMode("register")}>注册</button>
        </div>

        {mode === "register" && (
          <div className="segmented registration-methods" role="tablist" aria-label="注册方式">
            <button className="active" type="button" role="tab" aria-selected="true">
              <Mail /><span>邮箱注册</span>
            </button>
            <button type="button" role="tab" aria-selected="false" disabled title="手机号注册暂未开放">
              <Smartphone /><span>手机号注册</span><small>暂未开放</small>
            </button>
          </div>
        )}

        <form className="auth-form" onSubmit={submit} noValidate>
          {(mode === "login" || registrationStep === "details") ? (
            <>
              <label>
                <span>{mode === "login" ? "邮箱/用户名" : "用户名"}</span>
                <input
                  value={username}
                  onChange={(event) => { setUsername(event.target.value); setValidationError(""); }}
                  autoComplete="username"
                  required
                />
              </label>
              {mode === "register" && (
                <label>
                  <span>邮箱</span>
                  <input
                    type="email"
                    value={email}
                    onChange={(event) => { setEmail(event.target.value); setValidationError(""); }}
                    autoComplete="email"
                    inputMode="email"
                    required
                  />
                </label>
              )}
              {mode === "register" && (
                <label>
                  <span>邀请码（可选）</span>
                  <input value={referralCode} onChange={(event) => setReferralCode(event.target.value.toUpperCase())} placeholder="填写好友邀请码，双方各得 100 积分" autoComplete="off" />
                  <small className="field-hint">没有邀请码也可以正常注册</small>
                </label>
              )}
              <label>
                <span>密码</span>
                <div className="password-field">
                  <input
                    type={showPassword ? "text" : "password"}
                    value={password}
                    onChange={(event) => { setPassword(event.target.value); setValidationError(""); }}
                    autoComplete={mode === "register" ? "new-password" : "current-password"}
                    minLength={8}
                    maxLength={128}
                    required
                  />
                  <button type="button" className="icon-button" onClick={() => setShowPassword((value) => !value)} title={showPassword ? "隐藏密码" : "显示密码"}>
                    {showPassword ? <EyeOff /> : <Eye />}
                  </button>
                </div>
              </label>
            </>
          ) : (
            <div className="registration-verification">
              <div className="verification-destination">
                <Mail />
                <span><small>验证码已发送</small><strong>{delivery?.masked_identifier || email}</strong></span>
              </div>
              <label>
                <span>邮箱验证码</span>
                <input
                  className="verification-code-input"
                  value={verificationCode}
                  onChange={(event) => { setVerificationCode(event.target.value.replace(/\D/g, "").slice(0, 6)); setValidationError(""); }}
                  autoComplete="one-time-code"
                  inputMode="numeric"
                  pattern="[0-9]{6}"
                  maxLength={6}
                  placeholder="6 位验证码"
                  required
                />
              </label>
              <div className="verification-actions">
                <button type="button" className="text-button" disabled={busy} onClick={() => { setRegistrationStep("details"); setValidationError(""); }}>
                  <ArrowLeft /> 修改账户信息
                </button>
                <button type="button" className="text-button" disabled={busy || resendSeconds > 0} onClick={() => void requestCode()}>
                  {resendSeconds > 0 ? `${resendSeconds} 秒后重发` : "重新发送"}
                </button>
              </div>
            </div>
          )}

          {validationError && <p className="field-error" role="alert">{validationError}</p>}

          {(mode === "login" || registrationStep === "details") && (
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
          )}

          <button className="primary-button auth-submit" type="submit" disabled={submitDisabled}>
            <span>{submitLabel}</span>
            <ArrowRight />
          </button>
        </form>
      </section>
    </main>
  );
}
