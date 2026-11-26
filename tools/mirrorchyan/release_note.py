"""官方 Mirror 酱发布说明脚本的 TLS 校验版。

TLS-verifying adaptation of MirrorChyan/release-note-action
at 5bf59f3b46948f2cfd10d55996110834c33cdee0 (MIT, see LICENSE).
"""

import asyncio
import json
import sys
from datetime import datetime

import aiohttp


def log(msg: object) -> None:
    print(f"{datetime.now()} | {msg}")


async def upload(session: aiohttp.ClientSession, rid: str, data: dict, headers: dict) -> bool:
    log("start upload")
    url = f"https://mirrorchyan.com/api/resources/{rid}/versions/release-note"

    try:
        async with session.put(
            url,
            headers=headers,
            json=data,
            ssl=True,
            allow_redirects=False,
        ) as response:
            log(f"step 1: {response.status}")
            if response.status != 200:
                return False
            log("uploaded")
            return True

    except (aiohttp.ClientError, TimeoutError) as error:
        log(f"request failed: {type(error).__name__}")
        return False


async def main():
    _, rid, token, body_file = sys.argv

    headers = {
        "Authorization": token.strip(),
        "User-Agent": "Apifox/1.0.0 (https://apifox.com)",
        "Accept": "*/*",
        "Content-Type": "application/json",
    }

    with open(body_file, encoding="utf-8") as file:
        data = json.load(file)

    done = False
    retries = 3
    async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=30)) as session:
        for i in range(retries):
            if await upload(session, rid, data, headers):
                done = True
                break
            elif i + 1 < retries:
                delay = (i + 1) * 15
                log(f"retry {i + 1} after {delay}s")
                await asyncio.sleep(delay)

    if not done:
        log("failed")
        sys.exit(1)

    log("done")
    sys.exit(0)


if __name__ == "__main__":
    asyncio.run(main())
