"""
Revolut Auth Sidecar
--------------------
Owns a logged-in Revolut web session (app.revolut.com, "personal" surface —
Phase 1 of docs/features/revolut-sidecar.md) and exposes a minimal HTTP API
consumed by the Spring backend.

Auth model (see design doc §3 for the recon that established this):
  - The access/session token lives in an httpOnly cookie — never JS-visible.
  - Every request also needs `x-device-id` (= the `revo_device_id` cookie,
    NOT httpOnly, a stable per-device UUID) + `x-browser-application:
    WEB_CLIENT` + `x-client-version: 100.0`. Cookie alone -> 401.
  - Access tokens live ~4 min; `PUT /api/retail/token` mints a fresh one
    using the httpOnly refresh cookie.
  - Login itself (phone + passcode + mobile approval) is not automatable
    and is deliberately NOT attempted here — see /enrolment/start.

Two flows:
  POST /enrolment/start  → headful, human-in-the-loop login capture.
                           Returns { storageState } once the user finishes
                           logging in by hand in the visible browser window.
  POST /accounts         → headless recurring sync. Takes a previously
                           captured storageState, refreshes the token, and
                           harvests accounts/transactions.

All requests replay at the Playwright network layer (real fetch() calls
made from inside the page, below the app's JS) rather than reconstructing
headers out-of-band — see design doc §3.4 for why hooking window.fetch or
rebuilding headers from scratch does not work here.
"""

import json
import logging
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Tuple, Union

from fastapi import FastAPI
from fastapi.responses import JSONResponse
from playwright.async_api import Page, async_playwright
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("revolut-auth")

app = FastAPI()

APP_URL = "https://app.revolut.com/"
HOME_URL = "https://app.revolut.com/home"
UA = ("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36")

# Generous window for the human to complete phone + passcode + mobile
# approval / device enrolment by hand (see capture_login.py, from which this
# value is inherited).
ENROLMENT_WAIT_S = 420
ENROLMENT_POLL_MS = 3000

# Hard cap on transaction pages fetched per pocket — a safety net against an
# unexpected pagination shape looping forever, not a expected normal path.
MAX_TRANSACTION_PAGES = 20
TRANSACTION_WINDOW_DAYS = 90


# ─── Helpers: storageState / device id ───────────────────────────────────────

def _parse_storage_state(value: Union[str, Dict[str, Any]]) -> Dict[str, Any]:
    if isinstance(value, str):
        return json.loads(value)
    return value


def _extract_device_id(storage_state: Dict[str, Any]) -> Optional[str]:
    """`revo_device_id` is a plain (non-httpOnly) cookie — present in
    storageState like any other and readable from document.cookie in-page.
    We read it from the Python side so we don't depend on the exact cookie
    domain/path lining up with whatever page happens to be loaded."""
    for cookie in storage_state.get("cookies", []):
        if cookie.get("name") == "revo_device_id":
            return cookie.get("value")
    return None


async def _read_device_id_from_page(page: Page) -> Optional[str]:
    """Fallback for the enrolment flow, where we don't have a storageState
    yet — read the cookie straight from the live page once it's been set."""
    try:
        return await page.evaluate(
            """() => {
                const c = document.cookie.split(';').map(s => s.trim())
                          .find(s => s.startsWith('revo_device_id='));
                return c ? c.split('=').slice(1).join('=') : null;
            }"""
        )
    except Exception:  # noqa: BLE001
        return None


def _pick(d: Optional[Dict[str, Any]], *keys: str, default: Any = None) -> Any:
    """Try several candidate key names in order. The exact Revolut JSON field
    names below are best-effort from the design-doc recon (endpoint paths and
    high-level semantics were confirmed live; the precise field spelling of
    nested objects was not dumped) — this keeps the mapping tolerant instead
    of silently defaulting on a near-miss key."""
    if not isinstance(d, dict):
        return default
    for k in keys:
        if k in d and d[k] is not None:
            return d[k]
    return default


def _to_number(value: Any) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return 0.0


def _ms_to_date(ms: Any) -> str:
    try:
        return datetime.fromtimestamp(float(ms) / 1000, tz=timezone.utc).strftime("%Y-%m-%d")
    except (TypeError, ValueError, OSError):
        return ""


def session_expired() -> JSONResponse:
    """Exact-shape 401 the backend expects. NOTE: we deliberately do not use
    FastAPI's HTTPException here — it wraps `detail` in an envelope
    (`{"detail": {...}}`), which would break the flat `{"error": ...}`
    contract the Java side parses. Returning a JSONResponse directly gives
    us the exact body."""
    return JSONResponse(status_code=401, content={"error": "SESSION_EXPIRED"})


# ─── Helpers: authenticated fetch from inside the page ──────────────────────

_JS_FETCH = """
async ({ path, method, deviceId, params }) => {
    try {
        const url = new URL(path, window.location.origin);
        if (params) {
            for (const [k, v] of Object.entries(params)) {
                if (v !== undefined && v !== null) url.searchParams.set(k, v);
            }
        }
        const r = await fetch(url.toString(), {
            method,
            credentials: 'include',
            headers: {
                'x-device-id': deviceId || '',
                'x-browser-application': 'WEB_CLIENT',
                'x-client-version': '100.0',
            },
        });
        let data = null;
        try { data = await r.json(); } catch (e) { data = null; }
        return { status: r.status, data };
    } catch (e) {
        return { status: 0, data: null, error: String(e) };
    }
}
"""


async def api_call(page: Page, path: str, device_id: Optional[str],
                    method: str = "GET", params: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    """Authenticated fetch executed in-page (below the app's JS, per design
    doc §3.4). Never raises — endpoint failures are reported via status=0/4xx
    so callers can skip-and-continue per the sidecar's defensive-harvest
    contract."""
    try:
        return await page.evaluate(_JS_FETCH, {"path": path, "method": method,
                                                 "deviceId": device_id, "params": params or {}})
    except Exception as e:  # noqa: BLE001
        log.warning("api_call failed for %s %s: %s", method, path, e)
        return {"status": 0, "data": None}


async def refresh_access_token(page: Page, device_id: Optional[str]) -> int:
    resp = await api_call(page, "/api/retail/token", device_id, method="PUT")
    return resp.get("status", 0)


# ─── Harvest: wallets/pockets, money boxes, IBANs, transactions ─────────────

async def _fetch_wallets(page: Page, device_id: Optional[str]) -> List[Tuple[Optional[str], List[Dict[str, Any]]]]:
    """Returns (walletId, pockets[]) pairs. Tries the singular "current
    wallet" endpoint first (the common single-wallet case); falls back to the
    plural listing for accounts with more than one wallet."""
    resp = await api_call(page, "/api/retail/user/current/wallet", device_id)
    if resp.get("status") == 200 and isinstance(resp.get("data"), dict) and resp["data"].get("pockets"):
        w = resp["data"]
        return [(_pick(w, "id", "walletId"), w.get("pockets") or [])]

    resp = await api_call(page, "/api/retail/wallets", device_id)
    out: List[Tuple[Optional[str], List[Dict[str, Any]]]] = []
    if resp.get("status") == 200 and resp.get("data"):
        data = resp["data"]
        wallets = data if isinstance(data, list) else data.get("wallets", [])
        for w in wallets or []:
            out.append((_pick(w, "id", "walletId"), w.get("pockets") or []))
    if not out:
        log.warning("no wallet found (both /user/current/wallet and /wallets came back empty)")
    return out


async def _fetch_money_boxes(page: Page, device_id: Optional[str]) -> List[Dict[str, Any]]:
    seen_ids: set = set()
    boxes: List[Dict[str, Any]] = []
    for params in ({"accountType": "PERSONAL"}, {"accountType": "PERSONAL_JOINT"}, None):
        resp = await api_call(page, "/api/retail/user/current/money-boxes", device_id, params=params)
        if resp.get("status") != 200 or not resp.get("data"):
            continue
        data = resp["data"]
        items = data if isinstance(data, list) else data.get("moneyBoxes", [])
        for mb in items or []:
            mb_id = _pick(mb, "id")
            if not mb_id or mb_id in seen_ids:
                continue
            seen_ids.add(mb_id)
            boxes.append(mb)
    return boxes


async def _fetch_iban(page: Page, device_id: Optional[str], wallet_id: str, currency: str) -> Optional[str]:
    resp = await api_call(page, "/api/retail/bank-accounts/account-details", device_id, params={
        "currency": currency, "pocketType": "CURRENT", "locale": "fr-FR", "walletId": wallet_id,
    })
    if resp.get("status") == 200 and isinstance(resp.get("data"), dict):
        return _pick(resp["data"], "iban", "IBAN")
    return None


async def _fetch_transactions(page: Page, device_id: Optional[str], pocket_id: str) -> List[Dict[str, Any]]:
    now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
    cutoff_ms = now_ms - TRANSACTION_WINDOW_DAYS * 24 * 3600 * 1000
    out: List[Dict[str, Any]] = []
    seen_ids: set = set()
    cursor = now_ms

    for _ in range(MAX_TRANSACTION_PAGES):
        resp = await api_call(page, "/api/retail/user/current/transactions/last", device_id,
                               params={"internalPocketId": pocket_id, "to": cursor})
        if resp.get("status") != 200 or not resp.get("data"):
            break
        data = resp["data"]
        batch = data if isinstance(data, list) else data.get("transactions", [])
        if not batch:
            break

        new_count = 0
        oldest_ts = cursor
        for t in batch:
            tid = _pick(t, "id")
            if not tid or tid in seen_ids:
                continue
            seen_ids.add(tid)
            new_count += 1
            ts = _pick(t, "completedDate", "startedDate", default=0)
            if ts and ts < oldest_ts:
                oldest_ts = ts
            if ts and ts < cutoff_ms:
                continue  # outside the sync window, but still consumed for pagination
            merchant = _pick(t, "merchant", default={}) or {}
            counterparty = _pick(t, "counterparty", default={}) or {}
            out.append({
                "externalId": tid,
                "date": _ms_to_date(ts),
                "description": _pick(t, "description") or _pick(merchant, "name") or "",
                "amount": _to_number(_pick(t, "amount", default=0)),
                "counterparty": _pick(merchant, "name") or _pick(counterparty, "name"),
            })

        if new_count == 0 or oldest_ts <= cutoff_ms or oldest_ts >= cursor:
            break
        cursor = oldest_ts

    return out


async def harvest_accounts(page: Page, device_id: Optional[str]) -> Dict[str, Any]:
    accounts: List[Dict[str, Any]] = []

    wallets = await _fetch_wallets(page, device_id)
    for wallet_id, pockets in wallets:
        for pocket in pockets:
            pocket_id = _pick(pocket, "id")
            if not pocket_id:
                continue
            accounts.append({
                "externalId": pocket_id,
                "name": _pick(pocket, "name", "type", "pocketType", default="Revolut"),
                "type": "CHECKING",
                "iban": None,
                "balance": _to_number(_pick(pocket, "balance", "amount", default=0)),
                "currency": _pick(pocket, "currency", "currencyCode", default="EUR"),
                "parentExternalId": wallet_id if wallet_id and wallet_id != pocket_id else None,
                "transactions": [],
            })

    for mb in await _fetch_money_boxes(page, device_id):
        mb_id = _pick(mb, "id")
        if not mb_id:
            continue
        accounts.append({
            "externalId": mb_id,
            "name": _pick(mb, "name", default="Vault"),
            "type": "SAVINGS",
            "iban": None,
            "balance": _to_number(_pick(mb, "balance", "amount", default=0)),
            "currency": _pick(mb, "currency", "currencyCode", default="EUR"),
            "parentExternalId": _pick(mb, "accountId", "walletId", "podId"),
            "transactions": [],
        })

    # IBANs: one lookup per (walletId, currency) pair among CHECKING accounts.
    iban_cache: Dict[Tuple[str, str], Optional[str]] = {}
    for wallet_id, pockets in wallets:
        if not wallet_id:
            continue
        currencies = {a["currency"] for a in accounts if a["type"] == "CHECKING"}
        for ccy in currencies:
            key = (wallet_id, ccy)
            if key not in iban_cache:
                iban_cache[key] = await _fetch_iban(page, device_id, wallet_id, ccy)
            iban = iban_cache[key]
            if iban:
                for acc in accounts:
                    if acc["type"] == "CHECKING" and acc["currency"] == ccy and not acc["iban"]:
                        acc["iban"] = iban

    for acc in accounts:
        if acc["type"] == "CHECKING":
            acc["transactions"] = await _fetch_transactions(page, device_id, acc["externalId"])

    return {"accounts": accounts}


# ─── Endpoints ────────────────────────────────────────────────────────────────

class AccountsRequest(BaseModel):
    storageState: Union[str, Dict[str, Any]]


@app.post("/accounts")
async def get_accounts(req: AccountsRequest):
    storage_state = _parse_storage_state(req.storageState)
    device_id = _extract_device_id(storage_state)
    if not device_id:
        log.warning("no revo_device_id cookie in storageState — treating session as dead")
        return session_expired()

    async with async_playwright() as p:
        browser = await p.chromium.launch(headless=True, args=["--no-sandbox", "--disable-dev-shm-usage"])
        try:
            context = await browser.new_context(
                storage_state=storage_state, user_agent=UA, locale="fr-FR", timezone_id="Europe/Paris",
            )
            await context.add_init_script(
                "Object.defineProperty(navigator, 'webdriver', { get: () => undefined })"
            )
            page = await context.new_page()

            try:
                await page.goto(HOME_URL, wait_until="domcontentloaded", timeout=30000)
            except Exception:  # noqa: BLE001
                await page.wait_for_timeout(3000)

            refresh_status = await refresh_access_token(page, device_id)
            if refresh_status == 401:
                log.info("token refresh returned 401 — session expired")
                return session_expired()
            if refresh_status not in (200, 201):
                log.warning("token refresh returned unexpected status %s — proceeding anyway", refresh_status)

            info_resp = await api_call(page, "/api/retail/token/info", device_id)
            if info_resp.get("status") == 401:
                log.info("token/info returned 401 — session expired")
                return session_expired()

            result = await harvest_accounts(page, device_id)
            log.info("harvested %d accounts", len(result["accounts"]))
            return result
        finally:
            await browser.close()


@app.post("/enrolment/start")
async def enrolment_start():
    """
    One-time ASSISTED enrolment for a NEW Revolut device/session.

    Revolut login (phone + passcode + mobile-app approval) is not
    automatable and must not be — see design doc §3.5: aggressive automated
    login attempts get web-channel rate-limited. So this launches a HEADFUL
    Chromium window (headless=False) and waits for a human to complete the
    login by hand; no credentials are read or typed by this code.

    Deployment note: headless=False needs a display. The container image
    installs Xvfb and wraps its entrypoint in `xvfb-run` so this call does
    not crash for lack of a DISPLAY, but merely having a virtual display is
    not the same as a human being able to *see and click* the window — that
    requires a VNC bridge (e.g. x11vnc + noVNC) exposed alongside Xvfb. That
    bridge is a documented follow-up (see design doc §4.1), not implemented
    here. Until it exists, run capture_login.py on a host with a real
    display and pass the resulting storageState to /accounts directly, or
    call this endpoint from an environment that already has one.
    """
    async with async_playwright() as p:
        browser = await p.chromium.launch(headless=False, args=["--no-sandbox", "--disable-dev-shm-usage"])
        try:
            context = await browser.new_context(user_agent=UA, locale="fr-FR", timezone_id="Europe/Paris")
            await context.add_init_script(
                "Object.defineProperty(navigator, 'webdriver', { get: () => undefined })"
            )
            page = await context.new_page()
            try:
                await page.goto(APP_URL, wait_until="domcontentloaded", timeout=30000)
            except Exception:  # noqa: BLE001
                await page.wait_for_timeout(3000)

            log.info("enrolment: waiting up to %ss for the user to log in by hand", ENROLMENT_WAIT_S)
            device_id = None
            for _ in range(ENROLMENT_WAIT_S * 1000 // ENROLMENT_POLL_MS):
                device_id = await _read_device_id_from_page(page)
                if device_id:
                    info_resp = await api_call(page, "/api/retail/token/info", device_id)
                    if info_resp.get("status") == 200:
                        break
                await page.wait_for_timeout(ENROLMENT_POLL_MS)
            else:
                log.warning("enrolment timed out — user did not complete login in time")
                return JSONResponse(status_code=408, content={"error": "ENROLMENT_TIMEOUT"})

            storage_state = await context.storage_state()
            log.info("enrolment complete — storageState captured (%d cookies)",
                      len(storage_state.get("cookies", [])))
            return {"storageState": storage_state}
        finally:
            await browser.close()


@app.get("/health")
async def health():
    return {"status": "ok"}
