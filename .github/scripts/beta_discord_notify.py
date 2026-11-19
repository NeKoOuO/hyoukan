#!/usr/bin/env python3
"""Announce a freshly published Houri beta in Discord.

Runs after `publish-beta` in .github/workflows/build_push.yml, so a beta that
was never published (missing BETA_RELEASE_TOKEN) never pings testers.

Env vars (set by GitHub Actions):
  DISCORD_WEBHOOK_URL  - beta channel webhook; unset = dry run (payload printed)
  BETA_REPO            - repository holding the beta releases, e.g. PineappleTwilight/houri-beta
  BETA_TAG             - release tag in BETA_REPO, e.g. r1234
  BETA_CHANGES         - multiline "- subject (sha)" bullets since the previous beta
  BETA_CHANGE_COUNT    - number of commits since the previous beta
  DISCORD_ROLE_ID      - role pinged for beta testers
  GITHUB_REPOSITORY    - org/repo the beta was built from
  GITHUB_SHA           - commit the beta was built from
  AVATAR_URL           - webhook avatar / embed thumbnail
"""

from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

USER_AGENT = (
    "Houri-Beta-Notifier/1.0 (GitHub Actions; "
    "+https://github.com/PineappleTwilight/hyoukan)"
)
MAX_ATTEMPTS = 4
BASE_BACKOFF = 2

ALLOWED_WEBHOOK_HOSTS = ("discord.com", "discordapp.com", "canary.discord.com", "ptb.discord.com")

DEFAULT_BETA_REPO = "PineappleTwilight/houri-beta"
DEFAULT_ROLE_ID = "1564078706673057902"
DEFAULT_AVATAR_URL = (
    "https://raw.githubusercontent.com/PineappleTwilight/hyoukan/master/"
    ".github/readme-images/app-icon.png"
)

# Discord limits: description 4096, field value 1024, content 2000. Leave room
# for the headings and trailing links the description is assembled from.
DESCRIPTION_BUDGET = 3000
ASSET_SUFFIXES = (
    ("arm64-v8a", "arm64-v8a ⭐"),
    ("armeabi-v7a", "armeabi-v7a"),
    ("x86_64", "x86_64"),
    ("x86", "x86"),
)


def _env(name: str, default: str = "") -> str:
    return (os.environ.get(name) or default).strip()


def _validate_webhook(url: str) -> None:
    if not url.startswith("https://"):
        raise ValueError("DISCORD_WEBHOOK_URL must be https://")
    from urllib.parse import urlparse

    host = urlparse(url).hostname or ""
    if not any(host == h or host.endswith("." + h) for h in ALLOWED_WEBHOOK_HOSTS):
        raise ValueError(f"DISCORD_WEBHOOK_URL host must be discord.com, got {host}")


def _render_changes(raw: str, limit: int = DESCRIPTION_BUDGET) -> str:
    """Turn the beta bullet list into a Discord-sized description block."""
    lines = [line.rstrip() for line in raw.strip().splitlines() if line.strip()]
    rendered: list[str] = []
    used = 0
    hidden = 0
    for index, line in enumerate(lines):
        if not line.startswith("- "):
            line = "- " + line
        if len(line) > 220:
            line = line[:219] + "…"
        cost = len(line) + 1
        if used + cost > limit and rendered:
            hidden = len(lines) - index
            break
        rendered.append(line)
        used += cost
    if hidden:
        rendered.append(f"- *…and {hidden} more*")
    if not rendered:
        return "_No earlier beta build to compare against._"
    return "\n".join(rendered)


def build_payload() -> dict:
    repo = _env("GITHUB_REPOSITORY", "PineappleTwilight/hyoukan")
    sha = _env("GITHUB_SHA")
    short_sha = sha[:9]
    beta_repo = _env("BETA_REPO", DEFAULT_BETA_REPO)
    tag = _env("BETA_TAG", "r0")
    role_id = _env("DISCORD_ROLE_ID", DEFAULT_ROLE_ID)
    avatar_url = _env("AVATAR_URL", DEFAULT_AVATAR_URL)
    changes = _env("BETA_CHANGES")
    change_count = _env("BETA_CHANGE_COUNT", "0")

    release_url = f"https://github.com/{beta_repo}/releases/tag/{tag}"
    commit_url = f"https://github.com/{repo}/commit/{sha}" if sha else f"https://github.com/{repo}"

    heading = "**What's new since the last beta**"
    if change_count and change_count != "0":
        heading += f" · {change_count} commit{'s' if change_count != '1' else ''}"
    description = "\n".join(
        [
            "A fresh **beta build** is ready to test — grab an APK below and "
            "report anything that breaks. 🐛",
            "",
            heading,
            _render_changes(changes),
        ]
    )
    if len(description) > 3900:
        description = description[:3900] + "\n…"

    download_url = f"https://github.com/{beta_repo}/releases/download/{tag}"
    mtl_links = " · ".join(
        f"[{label}]({download_url}/Houri-{abi}-{tag}.apk)" for abi, label in ASSET_SUFFIXES
    )
    nomtl_links = " · ".join(
        f"[nomtl {abi}]({download_url}/Houri-nomtl-{abi}-{tag}.apk)"
        for abi in ("arm64-v8a", "armeabi-v7a")
    )
    downloads_value = (
        f"{mtl_links}\n{nomtl_links}\n"
        f"[Full release notes]({release_url})"
    )

    about_value = (
        f"Built from `master` at [`{short_sha or 'unknown'}`]({commit_url}).\n"
        "**arm64-v8a** is the safe pick for most phones; **nomtl** builds drop "
        "the on-device AI translation engine and are smaller. Betas update "
        "themselves in-app."
    )

    embed = {
        "author": {"name": "Houri Beta", "icon_url": avatar_url},
        "title": f"🍍 Beta {tag} is ready",
        "url": release_url,
        "description": description,
        "color": 0xF97316,  # houri orange
        "thumbnail": {"url": avatar_url},
        "fields": [
            {"name": "📥 Downloads", "value": downloads_value, "inline": False},
            {"name": "🧪 Which APK?", "value": about_value, "inline": False},
        ],
        "footer": {
            "text": f"{repo} • master@{short_sha or 'unknown'} • beta channel",
            "icon_url": avatar_url,
        },
        "timestamp": datetime.now(timezone.utc).isoformat(),
    }

    return {
        "username": "Houri Betas",
        "avatar_url": avatar_url,
        "content": f"<@&{role_id}> New beta **{tag}** is up for testing!",
        "allowed_mentions": {"parse": [], "roles": [role_id]},
        "embeds": [embed],
    }


def post_with_retry(webhook: str, payload: dict) -> None:
    data = json.dumps(payload).encode("utf-8")
    headers = {"Content-Type": "application/json", "User-Agent": USER_AGENT}
    for attempt in range(1, MAX_ATTEMPTS + 1):
        try:
            request = urllib.request.Request(webhook, data=data, headers=headers, method="POST")
            with urllib.request.urlopen(request, timeout=15) as response:
                print(f"Discord webhook responded {response.status}")
                return
        except urllib.error.HTTPError as error:
            body = error.read().decode(errors="replace")
            if error.code == 429:
                retry_after = float(error.headers.get("Retry-After", "1") or "1")
                wait = retry_after + BASE_BACKOFF ** (attempt - 1)
            elif error.code >= 500:
                wait = BASE_BACKOFF ** attempt
            else:
                print(f"Discord webhook error {error.code}: {body}")
                raise
            if attempt == MAX_ATTEMPTS:
                print(f"Discord still failing after {MAX_ATTEMPTS} attempts: {body}")
                raise
            print(f"Discord {error.code}, retrying in {wait:.1f}s (attempt {attempt}/{MAX_ATTEMPTS})")
            time.sleep(wait)
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            if attempt == MAX_ATTEMPTS:
                print(f"Discord unreachable after {MAX_ATTEMPTS} attempts: {error}")
                raise
            wait = BASE_BACKOFF ** attempt
            print(f"Discord network error: {error}; retrying in {wait}s (attempt {attempt}/{MAX_ATTEMPTS})")
            time.sleep(wait)
    raise RuntimeError("Discord delivery failed after retries")


def main() -> int:
    payload = build_payload()
    webhook = _env("DISCORD_WEBHOOK_URL")
    if not webhook:
        print("BETA_DISCORD_WEBHOOK not set; skipping beta announcement (dry run).")
        print(json.dumps(payload, indent=2))
        return 0
    _validate_webhook(webhook)
    print(json.dumps(payload, indent=2))
    post_with_retry(webhook, payload)
    print("Beta announcement sent.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
