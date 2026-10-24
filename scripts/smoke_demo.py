"""Launch a packaged Agent and verify its no-key/no-middleware default demo.

Python 3.10+ standard library only. Makes no paid model calls. Runtime files and
logs stay in a temporary directory; the Java subprocess is stopped on failure.
"""
import argparse
import json
import os
import re
import shutil
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


def read_traces(path):
    if not path.is_file():
        return []
    traces = []
    for line in path.read_text(encoding="utf-8").splitlines():
        try:
            traces.append(json.loads(line))
        except json.JSONDecodeError:
            # The async JSONL exporter may be appending its final line as we inspect it.
            continue
    return traces


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True, type=Path)
    parser.add_argument("--java", default="java")
    parser.add_argument("--artifacts", type=Path, help="Save trace snapshots and report for the standalone trace viewer")
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
    report_run_id = None
    trace_summary = None
    runtime = Path(__file__).resolve().parents[1] / "var"
    runtime.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="demo-", dir=runtime) as directory:
        with open(Path(directory) / "demo.log", "wb") as log:
            flags = {"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {}
            process = subprocess.Popen([args.java, "-Djava.io.tmpdir=" + directory, "-jar", str(jar), "--spring.profiles.active=demo",
                                        f"--server.port={port}", "--server.address=127.0.0.1", "--harness.trace.sink=file",
                                        "--harness.trace.directory=" + str(Path(directory) / "trace")],
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
                                                  ("查询视频1001昨天的弹幕数", "HARNESS", "128"),
                                                  ("分析直播间1001昨天的弹幕并生成报告", "HARNESS", "120")]:
                    status, created = request(base, "POST", "/runs", {"message": message})
                    assert status == 202, "Run submission failed"
                    path = "/runs/" + created["runId"]
                    if "报告" in message:
                        report_run_id = created["runId"]
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
                        intent = "DANMAKU_REPORT" if "报告" in message else "VIDEO_METRIC"
                        assert run["preparation"]["intent"] == intent, "Intent was not merged into the task"
                        assert 2 <= run["preparationCalls"] <= 3, "Critical recognition branches must be counted"
                    else:
                        assert run["totalModelCalls"] == 0
                        assert "intent" in run["preparation"]["skippedStages"]
                    _, stream = request(base, "GET", path + "/events")
                    assert stream.count("event:DONE") == 1, "SSE must deliver exactly one DONE"
                    if "报告" in message:
                        assert "event:ARTIFACT_READY" in stream
                        report_path = re.search(r"/api/agent/runs/[a-z0-9-]+/reports/rp_[a-z0-9-]+", run["answer"]["text"]).group()[len("/api/agent"):]
                        code, html = request(base, "GET", report_path)
                        assert code == 200 and "模拟数据" in html and "非随机" in html
                        assert "48 条样本 · 去重后 8 种内容" in html, "The report should merge repeated sample texts"
                        assert html.count('class="sample-card"') == 8, "Only unique sample texts should be rendered"
                        assert html.count('<circle class="trend-dot"') == 12, "The chart should render each time bin"
                        if args.artifacts:
                            args.artifacts.mkdir(parents=True, exist_ok=True)
                            (args.artifacts / "report.html").write_text(html, encoding="utf-8")
                        code, _ = request(base, "GET", report_path, authenticated=False)
                        assert code == 401, "Reports must require authentication"
                        checks.append("danmaku-report-private-html-and-sse")
                    else:
                        checks.append(expected.lower() + "-answer-and-sse")
                if report_run_id:
                    trace_file = Path(directory) / "trace" / "archive" / "spans.jsonl"
                    deadline = time.monotonic() + 10
                    while time.monotonic() < deadline:
                        run_spans = [event for event in read_traces(trace_file)
                                     if event.get("attributes", {}).get("run.id") == report_run_id]
                        if (any(event.get("name") == "agent.run" for event in run_spans)
                                and any(event.get("attributes", {}).get("span.type") == "finish" for event in run_spans)):
                            break
                        time.sleep(0.05)
                    assert any(event.get("attributes", {}).get("span.type") == "finish" for event in run_spans), \
                        "Trace export did not deliver the run's terminal span before shutdown"
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
                if report_run_id:
                    trace_file = Path(directory) / "trace" / "archive" / "spans.jsonl"
                    assert trace_file.is_file(), "Agent shut down without a trace archive"
                    trace = read_traces(trace_file)
                    run_spans = [event for event in trace if event.get("attributes", {}).get("run.id") == report_run_id]
                    assert run_spans, "No spans were collected for the report run"
                    roots = [event for event in run_spans if event.get("name") == "agent.run"]
                    if len(roots) != 1:
                        print(json.dumps({"traceDiagnostic": {"expectedRunId": report_run_id, "spanCount": len(run_spans),
                              "runSpans": [{"name": event.get("name"), "traceId": event.get("traceId"), "parent": event.get("parentSpanId"),
                                            "type": event.get("attributes", {}).get("span.type")} for event in run_spans],
                              "rootCandidates": [{"traceId": event.get("traceId"), "attributes": event.get("attributes", {})}
                                                for event in trace if event.get("name") == "agent.run"]}}))
                    assert len(roots) == 1, "Each report run must export one agent.run trace root"
                    trace_id = roots[0]["traceId"]
                    # Snapshot and SSE HTTP requests carry run.id on their own server spans;
                    # inspect the run's actual parent-child trace independently.
                    run_trace = [event for event in run_spans if event.get("traceId") == trace_id]
                    assert len(run_trace) >= 25, "The report trace did not collect its full span chain"
                    attrs = [event.get("attributes", {}) for event in run_trace]
                    span_types = {item.get("span.type") for item in attrs if item.get("span.type")}
                    required = {"receive_query", "pre_route", "faq_lookup", "emotion_recognition", "intent_recognition",
                                "query_understanding", "context_build", "agent_start", "llm_inference", "read_skill",
                                "tool_call", "agent_end", "finish"}
                    assert required.issubset(span_types), "The report trace is missing stages: " + repr(sorted(required - span_types))
                    assert any(item.get("preparation.intent") == "DANMAKU_REPORT" for item in attrs), "Intent is missing from the trace"
                    assert any(item.get("request.route") == "HARNESS" for item in attrs), "Route is missing from the trace"
                    assert any(item.get("finish.type") == "TOOL_DELIVERY" and item.get("run.status") == "SUCCEEDED" for item in attrs), "Terminal delivery is missing from the trace"
                    assert {item.get("tool.name") for item in attrs if item.get("tool.name")} >= {
                        "read_skill", "query_danmaku", "create_danmaku_report"}, "A report tool span is missing"
                    assert "分析直播间1001昨天的弹幕并生成报告" not in trace_file.read_text(encoding="utf-8")
                    assert "这款什么时候发货？" not in trace_file.read_text(encoding="utf-8"), "Raw danmaku text must not enter trace"
                    trace_summary = {"spanCount": len(run_trace), "spanTypes": sorted(span_types),
                                     "tools": sorted(item.get("tool.name") for item in attrs if item.get("tool.name")),
                                     "finish": "TOOL_DELIVERY/SUCCEEDED", "rawInputOrDanmakuExported": False}
                    checks.append("report-trace-stage-correlation-and-payload-redaction")
                    if args.artifacts:
                        args.artifacts.mkdir(parents=True, exist_ok=True)
                        (args.artifacts / "spans.jsonl").write_bytes(trace_file.read_bytes())
                        shutil.copytree(Path(directory) / "trace" / "details", args.artifacts / "details", dirs_exist_ok=True)
                        (args.artifacts / "run.json").write_text(json.dumps({"runId": report_run_id, "traceId": trace_id}), encoding="utf-8")
    print(json.dumps({"status": "PASS", "checks": checks, "reportTrace": trace_summary,
                      "paidModelCalls": 0, "middlewareRequired": False}))


if __name__ == "__main__":
    main()
