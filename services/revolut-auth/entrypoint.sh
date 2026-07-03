#!/bin/sh
# Start a virtual X display, then run the API server under it.
#
# POST /sync launches Camoufox (Firefox) with headless=False when a re-auth is
# needed, so the process needs a DISPLAY. We start Xvfb directly instead of the
# `xvfb-run` wrapper: on this slim/trixie base xvfb-run hangs on its X-readiness
# handshake and never exec's the wrapped command, so the sidecar never comes up.
#
# Xvfb refuses to create /tmp/.X11-unix when euid != 0, so (running as the non-root
# revoauth user) we must ensure the socket dir exists before starting it.
set -e

mkdir -p /tmp/.X11-unix
Xvfb :99 -screen 0 1280x1024x24 -nolisten tcp &
export DISPLAY=:99

# Wait (briefly) for the X socket so a fast /sync right after boot still has a display.
i=0
while [ ! -S /tmp/.X11-unix/X99 ] && [ "$i" -lt 25 ]; do
    i=$((i + 1))
    sleep 0.2
done

exec uvicorn main:app --host 0.0.0.0 --port 8002
