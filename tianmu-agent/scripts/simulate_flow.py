"""Process-level acceptance: real Agent/MySQL/Redis, local simulated model and business data.

Requires Python 3.10+, a packaged Agent JAR, a dedicated MySQL database ending in _it,
and an isolated Redis. No third-party Python packages or paid model calls are used.
Credentials are read from environment (or an untracked --env-file), never printed.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.request
from urllib.parse import urlsplit
import uuid


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def wait_for(operation, timeout=25):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = operation()
        if value:
            return value
        time.sleep(0.05)
    raise AssertionError("Timed out waiting for acceptance condition")


def free_port():
    with socket.socket() as connection:
        connection.bind(("127.0.0.1", 0))
        return connection.getsockname()[1]


class SimulatedModel:
    def __init__(self):
        self.mode = "normal"
        self.calls = []
        self.lock = threading.Lock()

    def respond(self, body):
        prompt = body["messages"][0]["content"]
        data = json.loads(body["messages"][-1]["content"])
        if body["model"] == "mock-preparation":
            stage = "emotion" if '"emotion"' in prompt else (
                "clarification" if '"required"' in prompt else "rewrite")
        else:
            stage = "harness"
        with self.lock:
            call = {"stage": stage, "start": time.monotonic(), "end": None,
                    "input": data, "mode": self.mode}
            self.calls.append(call)
        try:
            time.sleep(0.03 if stage == "emotion" and call["mode"] == "negative-emotion" else 0.15)
            if stage == "emotion":
                if call["mode"] == "emotion-slow":
                    time.sleep(3)
                return 200, {"emotion": "NEGATIVE" if call["mode"] == "negative-emotion" else "NEUTRAL"}
            if stage == "clarification":
                if call["mode"] == "clarification-failure":
                    return 503, None
                return 200, {"required": False, "question": None}
            if stage == "rewrite":
                if call["mode"] == "rewrite-failure":
                    return 503, None
                if call["mode"] == "unsafe-rewrite":
                    return 200, {"query": data["query"].replace("1001", "1002")}
                return 200, {"query": data["query"].replace("帮我查一下", "查询")}
            if call["mode"] == "harness-slow":
                time.sleep(4)
            if call["mode"] == "malformed":
                return 200, {"kind": "MADE_UP_KIND"}
            if "video-metrics" not in data["activeSkills"]:
                return 200, {"kind": "TOOL", "call": {
                    "name": "read_skill", "arguments": {"name": "video-metrics"}}}
            results = [item for item in data["results"] if item["tool"] == "query_video_metrics"]
            if not results:
                conditions = data["taskSpecification"]["conditions"]
                arguments = dict(conditions)
                if call["mode"] == "scope-escape":
                    arguments["video_id"] = 1002
                return 200, {"kind": "TOOL", "call": {
                    "name": "query_video_metrics", "arguments": arguments}}
            result = results[-1]
            value = result["data"]["value"]
            conditions = data["taskSpecification"]["conditions"]
            label = "【模拟数据】" if result["data"].get("demo", True) else "【已入库统计】"
            text = (f"{label}视频 {conditions['video_id']} 在 {conditions['date']} "
                    f"的弹幕数为 {value} 条；本次结果仅用于验证流程。")
            return 200, {"kind": "ANSWER", "answer": {
                "text": text, "evidenceIds": [result["evidenceId"]]}}
        finally:
            call["end"] = time.monotonic()

    def handler(self):
        model = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_POST(self):
                try:
                    require(self.path == "/chat/completions", "Unexpected model path")
                    require(self.headers.get("Authorization") == "Bearer simulation-only",
                            "Unexpected model credential")
                    length = int(self.headers.get("Content-Length", "0"))
                    require(0 < length <= 1048576, "Invalid model request size")
                    status, decision = model.respond(json.loads(self.rfile.read(length)))
                    body = {"error": {"message": "simulated-unavailable"}} if status != 200 else {
                        "choices": [{"finish_reason": "stop", "message": {
                            "role": "assistant", "content": json.dumps(decision, ensure_ascii=False)}}],
                        "usage": {"prompt_tokens": 100, "completion_tokens": 30}}
                    raw = json.dumps(body, ensure_ascii=False).encode("utf-8")
                    self.send_response(status)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(raw)))
                    self.end_headers()
                    self.wfile.write(raw)
                except ConnectionError:
                    pass  # Expected when Agent cancels an in-flight HTTP request.

        return Handler


class FlowSimulation:
    def __init__(self, args):
        self.args = args
        self.output = Path(args.output_dir).resolve()
        self.output.mkdir(parents=True, exist_ok=False)
        (self.output / "java-temp").mkdir()
        self.environment = dict(os.environ)
        if args.env_file:
            self.environment.update(json.loads(Path(args.env_file).read_text(encoding="utf-8")))
        jdbc = self.environment.get("AGENT_JDBC_URL", "")
        require(jdbc.startswith("jdbc:mysql://"), "AGENT_JDBC_URL must be MySQL")
        parsed = urlsplit(jdbc.removeprefix("jdbc:"))
        self.database = parsed.path.lstrip("/")
        require(parsed.hostname in ("127.0.0.1", "localhost") and
                re.fullmatch(r"[A-Za-z0-9_]+_it", self.database),
                "Use an isolated local database whose name ends in _it")
        self.db_host, self.db_port = parsed.hostname, parsed.port or 3306
        self.redis_host = self.environment.get("AGENT_REDIS_HOST", "127.0.0.1")
        self.redis_port = int(self.environment.get("AGENT_REDIS_PORT", "6379"))
        require(self.redis_host in ("127.0.0.1", "localhost"), "Use an isolated local Redis")
        self.model = SimulatedModel()
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), self.model.handler())
        self.server.daemon_threads = True
        self.port = free_port()
        self.base = f"http://127.0.0.1:{self.port}/api/agent"
        self.process = None
        self.logs = []
        self.checks = []
        self.traces = []
        self.main_run = None
        self.report = {"status": "RUNNING", "startedAt": datetime.now(timezone.utc).isoformat(),
                       "businessData": "synthetic-demo", "model": "local-http-simulator",
                       "externalModelCalls": 0, "storage": "real-mysql-redis-private-files",
                       "traceSink": "file", "checks": self.checks}

    def api(self, method, path, body=None, expected=200, user="alice", key=None, headers=None):
        values = {"Content-Type": "application/json; charset=utf-8"}
        if user is not None:
            values["Authorization"] = "Bearer demo-" + user
        if key:
            values["Idempotency-Key"] = key
        values.update(headers or {})
        payload = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(self.base + path, data=payload, headers=values, method=method)
        try:
            with urllib.request.urlopen(request, timeout=25) as response:
                status, raw, content = response.status, response.read(), response.headers.get("Content-Type", "")
        except urllib.error.HTTPError as error:
            status, raw, content = error.code, error.read(), error.headers.get("Content-Type", "")
        require(status == expected, f"{method} {path}: expected {expected}, received {status}: {raw[:300]!r}")
        return json.loads(raw) if "json" in content else raw.decode("utf-8")

    def create(self, text, **fields):
        return self.api("POST", "/runs", {"message": text, **fields}, expected=202)

    def terminal(self, run):
        def result():
            value = self.api("GET", "/runs/" + run["runId"])
            return value if value["status"] not in ("RUNNING", "QUEUED") else None
        return wait_for(result)

    def done(self, run):
        stream = self.api("GET", "/runs/" + run["runId"] + "/events")
        require(stream.count("event:DONE") == 1, "Expected exactly one SSE DONE")
        sequences = [int(line[3:]) for line in stream.splitlines() if line.startswith("id:")]
        require(sequences == sorted(set(sequences)), "SSE sequences are not strictly increasing")
        return stream, sequences

    def check(self, name, **detail):
        self.checks.append({"name": name, "status": "PASS", **detail})
        print(json.dumps({"check": name, "status": "PASS", **detail}, ensure_ascii=True), flush=True)

    def sql(self, statement):
        env = dict(self.environment)
        env["MYSQL_PWD"] = env["AGENT_DB_PASSWORD"]
        command = [self.args.mysql_client, "--no-defaults", "--default-character-set=utf8mb4",
                   "--host=" + self.db_host, "--port=" + str(self.db_port),
                   "--user=" + env["AGENT_DB_USER"], "--database=" + self.database,
                   "--batch", "--raw", "--skip-column-names"]
        result = subprocess.run(command, input=statement.encode("utf-8"), capture_output=True,
                                env=env, timeout=15, **hidden_process())
        require(result.returncode == 0, "MySQL verification command failed")
        return result.stdout.decode("utf-8").strip()

    def redis(self, *arguments):
        def receive(reader):
            kind, value = reader.read(1), reader.readline().rstrip(b"\r\n")
            if kind == b"$":
                size = int(value)
                if size < 0:
                    return None
                payload = reader.read(size)
                require(reader.read(2) == b"\r\n", "Invalid Redis payload")
                return payload.decode("utf-8")
            if kind == b"*":
                return [receive(reader) for _ in range(int(value))]
            if kind == b":":
                return int(value)
            require(kind == b"+", "Redis command failed")
            return value.decode("utf-8")

        def send(connection, values):
            encoded = [str(value).encode("utf-8") for value in values]
            connection.sendall(b"*" + str(len(encoded)).encode() + b"\r\n" + b"".join(
                b"$" + str(len(value)).encode() + b"\r\n" + value + b"\r\n" for value in encoded))

        with socket.create_connection((self.redis_host, self.redis_port), timeout=3) as connection:
            reader = connection.makefile("rb")
            if self.environment.get("AGENT_REDIS_PASSWORD"):
                send(connection, ["AUTH", self.environment["AGENT_REDIS_PASSWORD"]])
                receive(reader)
            send(connection, arguments)
            return receive(reader)

    def start(self, label, redis_unavailable=False):
        self.stop()
        trace = self.output / ("trace-" + label)
        self.traces.append(trace)
        env = dict(self.environment, DEEPSEEK_API_KEY="simulation-only",
                   DEEPSEEK_BASE_URL=f"http://127.0.0.1:{self.server.server_port}",
                   PREPARATION_MODEL="mock-preparation", HARNESS_MODEL="mock-harness",
                   AGENT_OBJECT_DIRECTORY=str(self.output / "objects"), AGENT_REDIS_ENABLED="true")
        if redis_unavailable:
            env["AGENT_REDIS_PORT"] = str(free_port())
        log = (self.output / ("app-" + label + ".log")).open("wb")
        self.logs.append(log)
        command = [self.args.java, "-Dfile.encoding=UTF-8", "-Djava.io.tmpdir=" + str(self.output / "java-temp"),
                   "-jar", str(Path(self.args.jar).resolve()),
                   "--spring.profiles.active=" + getattr(self.args, "profiles", "demo,deepseek,persistent"), f"--server.port={self.port}",
                   "--harness.trace.sink=file", "--harness.trace.directory=" + str(trace),
                   "--agent.preparation.parallel-timeout=2s", "--agent.preparation.rewrite-timeout=1500ms",
                   "--agent.preparation.emotion-timeout=1s", "--harness.loop.model-timeout=2s",
                   "--harness.loop.request-timeout=20s"]
        self.process = subprocess.Popen(command, env=env, cwd=self.output, stdout=log,
                                        stderr=subprocess.STDOUT, **hidden_process())

        def ready():
            require(self.process.poll() is None, "Agent exited; inspect app-" + label + ".log")
            try:
                with socket.create_connection(("127.0.0.1", self.port), timeout=0.3):
                    return True
            except OSError:
                return False
        wait_for(ready, 60)

    def stop(self):
        if self.process and self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=5)

    def execute(self):
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        try:
            self.start("first")
            self.api("POST", "/runs", {"message": "你能做什么"}, user=None, expected=401)
            self.check("authentication")
            before = len(self.model.calls)
            faq = self.terminal(self.create("你能做什么"))
            require(faq["route"] == "FAQ" and faq["totalModelCalls"] == 0 and
                    len(self.model.calls) == before, "FAQ must bypass every model")
            self.done(faq)
            self.check("faq_short_circuit", modelCalls=0)
            sid = faq["sessionId"]
            clarification = self.terminal(self.create("查询视频1001的弹幕数", sessionId=sid))
            require(clarification["status"] == "NEEDS_INPUT" and clarification["totalModelCalls"] == 0,
                    "Missing date must ask for clarification without inventing a date")
            self.done(clarification)
            self.check("missing_date_clarification")
            body = {"message": "昨天", "sessionId": sid, "replyToRunId": clarification["runId"]}
            key = "e2e." + uuid.uuid4().hex
            before = len(self.model.calls)
            created = self.api("POST", "/runs", body, key=key, expected=202)
            with ThreadPoolExecutor(max_workers=1) as pool:
                streaming = pool.submit(self.api, "GET", "/runs/" + created["runId"] + "/events")
                metric = self.terminal(created)
                stream = streaming.result(timeout=10)
            self.main_run = metric
            require(metric["status"] == "SUCCEEDED" and metric["route"] == "HARNESS", "Harness failed")
            require("128" in metric["answer"]["text"] and "模拟" in metric["answer"]["text"],
                    "Answer must contain the demo value and disclosure")
            require(stream.count("event:DONE") == 1, "Live SSE must end exactly once")
            self.check("clarification_resume_harness", runId=metric["runId"], steps=metric["steps"],
                       preparationCalls=metric["preparationCalls"], modelCalls=metric["modelCalls"])
            calls = self.model.calls[before:]
            branches = [next(c for c in calls if c["stage"] == stage)
                        for stage in ("emotion", "clarification", "rewrite")]
            require(max(c["start"] for c in branches) < min(c["end"] for c in branches),
                    "Preparation branches did not overlap")
            first_harness = next(c for c in calls if c["stage"] == "harness")
            require(first_harness["start"] >= max(c["end"] for c in branches[:0:-1]),
                    "Harness started before critical preparation completed")
            memory = first_harness["input"]["memory"]
            require(len(memory["recent"]) == 4 and memory["boundary"], "History/focus boundary missing")
            self.check("parallel_preparation_and_history", recentMessages=len(memory["recent"]))
            require(self.api("POST", "/runs", body, key=key, expected=202)["runId"] == metric["runId"],
                    "Idempotent retry created a second run")
            self.api("POST", "/runs", {"message": "different"}, key=key, expected=409)
            self.api("POST", "/runs", body, expected=409)
            self.check("idempotency_and_single_use_resume")
            evidence_id = metric["answer"]["evidenceIds"][0]
            prefix = "/runs/" + metric["runId"]
            evidence = self.api("GET", prefix + "/evidence/" + evidence_id)
            require(evidence["data"]["value"] == 128 and evidence["data"]["demo"], "Evidence mismatch")
            artifacts = self.api("GET", prefix + "/artifacts")
            require(len(artifacts) == 1, "Expected a private evidence artifact")
            artifact = self.api("GET", prefix + "/artifacts/" + artifacts[0]["artifactId"])
            require(artifact["value"] == 128, "Artifact mismatch")
            raw = next(path for path in (self.output / "objects").rglob("*") if path.is_file()
                       and hashlib.sha256(path.read_bytes()).hexdigest() == artifacts[0]["sha256"])
            require(raw.stat().st_size == artifacts[0]["sizeBytes"], "Object integrity mismatch")
            require(len(self.api("GET", "/sessions/" + sid + "/messages")) == 6, "Formal message count mismatch")
            sql_id = metric["runId"]
            require(self.sql(f"SELECT COUNT(*) FROM agent_run_event WHERE run_id='{sql_id}' AND event_type='DONE'") == "1",
                    "Database contains duplicate DONE")
            require(self.sql(f"SELECT COUNT(*) FROM agent_message WHERE run_id='{sql_id}'") == "2",
                    "Database message pair mismatch")
            require(self.sql(f"SELECT COUNT(*) FROM agent_audit_event WHERE run_id='{sql_id}' AND action='RUN_COMMITTED'") == "1",
                    "Commit audit missing")
            self.check("mysql_messages_events_audit_and_private_artifact")
            _, sequences = self.done(metric)
            tail = self.api("GET", prefix + "/events", headers={"Last-Event-ID": str(sequences[-1] - 1)})
            require(tail.count("id:") == 1 and "event:DONE" in tail, "SSE cursor replay mismatch")
            self.check("live_sse_unique_done_and_cursor_replay")
            for path in (prefix, prefix + "/events", prefix + "/evidence/" + evidence_id,
                         prefix + "/artifacts", prefix + "/artifacts/" + artifacts[0]["artifactId"],
                         "/sessions/" + sid + "/messages"):
                self.api("GET", path, user="bob", expected=404)
            denied = self.terminal(self.create("查询视频1002昨天的弹幕数"))
            require(denied["status"] == "FAILED" and denied["errorCode"] == "FORBIDDEN", "Out-of-scope query was admitted")
            self.check("cross_user_and_video_scope_rejection")
            owner = "alice:" + hashlib.sha256(b"[1001]").hexdigest()
            source = f"4:demo{len(owner)}:{owner}{sid}"
            cache_key = "tianmu:memory:{" + hashlib.sha256(source.encode()).hexdigest() + "}:recent"
            cached = wait_for(lambda: self.redis("GET", cache_key))
            window = json.loads(cached.split("\n", 1)[1])
            require(len(window["messages"]) == 6 and 0 < self.redis("TTL", cache_key) <= 86400,
                    "Redis window or TTL mismatch")
            self.check("redis_window_and_ttl")
            self.model.mode = "rewrite-failure"
            degraded = self.terminal(self.create("查询视频1001昨天的弹幕数"))
            require(degraded["status"] == "SUCCEEDED" and "rewrite" in degraded["preparation"]["degradedStages"],
                    "Rewrite failure did not preserve the original query")
            self.check("rewrite_failure_fallback")
            self.model.mode = "unsafe-rewrite"
            unsafe = self.terminal(self.create("查询视频1001昨天的弹幕数"))
            require(unsafe["status"] == "SUCCEEDED" and "rewrite" in unsafe["preparation"]["degradedStages"] and
                    unsafe["task"]["specification"]["conditions"]["video_id"] == 1001,
                    "Unsafe rewrite changed the user's video")
            self.check("unsafe_rewrite_preserves_original_conditions")
            self.model.mode = "clarification-failure"
            before = len(self.model.calls)
            unavailable = self.terminal(self.create("查询视频1001昨天的弹幕数"))
            require(unavailable["status"] == "NEEDS_INPUT" and
                    "clarification" in unavailable["preparation"]["degradedStages"] and
                    not any(c["stage"] == "harness" for c in self.model.calls[before:]),
                    "Unavailable clarification must not silently enter Harness")
            self.check("clarification_failure_stops_before_harness")
            self.model.mode = "negative-emotion"
            negative = self.terminal(self.create("帮我查一下视频1001昨天的弹幕数，最近效果很差，有点着急"))
            require(negative["status"] == "SUCCEEDED" and negative["preparation"]["emotion"] == "NEGATIVE",
                    "Non-neutral emotion did not reach the prepared task")
            require(negative["preparation"]["query"].startswith("查询视频1001"), "Safe rewrite was not applied")
            self.check("negative_emotion_and_safe_rewrite")
            self.model.mode = "emotion-slow"
            started = time.monotonic()
            emotion = self.terminal(self.create("查询视频1001昨天的弹幕数"))
            elapsed = time.monotonic() - started
            require(emotion["status"] == "SUCCEEDED" and elapsed < 3 and
                    "emotion" in emotion["preparation"]["skippedStages"], "Emotion delayed the main task")
            self.check("slow_emotion_does_not_block", elapsedMs=round(elapsed * 1000))
            self.model.mode = "malformed"
            malformed = self.terminal(self.create("查询视频1001昨天的弹幕数"))
            require(malformed["status"] == "FAILED" and malformed["errorCode"] == "INVALID_DECISION",
                    "Malformed model output escaped validation")
            self.done(malformed)
            self.check("invalid_model_output_rejected")
            self.model.mode = "scope-escape"
            escape = self.terminal(self.create("查询视频1001昨天的弹幕数"))
            require(escape["status"] == "FAILED" and escape["errorCode"] == "FORBIDDEN",
                    "Model-generated tool arguments bypassed authorization")
            require(not self.api("GET", "/runs/" + escape["runId"] + "/artifacts"),
                    "Rejected tool call produced an artifact")
            self.check("model_tool_scope_escape_rejected")
            self.model.mode = "harness-slow"
            timeout_run = self.terminal(self.create("查询视频1001昨天的弹幕数"))
            require(timeout_run["status"] == "FAILED" and timeout_run["errorCode"] == "MODEL_CALL_TIMEOUT",
                    "Model timeout did not produce a bounded failure")
            self.done(timeout_run)
            self.check("model_timeout_unique_terminal")
            before = len(self.model.calls)
            slow = self.create("查询视频1001昨天的弹幕数")
            wait_for(lambda: any(c["stage"] == "harness" for c in self.model.calls[before:]))
            self.api("POST", "/runs/" + slow["runId"] + "/cancel")
            cancelled = self.terminal(slow)
            require(cancelled["status"] == "CANCELLED" and cancelled["stopReason"] == "USER_CANCELLED",
                    "Explicit cancellation lost terminal arbitration")
            self.done(cancelled)
            self.check("inflight_model_cancel_unique_terminal")
            self.model.mode = "normal"
            self.start("restart")
            restored = self.api("GET", prefix)
            require(restored["status"] == "SUCCEEDED" and restored["answer"] == metric["answer"],
                    "Completed result did not survive process restart")
            require(len(self.api("GET", "/sessions/" + sid + "/messages")) == 6, "History lost on restart")
            require(self.api("GET", prefix + "/artifacts/" + artifacts[0]["artifactId"]) == artifact,
                    "Artifact lost on restart")
            self.done(restored)
            require(self.api("POST", "/runs", body, key=key, expected=202)["runId"] == metric["runId"],
                    "Idempotency lost on restart")
            self.check("restart_restores_task_history_evidence_events_idempotency")
            self.start("redis-unavailable", redis_unavailable=True)
            continued = self.terminal(self.create("查询视频1001昨天的弹幕数", sessionId=sid))
            require(continued["status"] == "SUCCEEDED", "Redis failure blocked authoritative storage")
            latest = [c for c in self.model.calls if c["stage"] == "harness"][-1]
            require(len(latest["input"]["memory"]["recent"]) == 6, "MySQL history fallback failed")
            self.check("redis_unavailable_mysql_history_fallback")
            self.api("DELETE", "/sessions/" + sid, expected=202)
            self.api("GET", "/sessions/" + sid + "/messages", expected=404)
            self.api("GET", prefix, expected=404)
            self.api("GET", prefix + "/artifacts/" + artifacts[0]["artifactId"], expected=404)
            wait_for(lambda: not raw.exists(), 20)
            require(self.sql(f"SELECT COUNT(*) FROM agent_run WHERE session_id='{sid}'") == "0",
                    "Session database cleanup did not complete")
            self.check("session_delete_immediate_denial_and_object_gc")
            self.stop()  # Flush file Trace exporter before checking its output.
            records = []
            for trace in self.traces:
                for path in (trace / "archive").glob("*.jsonl"):
                    records.extend(json.loads(line) for line in path.read_text(encoding="utf-8").splitlines())
            main_trace = [r for r in records if r["traceId"] == metric["traceId"]]
            names = {r["name"] for r in main_trace}
            require({"agent.run", "agent.prepare.emotion", "agent.prepare.clarification", "agent.prepare.rewrite",
                     "agent.assemble", "agent.model", "agent.tool", "agent.skill"} <= names,
                    "End-to-end Trace stages missing")
            require(all(r.get("spanId") for r in main_trace), "Trace span IDs missing")
            self.check("trace_correlation", spans=len(main_trace))
            self.report.update(status="PASS", mainRunId=metric["runId"], traceId=metric["traceId"],
                               answer=metric["answer"], mainSteps=metric["steps"],
                               simulatedModelHttpCalls=len(self.model.calls))
        except Exception as error:
            self.report.update(status="FAIL", error=str(error))
            raise
        finally:
            self.stop()
            self.server.shutdown()
            self.server.server_close()
            for log in self.logs:
                log.close()
            self.report["finishedAt"] = datetime.now(timezone.utc).isoformat()
            (self.output / "report.json").write_text(json.dumps(self.report, ensure_ascii=False, indent=2), encoding="utf-8")


def hidden_process():
    return {"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True)
    parser.add_argument("--java", default=shutil.which("java") or "java")
    parser.add_argument("--mysql-client", default=shutil.which("mysql") or "mysql")
    parser.add_argument("--env-file", help="Untracked local JSON with AGENT_* storage settings")
    parser.add_argument("--output-dir", required=True, help="A new private output directory")
    FlowSimulation(parser.parse_args()).execute()


if __name__ == "__main__":
    main()
