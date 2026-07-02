FROM python:3.12-slim

WORKDIR /app

RUN groupadd -r revoauth && useradd -r -g revoauth revoauth

# Chromium system deps for Debian bookworm (subset of playwright --with-deps,
# excluding Ubuntu-only font packages unavailable on Debian). xvfb backs the
# headful path used by POST /enrolment/start (see main.py docstring) — the
# always-on headless /accounts sync does not need it.
RUN apt-get update && apt-get install -y --no-install-recommends \
        libnss3 libnspr4 libatk1.0-0 libatk-bridge2.0-0 libcups2 \
        libdrm2 libxkbcommon0 libxcomposite1 libxdamage1 libxfixes3 \
        libxrandr2 libgbm1 libasound2 libpango-1.0-0 libcairo2 \
        libx11-6 libx11-xcb1 libxcb1 libxext6 \
        ca-certificates fonts-liberation \
        xvfb \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

ENV PLAYWRIGHT_BROWSERS_PATH=/app/playwright-browsers

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt \
    && playwright install chromium

COPY main.py .

RUN chown -R revoauth:revoauth /app
USER revoauth

EXPOSE 8002

# xvfb-run gives the whole process a virtual DISPLAY, so the headful
# enrolment path (headless=False) has somewhere to render instead of
# crashing for lack of a display. That alone does not make it *interactive*
# for a human yet — actually seeing/clicking the window needs a VNC bridge
# (e.g. x11vnc + noVNC) exposed alongside Xvfb, which is a documented
# follow-up (design doc §4.1), not wired here.
CMD ["xvfb-run", "-a", "uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8002"]
