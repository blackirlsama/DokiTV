"""Real business JWT/Redis/permission/SQL integration against a dedicated local _it database.

Requires the root test fixture issuer and Agent/business bridge JARs. Model decisions are simulated
over HTTP; authentication, authorization, database queries and Agent persistence execute real code.
"""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import socket
import subprocess
import threading
import time
from urllib.parse import urlsplit

from simulate_flow import FlowSimulation, free_port, hidden_process, require, wait_for


class BusinessFlow(FlowSimulation):
    def __init__(self, args):
        args.profiles = "business,deepseek,persistent"
        super().__init__(args)
        self.fixture = json.loads(Path(args.fixture).read_text(encoding="utf-8"))
        self.tokens = self.fixture["tokens"]
        self.bridge_port = free_port()
        self.environment["BUSINESS_BASE_URL"] = f"http://127.0.0.1:{self.bridge_port}"
        self.bridge = None
        self.report.update(businessData="isolated-mysql-fixtures", authentication="business-jwt-and-online-redis-session",
                           businessService="real-business-bridge", queryDate=self.fixture["queryDate"])

    def api(self, method, path, body=None, expected=200, user="alice", key=None, headers=None):
        auth = {} if user is None else {"Authorization": "Bearer " + self.tokens.get(user, "invalid")}
        auth.update(headers or {})
        return super().api(method, path, body, expected, user=None, key=key, headers=auth)

    def business_sql(self, statement):
        previous = self.database, self.db_host, self.db_port, self.environment["AGENT_DB_USER"], self.environment["AGENT_DB_PASSWORD"]
        try:
            parsed = urlsplit(self.environment["BUSINESS_JDBC_URL"].removeprefix("jdbc:"))
            self.database = parsed.path.lstrip("/")
            require(parsed.hostname in ("127.0.0.1", "localhost") and self.database.endswith("_it"), "Use a dedicated local business integration database")
            self.db_host, self.db_port = parsed.hostname, parsed.port or 3306
            self.environment["AGENT_DB_USER"] = self.environment["BUSINESS_DB_USER"]
            self.environment["AGENT_DB_PASSWORD"] = self.environment["BUSINESS_DB_PASSWORD"]
            return self.sql(statement)
        finally:
            self.database, self.db_host, self.db_port, self.environment["AGENT_DB_USER"], self.environment["AGENT_DB_PASSWORD"] = previous

    def bridge_start(self):
        log = (self.output / "business-bridge.log").open("wb")
        self.logs.append(log)
        command = [self.args.java, "-Dfile.encoding=UTF-8", "-Djava.io.tmpdir=" + str(self.output / "java-temp"),
                   "-jar", str(Path(self.args.business_jar).resolve()), f"--server.port={self.bridge_port}"]
        self.bridge = subprocess.Popen(command, env=self.environment, cwd=self.output, stdout=log,
                                       stderr=subprocess.STDOUT, **hidden_process())

        def ready():
            require(self.bridge.poll() is None, "Business bridge exited; inspect business-bridge.log")
            try:
                with socket.create_connection(("127.0.0.1", self.bridge_port), timeout=0.3):
                    return True
            except OSError:
                return False
        wait_for(ready, 60)

    def execute(self):
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        try:
            self.bridge_start()
            self.start("business")
            self.api("POST", "/runs", {"message": "你能做什么"}, user=None, expected=401)
            self.api("POST", "/runs", {"message": "你能做什么"}, user="invalid", expected=401)
            self.check("real_jwt_authentication")
            faq = self.terminal(self.create("你能做什么"))
            require(faq["route"] == "FAQ" and faq["totalModelCalls"] == 0 and "模拟" not in faq["answer"]["text"], "Business FAQ mismatch")
            self.check("business_faq_without_model")
            sid = faq["sessionId"]
            clarification = self.terminal(self.create("查询视频1001的弹幕数", sessionId=sid))
            require(clarification["status"] == "NEEDS_INPUT", "Missing date was not clarified")
            self.check("business_clarification")
            metric = self.terminal(self.create("昨天", sessionId=sid, replyToRunId=clarification["runId"]))
            require(metric["status"] == "SUCCEEDED", "Business query failed: " + str(metric.get("errorCode")))
            require(metric["steps"] == 3 and metric["preparationCalls"] == 2 and metric["modelCalls"] == 3, "Unexpected execution chain")
            require("3 条" in metric["answer"]["text"] and "当前已入库且仍保留" in metric["answer"]["text"], "Metric answer or mandatory qualification missing")
            self.main_run = metric
            self.check("real_business_harness_query", runId=metric["runId"], steps=metric["steps"])
            prefix = "/runs/" + metric["runId"]
            evidence = self.api("GET", prefix + "/evidence/" + metric["answer"]["evidenceIds"][0])
            require(evidence["data"]["value"] == self.fixture["referenceCount"] == 3 and not evidence["data"]["demo"], "Business evidence differs from independent SQL")
            require(evidence["data"]["source"] == "mysql:bullet:retained-persisted-v1", "Synthetic adapter was used")
            self.check("independent_sql_reconciliation", expected=3, toolValue=evidence["data"]["value"],
                       cumulativeCounterFixture=self.fixture["storedCounter"])
            artifacts = self.api("GET", prefix + "/artifacts")
            require(self.api("GET", prefix + "/artifacts/" + artifacts[0]["artifactId"])["value"] == 3, "Business artifact mismatch")
            self.done(metric)
            require(len(self.api("GET", "/sessions/" + sid + "/messages")) == 6, "Business history missing")
            self.check("business_evidence_artifact_history_sse")
            for path in (prefix, prefix + "/artifacts", "/sessions/" + sid + "/messages"):
                self.api("GET", path, user="bob", expected=404)
            self.api("POST", "/runs", {"message": "查询视频1002昨天的弹幕数", "userId": "882"}, expected=403)
            self.check("forged_user_and_foreign_video_rejected")
            self.business_sql("UPDATE video SET user_id=882 WHERE video_id=1001")
            try:
                self.api("GET", prefix, expected=403)
                self.api("GET", prefix + "/artifacts", expected=403)
                visible = self.api("GET", "/sessions/" + sid + "/messages")
                require(len(visible) == 2 and all(message["runId"] == faq["runId"] for message in visible), "Revoked video leaked through history")
                self.check("ownership_change_revokes_saved_results_and_history")
            finally:
                self.business_sql("UPDATE video SET user_id=881 WHERE video_id=1001")
            before = len(self.model.calls)
            pending = self.create("查询视频1001昨天的弹幕数")
            wait_for(lambda: any(call["stage"] == "harness" for call in self.model.calls[before:]))
            self.business_sql("UPDATE video SET user_id=882 WHERE video_id=1001")
            try:
                def failed():
                    return self.sql("SELECT status FROM agent_run WHERE run_id='" + pending["runId"] + "'") == "FAILED"
                wait_for(failed)
                require(self.sql("SELECT JSON_UNQUOTE(JSON_EXTRACT(snapshot_json,'$.errorCode')) FROM agent_run WHERE run_id='" + pending["runId"] + "'") == "FORBIDDEN", "Tool query failed to recheck current ownership")
                self.check("ownership_change_before_tool_call_is_rechecked")
            finally:
                self.business_sql("UPDATE video SET user_id=881 WHERE video_id=1001")
            before = len(self.model.calls)
            revoked = self.create("查询视频1001昨天的弹幕数")
            wait_for(lambda: any(call["stage"] == "harness" for call in self.model.calls[before:]))
            self.redis("DEL", "881")
            try:
                wait_for(lambda: self.sql("SELECT status FROM agent_run WHERE run_id='" + revoked["runId"] + "'") == "FAILED")
                require(self.sql("SELECT JSON_UNQUOTE(JSON_EXTRACT(snapshot_json,'$.errorCode')) FROM agent_run WHERE run_id='" + revoked["runId"] + "'") == "UNAUTHORIZED", "Tool query failed to recheck online session")
                self.api("GET", prefix, expected=401)
                self.check("logout_revokes_running_tool_and_result_reads")
            finally:
                self.redis("SETEX", "881", "600", self.tokens["alice"])
            self.start("business-restart")
            restored = self.api("GET", prefix)
            require(restored["answer"] == metric["answer"], "Business result did not survive Agent restart")
            self.done(restored)
            self.check("business_result_survives_restart_with_fresh_authentication")
            fresh = self.terminal(self.create("查询视频1001昨天的弹幕数", sessionId=sid))
            require(fresh["status"] == "SUCCEEDED", "Fresh credential binding after restart failed")
            self.check("new_business_query_after_restart")
            self.stop()
            for path in self.output.rglob("*"):
                if path.is_file() and path.suffix in (".json", ".jsonl", ".log"):
                    text = path.read_text(encoding="utf-8", errors="replace")
                    require(all(token not in text for token in self.tokens.values()), "Raw login token leaked into persisted output")
            persisted = self.sql("SELECT access_scope_json FROM agent_run WHERE run_id='" + metric["runId"] + "'")
            require(all(token not in persisted for token in self.tokens.values()), "Raw login token persisted in scope")
            self.check("raw_tokens_absent_from_snapshots_logs_traces_and_objects")
            self.report.update(status="PASS", mainRunId=metric["runId"], traceId=metric["traceId"],
                               answer=metric["answer"], expectedValue=3, referenceValue=self.fixture["referenceCount"],
                               simulatedModelHttpCalls=len(self.model.calls))
        except Exception as error:
            self.report.update(status="FAIL", error=str(error))
            raise
        finally:
            self.stop()
            if self.bridge and self.bridge.poll() is None:
                self.bridge.terminate()
                try:
                    self.bridge.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    self.bridge.kill()
                    self.bridge.wait(timeout=5)
            self.server.shutdown()
            self.server.server_close()
            for log in self.logs:
                log.close()
            self.report["finishedAt"] = datetime.now(timezone.utc).isoformat()
            (self.output / "report.json").write_text(json.dumps(self.report, ensure_ascii=False, indent=2), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True)
    parser.add_argument("--business-jar", required=True)
    parser.add_argument("--fixture", required=True, help="Private output of BusinessFixtureMain")
    parser.add_argument("--java", required=True)
    parser.add_argument("--mysql-client", required=True)
    parser.add_argument("--env-file", required=True)
    parser.add_argument("--output-dir", required=True)
    BusinessFlow(parser.parse_args()).execute()


if __name__ == "__main__":
    main()
