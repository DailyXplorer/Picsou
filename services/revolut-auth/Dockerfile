FROM python:3.12-slim

WORKDIR /app

RUN groupadd -r revoauth && useradd -r -g revoauth -m -d /home/revoauth revoauth

# System libraries for Camoufox's Firefox engine + Xvfb. POST /sync runs a headful
# login (headless=False) when re-auth is needed, so the whole app is launched under
# xvfb-run to give it a virtual display; the headless session-reuse path also works.
RUN apt-get update && apt-get install -y --no-install-recommends \
        xvfb \
        libgtk-3-0 libdbus-glib-1-2 libx11-xcb1 libxcb-shm0 libxcomposite1 \
        libxcursor1 libxdamage1 libxi6 libxtst6 libxrandr2 libxt6 libxext6 \
        libasound2 libpangocairo-1.0-0 libpango-1.0-0 libcairo2 libgdk-pixbuf-2.0-0 \
        libnss3 libnspr4 libfontconfig1 fonts-liberation ca-certificates \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Persistent per-member session profiles live here (mount a volume in docker-compose).
RUN mkdir -p /data/revolut-profiles && chown -R revoauth:revoauth /app /data/revolut-profiles
USER revoauth

# Download Camoufox's stealth Firefox + GeoIP DB as the runtime user (into ~/.cache/camoufox)
# so the non-root user can read them at runtime.
RUN python -m camoufox fetch

COPY --chown=revoauth:revoauth main.py .

ENV REVOLUT_PROFILES_DIR=/data/revolut-profiles
EXPOSE 8002

CMD ["xvfb-run", "-a", "uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8002"]
