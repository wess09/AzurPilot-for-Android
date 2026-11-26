"""官方 Mirror 酱上传脚本的 TLS 校验版。

TLS-verifying adaptation of MirrorChyan/uploading-action
at 4230465d8cf5331da0523a3551518e1a88fe666b (MIT, see LICENSE).
"""

import os as ospkg
import sys
import time
from datetime import datetime
from urllib.parse import urlsplit

import requests

BASE = "mirrorchyan.com"


def log(msg: object) -> None:
    print(f"{datetime.now()} | {msg}")


def get_suffix(path):
    for suffix in (".tar.gz", ".tar.xz"):
        if path.endswith(suffix):
            return suffix
    return ospkg.path.splitext(path)[1]


def upload(rid: str, file: str, data: dict, headers: dict, download_name: str) -> bool:
    log("start upload")

    # step 1
    response_1 = requests.post(
        f"https://{BASE}/api/resources/{rid}/versions",
        headers=headers,
        data=data,
        verify=True,
        allow_redirects=False,
        timeout=30,
    )
    log(f"step 1: {response_1.status_code}")

    if response_1.status_code != 200:
        return False

    # step 2
    response_1_data = response_1.json()["data"]

    host = response_1_data["host"]
    url = urlsplit(host)
    if url.scheme != "https" or not url.hostname or url.username or url.password:
        log("upload host must use HTTPS")
        return False

    with open(file, "rb") as source:
        response_2 = requests.post(
            host,
            data={
                "success_action_status": "200",
                "name": response_1_data["name"],
                "signature": response_1_data["signature"],
                "key": response_1_data["key"],
                "policy": response_1_data["policy"],
                "OSSAccessKeyId": response_1_data["access_key"],
                "Content-Disposition": f'attachment; filename="{download_name}"',
            },
            files={"file": source},
            verify=True,
            allow_redirects=False,
            timeout=(10, 300),
        )
    log(f"step 2: {response_2.status_code}")

    if response_2.status_code != 200:
        return False

    # step 3
    response_3 = requests.post(
        f"https://{BASE}/api/resources/{rid}/versions/callback",
        headers=headers,
        data={**data, "key": response_1_data["key"]},
        verify=True,
        allow_redirects=False,
        timeout=30,
    )
    log(f"step 3: {response_3.status_code}")

    if response_3.status_code != 200:
        return False

    status_key = response_3.json()["data"].get("status_key", "")
    if not status_key:
        log("no status_key returned, skip polling")
        return True

    # step 4: poll status
    log("polling upload status")
    poll_headers = {k: v for k, v in headers.items() if k != "Content-Type"}

    STATUS_PENDING = 1
    STATUS_COMPLETED = 2
    STATUS_FAILED = 3

    max_polls = 60
    interval = 5
    for i in range(max_polls):
        time.sleep(interval)
        resp = requests.get(
            f"https://{BASE}/api/resources/{rid}/versions/status",
            headers=poll_headers,
            params={"key": status_key},
            verify=True,
            allow_redirects=False,
            timeout=30,
        )
        if resp.status_code != 200:
            log(f"poll failed: {resp.status_code}")
            continue

        status = resp.json()["data"]["status"]
        if status == STATUS_COMPLETED:
            log("processing completed")
            return True
        elif status == STATUS_FAILED:
            log("processing failed on server")
            return False
        elif status != STATUS_PENDING:
            log(f"unexpected status: {status}")
            return False

        if (i + 1) % 6 == 0:
            log(f"still pending... ({(i + 1) * interval}s elapsed)")

    log("polling timed out")
    return False


def main():
    _, rid, version, os, arch, channel, token, file = sys.argv

    data = {
        "name": version,
        "os": os,
        "arch": arch,
        "channel": channel,
    }

    file_ext = get_suffix(file)
    download_name = "-".join(filter(bool, [rid, version, os, arch])) + file_ext

    data["filename"] = download_name

    headers = {
        "Authorization": token.strip(),
        "User-Agent": "Apifox/1.0.0 (https://apifox.com)",
        "Accept": "*/*",
        "Content-Type": "application/x-www-form-urlencoded",
    }

    log(data)

    done = False
    retries = 3
    for i in range(retries):
        try:
            uploaded = upload(rid, file, data, headers, download_name)
        except (OSError, KeyError, TypeError, ValueError) as error:
            # 传输异常可能包含临时存储链接，日志只保留异常类型。
            log(f"upload failed: {type(error).__name__}")
            uploaded = False
        if uploaded:
            done = True
            break
        elif i + 1 < retries:
            delay = (i + 1) * 15
            log(f"retry {i + 1} after {delay}s")
            time.sleep(delay)

    if not done:
        log("failed")
        sys.exit(1)

    log("done")
    sys.exit(0)


if __name__ == "__main__":
    main()
