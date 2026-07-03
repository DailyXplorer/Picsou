"""Regression tests for the Camoufox profile-lock collision that made /sync 500.

A persistent Camoufox/Firefox profile can be opened by only ONE Firefox process at
a time. Two failure modes broke /sync with
"Firefox is already running, but is not responding":

  1. concurrent /sync calls for the same member launched two browsers on the same
     profile (the second died), and
  2. a browser that exited uncleanly left `lock`/`.parentlock` behind, wedging every
     later /sync until manual cleanup.

The fix is per-member serialization (fast-fail 409 when a sync is already running)
plus clearing stale Firefox lock files before each launch. These tests exercise both
without a real browser.

Run: .venv/bin/python tests/test_profile_lock.py   (no pytest needed)
"""

import os
import sys
import tempfile

import anyio

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import main  # noqa: E402


async def test_concurrent_sync_for_same_member_fast_fails_409():
    """A second /sync for a member whose sync is already in flight must be rejected
    with 409 and must NOT reach the browser layer (which would collide on the profile)."""
    harvest_calls = 0
    started = anyio.Event()

    async def slow_harvest(member_id):
        nonlocal harvest_calls
        harvest_calls += 1
        started.set()
        await anyio.sleep(0.5)  # hold the member "busy" while the 2nd request arrives
        return {"accounts": []}

    original = main._harvest_from_profile
    main._harvest_from_profile = slow_harvest
    main._member_locks.clear()
    try:
        req = main.SyncRequest(phoneNumber="+33600000000", passcode="123456", memberId="1")
        results = {}

        async def first():
            results["first"] = await main.sync(req)

        async def second():
            await started.wait()          # ensure the first call already holds the lock
            results["second"] = await main.sync(req)

        async with anyio.create_task_group() as tg:
            tg.start_soon(first)
            tg.start_soon(second)

        assert harvest_calls == 1, f"expected exactly one harvest, got {harvest_calls}"
        second = results["second"]
        assert getattr(second, "status_code", None) == 409, (
            f"second concurrent sync should be 409, got {second!r}")
        assert results["first"] == {"accounts": []}, results["first"]
    finally:
        main._harvest_from_profile = original
        main._member_locks.clear()


async def test_lock_keyed_on_sanitized_profile_key():
    """The sync lock must key on the SAME canonical key as the profile dir. Raw ids that
    sanitize to the same profile (e.g. "1" and "1!") must share one lock, else two syncs
    would take different locks yet collide on one Firefox profile."""
    main._member_locks.clear()
    try:
        assert main._profile_key("1") == main._profile_key("1!") == "1"
        assert main._member_lock("1") is main._member_lock("1!"), "same profile must share a lock"
        assert main._member_lock("1") is main._member_lock("1"), "same id must reuse its lock"
        assert main._member_lock("1") is not main._member_lock("2"), "distinct profiles must differ"
    finally:
        main._member_locks.clear()


async def test_clear_stale_locks_removes_lock_files():
    """Stale `lock` (a symlink) and `.parentlock` in a profile dir must be removed, the
    profile dir itself kept, and a second call on a clean dir must be a no-op."""
    with tempfile.TemporaryDirectory() as root:
        original_root = main.PROFILES_ROOT
        main.PROFILES_ROOT = root
        try:
            profile = main._profile_dir("1")  # creates <root>/1
            os.symlink("127.0.1.1:+475", os.path.join(profile, "lock"))  # dangling, like Firefox
            open(os.path.join(profile, ".parentlock"), "w").close()
            (open(os.path.join(profile, "prefs.js"), "w")).close()  # real profile data — must survive

            main._clear_stale_locks("1")

            assert not os.path.lexists(os.path.join(profile, "lock"))
            assert not os.path.exists(os.path.join(profile, ".parentlock"))
            assert os.path.isdir(profile), "profile dir must be preserved"
            assert os.path.exists(os.path.join(profile, "prefs.js")), "profile data must be preserved"

            main._clear_stale_locks("1")  # idempotent, no raise on a clean dir
        finally:
            main.PROFILES_ROOT = original_root


async def _run():
    tests = [
        test_concurrent_sync_for_same_member_fast_fails_409,
        test_lock_keyed_on_sanitized_profile_key,
        test_clear_stale_locks_removes_lock_files,
    ]
    failures = 0
    for t in tests:
        try:
            await t()
            print(f"PASS {t.__name__}")
        except Exception as exc:  # noqa: BLE001
            failures += 1
            print(f"FAIL {t.__name__}: {type(exc).__name__}: {exc}")
    return failures


if __name__ == "__main__":
    sys.exit(1 if anyio.run(_run) else 0)
