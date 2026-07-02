"""
Revolut ASSISTED login capture — runs in the sidecar's Playwright env, but HEADFUL
on your GUI. You perform the whole login by hand in the window that appears (phone
number, passcode, mobile approval — a real new-device enrolment).

On success it writes the session to revolut-storage.json. The sidecar reuses that
headless for recurring sync (cookie + x-device-id + PUT /token — already proven).

No credentials are read by this script; you type everything in the browser window.
"""

import asyncio
import sys

from playwright.async_api import async_playwright

APP = "https://app.revolut.com/"
UA = ("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36")
STORAGE = "revolut-storage.json"
WAIT_S = 420  # generous — take your time with the mobile approval / device enrolment


def log(msg: str) -> None:
    line = f"[capture] {msg}"
    print(line, flush=True)
    try:
        with open("capture.log", "a") as f:
            f.write(line + "\n")
    except Exception:  # noqa: BLE001
        pass


async def logged_in(page) -> bool:
    try:
        return await page.evaluate(
            """async () => {
                const c = document.cookie.split(';').map(s=>s.trim())
                          .find(s=>s.startsWith('revo_device_id='));
                const dev = c ? c.split('=').slice(1).join('=') : '';
                const r = await fetch('/api/retail/token/info', {
                  credentials: 'include',
                  headers: {'x-device-id': dev, 'x-browser-application': 'WEB_CLIENT',
                            'x-client-version': '100.0'}
                });
                return r.status === 200;
            }"""
        )
    except Exception:  # noqa: BLE001
        return False


async def main() -> int:
    try:
        open("capture.log", "w").close()
    except Exception:  # noqa: BLE001
        pass
    log("launching HEADFUL Chromium — a window should appear on your screen")
    async with async_playwright() as p:
        browser = await p.chromium.launch(
            headless=False, args=["--no-sandbox", "--disable-dev-shm-usage"])
        context = await browser.new_context(user_agent=UA, locale="fr-FR",
                                            timezone_id="Europe/Paris")
        await context.add_init_script(
            "Object.defineProperty(navigator,'webdriver',{get:()=>undefined})")
        page = await context.new_page()
        await page.goto(APP, wait_until="domcontentloaded", timeout=30000)
        log("=> LOG IN BY HAND in the window: phone number, passcode, approve on your phone.")
        log(f"waiting up to {WAIT_S}s for you to reach your dashboard...")
        for _ in range(WAIT_S // 3):
            if await logged_in(page):
                break
            await page.wait_for_timeout(3000)
        if await logged_in(page):
            await context.storage_state(path=STORAGE)
            log(f"GO: logged in. Session saved to {STORAGE} (secret — gitignored).")
            await browser.close()
            return 0
        log("NO-GO: not logged in within the window. Re-run and take your time.")
        await browser.close()
        return 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
