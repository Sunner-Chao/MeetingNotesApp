"""支付宝 APP 支付适配器。

The adapter deliberately keeps credentials out of application code.  It reads
the runtime configuration from environment variables or the protected sandbox
JSON written by the Alipay integration flow, and only imports the SDK when a
request is actually made.
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
from pathlib import Path
from typing import Any, Mapping


class AlipayConfigurationError(RuntimeError):
    """Raised when the payment integration is not safe to use yet."""


class AlipayGatewayError(RuntimeError):
    """Raised when the SDK cannot create or process an Alipay request."""


@dataclass(frozen=True)
class AlipayConfig:
    enabled: bool
    environment: str
    app_id: str
    app_private_key: str
    alipay_public_key: str
    gateway: str
    notify_url: str
    seller_id: str
    seller_email: str
    timeout_sec: int

    @property
    def is_ready(self) -> bool:
        # The seller identity is required up front: without it every async
        # notification fails notify_business_matches and is silently dropped,
        # so a deployment missing it must fail loudly before taking money.
        return bool(
            self.enabled
            and self.app_id
            and self.app_private_key
            and self.alipay_public_key
            and (self.seller_id or self.seller_email)
        )

    @property
    def notify_ready(self) -> bool:
        return self.notify_url.startswith("https://")

    @property
    def environment_label(self) -> str:
        return "沙箱" if self.environment == "sandbox" else "生产"

    def unavailable_reason(self) -> str:
        """User-facing reason the gateway cannot be used, naming the real environment."""
        if not self.enabled:
            return f"支付宝支付尚未启用（当前环境：{self.environment_label}）"
        missing = [
            label
            for label, value in (
                ("应用 APPID", self.app_id),
                ("应用私钥", self.app_private_key),
                ("支付宝公钥", self.alipay_public_key),
                ("商家收款账号（ALIPAY_SELLER_ID）", self.seller_id or self.seller_email),
            )
            if not value
        ]
        if missing:
            return f"支付宝{self.environment_label}配置不完整，缺少：{'、'.join(missing)}"
        return f"支付宝{self.environment_label}支付当前不可用"


def _truthy(name: str, default: str = "0") -> bool:
    return os.getenv(name, default).strip().lower() not in {"", "0", "false", "no", "off"}


def _first(mapping: Mapping[str, Any], *names: str) -> str:
    for name in names:
        value = mapping.get(name)
        if value is not None and str(value).strip():
            return str(value).strip()
    return ""


def _load_sandbox_values(path: Path) -> dict[str, str]:
    if not path.is_file():
        return {}
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise AlipayConfigurationError("支付宝沙箱配置文件无法读取") from exc
    if not isinstance(payload, dict):
        raise AlipayConfigurationError("支付宝沙箱配置文件格式无效")
    apps = payload.get("appIds")
    app = apps[0] if isinstance(apps, list) and apps and isinstance(apps[0], dict) else payload
    if not isinstance(app, dict):
        return {}
    return {
        "app_id": _first(app, "appId", "app_id"),
        "app_private_key": _first(app, "appPrivatePkcsKey", "appPrivateKey", "app_private_key"),
        "alipay_public_key": _first(app, "alipayPublicKey", "alipay_public_key"),
        "seller_id": _first(app, "sellerId", "seller_id", "merchantUserId", "merchant_user_id"),
        "seller_email": _first(app, "sellerEmail", "seller_email", "merchantEmail", "merchant_email", "merchantAccount"),
    }


def load_alipay_config() -> AlipayConfig:
    environment = os.getenv("ALIPAY_ENVIRONMENT", "sandbox").strip().lower() or "sandbox"
    if environment not in {"sandbox", "production"}:
        raise AlipayConfigurationError("ALIPAY_ENVIRONMENT 只能是 sandbox 或 production")
    default_config = Path(__file__).resolve().parents[2] / ".alipay-sandbox.json"
    config_path = Path(os.getenv("ALIPAY_SANDBOX_CONFIG_PATH", str(default_config))).expanduser()
    sandbox = _load_sandbox_values(config_path) if environment == "sandbox" else {}
    app_id = os.getenv("ALIPAY_APP_ID", "").strip() or sandbox.get("app_id", "")
    private_key = os.getenv("ALIPAY_APP_PRIVATE_KEY", "").strip() or sandbox.get("app_private_key", "")
    public_key = os.getenv("ALIPAY_ALIPAY_PUBLIC_KEY", "").strip() or sandbox.get("alipay_public_key", "")
    gateway = os.getenv("ALIPAY_GATEWAY", "").strip()
    if not gateway:
        gateway = (
            "https://openapi-sandbox.dl.alipaydev.com/gateway.do"
            if environment == "sandbox"
            else "https://openapi.alipay.com/gateway.do"
        )
    timeout_raw = os.getenv("ALIPAY_TIMEOUT_SEC", "30").strip() or "30"
    try:
        timeout_sec = max(5, int(timeout_raw))
    except ValueError as exc:
        raise AlipayConfigurationError("ALIPAY_TIMEOUT_SEC 必须是整数") from exc
    return AlipayConfig(
        enabled=_truthy("ALIPAY_ENABLED"),
        environment=environment,
        app_id=app_id,
        app_private_key=private_key,
        alipay_public_key=public_key,
        gateway=gateway,
        notify_url=os.getenv("ALIPAY_NOTIFY_URL", "").strip(),
        seller_id=os.getenv("ALIPAY_SELLER_ID", "").strip() or sandbox.get("seller_id", ""),
        seller_email=os.getenv("ALIPAY_SELLER_EMAIL", "").strip() or sandbox.get("seller_email", ""),
        timeout_sec=timeout_sec,
    )


def normalize_amount(value: Any) -> str | None:
    try:
        amount = Decimal(str(value).strip())
        if not amount.is_finite():
            return None
        rounded = amount.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
    except (InvalidOperation, ValueError, TypeError):
        return None
    if rounded < 0 or rounded != amount:
        return None
    return format(rounded, ".2f")


def amount_cents(value: Any) -> int:
    normalized = normalize_amount(value)
    if normalized is None:
        raise ValueError("金额格式无效")
    return int(Decimal(normalized) * 100)


class AlipayPaymentClient:
    """Small SDK boundary used by the routes and easy to replace in tests."""

    def __init__(self, config: AlipayConfig | None = None) -> None:
        self.config = config or load_alipay_config()
        if not self.config.is_ready:
            raise AlipayConfigurationError(self.config.unavailable_reason())
        try:
            from alipay.aop.api.AlipayClientConfig import AlipayClientConfig
            from alipay.aop.api.DefaultAlipayClient import DefaultAlipayClient
        except ImportError as exc:
            raise AlipayConfigurationError("支付宝 SDK 未安装到后端运行时") from exc
        sdk_config = AlipayClientConfig()
        sdk_config.server_url = self.config.gateway
        sdk_config.app_id = self.config.app_id
        sdk_config.app_private_key = self.config.app_private_key
        sdk_config.alipay_public_key = self.config.alipay_public_key
        sdk_config.charset = "utf-8"
        sdk_config.sign_type = "RSA2"
        sdk_config.timeout = self.config.timeout_sec
        self._client = DefaultAlipayClient(alipay_client_config=sdk_config)

    def _execute_json(self, request: Any) -> dict[str, Any]:
        try:
            raw = self._client.execute(request)
            payload = json.loads(raw) if isinstance(raw, str) else raw
        except Exception as exc:
            raise AlipayGatewayError("支付宝网关请求失败") from exc
        if not isinstance(payload, dict):
            raise AlipayGatewayError("支付宝网关返回格式无效")
        return payload

    @staticmethod
    def _response(payload: Mapping[str, Any], key: str) -> dict[str, Any]:
        value = payload.get(key)
        return value if isinstance(value, dict) else {}

    def create_app_order(self, out_trade_no: str, total_amount: int, subject: str) -> str:
        from alipay.aop.api.domain.AlipayTradeAppPayModel import AlipayTradeAppPayModel
        from alipay.aop.api.request.AlipayTradeAppPayRequest import AlipayTradeAppPayRequest

        if not out_trade_no.strip() or not subject.strip() or total_amount < 1:
            raise AlipayGatewayError("支付宝 APP 支付订单参数无效")
        normalized_total = normalize_amount(Decimal(total_amount) / 100)
        if normalized_total is None:
            raise AlipayGatewayError("支付宝 APP 支付金额无效")
        request = AlipayTradeAppPayRequest()
        if self.config.notify_url:
            request.notify_url = self.config.notify_url
        model = AlipayTradeAppPayModel()
        model.out_trade_no = out_trade_no
        model.total_amount = normalized_total
        model.subject = subject
        model.product_code = "QUICK_MSECURITY_PAY"
        # An explicit expiry lets the server rotate dead trades deterministically;
        # keep this below account_service.ALIPAY_TRADE_ROTATE_AFTER_SEC.
        model.timeout_express = "30m"
        request.biz_model = model
        try:
            order_str = self._client.sdk_execute(request)
        except Exception as exc:
            raise AlipayGatewayError("支付宝 APP 支付下单失败") from exc
        if not isinstance(order_str, str) or not order_str.strip():
            raise AlipayGatewayError("支付宝未返回支付订单串")
        return order_str

    def query(self, *, out_trade_no: str = "", trade_no: str = "") -> dict[str, Any]:
        from alipay.aop.api.domain.AlipayTradeQueryModel import AlipayTradeQueryModel
        from alipay.aop.api.request.AlipayTradeQueryRequest import AlipayTradeQueryRequest

        model = AlipayTradeQueryModel()
        model.out_trade_no = out_trade_no or None
        model.trade_no = trade_no or None
        request = AlipayTradeQueryRequest()
        request.biz_model = model
        payload = self._execute_json(request)
        return self._response(payload, "alipay_trade_query_response")

    def refund(self, *, out_trade_no: str = "", trade_no: str = "", refund_amount: int, out_request_no: str) -> dict[str, Any]:
        from alipay.aop.api.domain.AlipayTradeRefundModel import AlipayTradeRefundModel
        from alipay.aop.api.request.AlipayTradeRefundRequest import AlipayTradeRefundRequest

        model = AlipayTradeRefundModel()
        model.out_trade_no = out_trade_no or None
        model.trade_no = trade_no or None
        model.refund_amount = normalize_amount(Decimal(refund_amount) / 100)
        model.out_request_no = out_request_no
        request = AlipayTradeRefundRequest()
        request.biz_model = model
        return self._response(self._execute_json(request), "alipay_trade_refund_response")

    def refund_query(self, *, out_trade_no: str = "", trade_no: str = "", out_request_no: str) -> dict[str, Any]:
        from alipay.aop.api.domain.AlipayTradeFastpayRefundQueryModel import AlipayTradeFastpayRefundQueryModel
        from alipay.aop.api.request.AlipayTradeFastpayRefundQueryRequest import AlipayTradeFastpayRefundQueryRequest

        model = AlipayTradeFastpayRefundQueryModel()
        model.out_trade_no = out_trade_no or None
        model.trade_no = trade_no or None
        model.out_request_no = out_request_no
        request = AlipayTradeFastpayRefundQueryRequest()
        request.biz_model = model
        return self._response(self._execute_json(request), "alipay_trade_fastpay_refund_query_response")

    def close(self, *, out_trade_no: str = "", trade_no: str = "") -> dict[str, Any]:
        from alipay.aop.api.domain.AlipayTradeCloseModel import AlipayTradeCloseModel
        from alipay.aop.api.request.AlipayTradeCloseRequest import AlipayTradeCloseRequest

        model = AlipayTradeCloseModel()
        model.out_trade_no = out_trade_no or None
        model.trade_no = trade_no or None
        request = AlipayTradeCloseRequest()
        request.biz_model = model
        return self._response(self._execute_json(request), "alipay_trade_close_response")


def verify_notify_signature(params: Mapping[str, Any], config: AlipayConfig | None = None) -> bool:
    """Use the installed SDK's RSA verifier; no local crypto implementation."""
    active = config or load_alipay_config()
    if not active.is_ready or not params.get("sign"):
        return False
    try:
        from alipay.aop.api.util.SignatureUtils import get_sign_content, verify_with_rsa

        unsigned = {
            str(key): value
            for key, value in params.items()
            if str(key) not in {"sign", "sign_type"}
        }
        message = get_sign_content(unsigned)
        return bool(verify_with_rsa(active.alipay_public_key, message, str(params["sign"])))
    except Exception:
        return False


def notify_business_matches(
    params: Mapping[str, Any],
    *,
    config: AlipayConfig,
    out_trade_no: str,
    amount_cents_expected: int,
) -> bool:
    if params.get("app_id") != config.app_id:
        return False
    if params.get("out_trade_no") != out_trade_no:
        return False
    if normalize_amount(params.get("total_amount")) != normalize_amount(Decimal(amount_cents_expected) / 100):
        return False
    if not (config.seller_id or config.seller_email):
        return False
    seller_id_matches = bool(config.seller_id) and params.get("seller_id") == config.seller_id
    seller_email_matches = bool(config.seller_email) and params.get("seller_email") == config.seller_email
    return seller_id_matches or seller_email_matches


def is_paid_notification(params: Mapping[str, Any]) -> bool:
    return bool(
        params.get("trade_status") in {"TRADE_SUCCESS", "TRADE_FINISHED"}
        and not params.get("out_biz_no")
        and not params.get("gmt_refund")
        and not params.get("refund_fee")
    )
