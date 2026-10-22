"""Launch a packaged Agent and verify its no-key/no-middleware default demo.

Python 3.10+ standard library only. Makes no paid model calls. Runtime files and
logs stay in a temporary directory; the Java subprocess is stopped on failure.
"""
import argparse
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request


def request(base, method, path, body=None, authenticated=True):
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if authenticated:
        headers["Authorization"] = "Bearer demo-alice"
    value = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    req = urllib.request.Request(base + path, data=value, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            raw = response.read()
            return response.status, (json.loads(raw) if "json" in response.headers.get("Content-Type", "") else raw.decode())
    except urllib.error.HTTPError as error:
        return error.code, None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True, type=Path)
    parser.add_argument("--java", default="java")
    args = parser.parse_args()
    jar = args.jar.resolve(strict=True)
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    base = f"http://127.0.0.1:{port}/api/agent"
    # Avoid inheriting paid-model/storage settings from the caller's shell.
    environment = {key: value for key, value in os.environ.items()
                   if not key.upper().startswith(("AGENT_", "HARNESS_", "DEEPSEEK_", "SPRING_", "BUSINESS_"))}
    checks = []
    runtime = Path(__file__).resolve().parents[1] / "var"
    runtime.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="demo-", dir=runtime) as directory:
        with open(Path(directory) / "demo.log", "wb") as log:
            flags = {"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {}
            process = subprocess.Popen([args.java, "-Djava.io.tmpdir=" + directory, "-jar", str(jar), "--spring.profiles.active=demo",
                                        f"--server.port={port}", "--server.address=127.0.0.1"],
                                       cwd=directory, env=environment, stdout=log, stderr=subprocess.STDOUT, **flags)
            try:
                deadline = time.monotonic() + 60
                while True:
                    if process.poll() is not None:
                        raise AssertionError("Demo exited before accepting requests")
                    try:
                        status, _ = request(base, "POST", "/runs", {"message": "你能做什么"}, False)
                        if status == 401:
                            break
                    except (OSError, urllib.error.URLError):
                        pass
                    if time.monotonic() > deadline:
                        raise AssertionError("Demo startup timed out")
                    time.sleep(0.2)
                checks.append("unauthenticated-request-rejected")
                for message, expected, value in [("你能做什么", "FAQ", None),
                                                  ("查询视频1001昨天的弹幕数", "HARNESS", "128")]:
                    status, created = request(base, "POST", "/runs", {"message": message})
                    assert status == 202, "Run submission failed"
                    path = "/runs/" + created["runId"]
                    deadline = time.monotonic() + 20
                    while True:
                        status, run = request(base, "GET", path)
                        assert status == 200, "Run read failed"
                        if run["status"] not in ("QUEUED", "RUNNING"):
                            break
                        assert time.monotonic() < deadline, "Demo run timed out"
                        time.sleep(0.1)
                    assert run["status"] == "SUCCEEDED" and run["route"] == expected, "Unexpected demo route/result"
                    if value:
                        assert value in run["answer"]["text"] and "模拟" in run["answer"]["text"]
                    else:
                        assert run["totalModelCalls"] == 0
                    _, stream = request(base, "GET", path + "/events")
                    assert stream.count("event:DONE") == 1, "SSE must deliver exactly one DONE"
                    checks.append(expected.lower() + "-answer-and-sse")
            except Exception:
                log.flush()
                failure = runtime / "demo-failure.log"
                failure.write_bytes((Path(directory) / "demo.log").read_bytes())
                print("Demo diagnostics saved to " + str(failure))
                raise
            finally:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)
    print(json.dumps({"status": "PASS", "checks": checks, "paidModelCalls": 0, "middlewareRequired": False}))


if __name__ == "__main__":
    main()
