"""One real-model acceptance request. Requires DEEPSEEK_API_KEY in the environment.

Uses the same isolated business fixture, real JWT/Redis authentication and real SQL service as
verify_business_flow.py. This script makes billable provider requests (bounded by the Agent budget).
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import socket
import subprocess
from verify_business_flow import BusinessFlow
from simulate_flow import hidden_process, require, wait_for


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ("jar","business-jar","fixture","java","mysql-client","env-file","output-dir"):
        parser.add_argument("--"+name,required=True)
    parser.add_argument("--existing-run",help="Verify a completed task without repeating provider requests")
    parser.add_argument("--source-trace",help="Original stopped process's Trace directory when verifying an existing task")
    args=parser.parse_args()
    require(bool(os.environ.get("DEEPSEEK_API_KEY")),"Set DEEPSEEK_API_KEY in the environment")
    flow=BusinessFlow(args)
    flow.report.update(model="real-provider",externalModelCalls=None)
    try:
        flow.bridge_start()
        log=(flow.output/"agent-real-model.log").open("wb"); flow.logs.append(log)
        trace=flow.output/"trace-real-model"
        command=[args.java,"-Dfile.encoding=UTF-8","-Djava.io.tmpdir="+str(flow.output/"java-temp"),"-jar",str(Path(args.jar).resolve()),
                 "--spring.profiles.active=business,deepseek,persistent",f"--server.port={flow.port}",
                 "--harness.trace.sink=file","--harness.trace.directory="+str(trace)]
        flow.process=subprocess.Popen(command,env=flow.environment,cwd=flow.output,stdout=log,stderr=subprocess.STDOUT,**hidden_process())
        def ready():
            require(flow.process.poll() is None,"Agent exited; inspect agent-real-model.log")
            try:
                with socket.create_connection(("127.0.0.1",flow.port),timeout=.3):return True
            except OSError:return False
        wait_for(ready,60)
        created={"runId":args.existing_run} if args.existing_run else flow.create("查询视频1001昨天的弹幕数")
        def terminal():
            result=flow.api("GET","/runs/"+created["runId"])
            return result if result["status"] not in ("QUEUED","RUNNING") else None
        result=wait_for(terminal,160)
        flow.report.update(mainRunId=result["runId"],traceId=result["traceId"],snapshot=result,externalModelCalls=result["totalModelCalls"])
        require(result["status"]=="SUCCEEDED","Real provider task ended with "+str(result.get("errorCode") or result["status"]))
        evidence=flow.api("GET","/runs/"+result["runId"]+"/evidence/"+result["answer"]["evidenceIds"][0])
        require(evidence["data"]["value"]==3 and not evidence["data"]["demo"],"Real model cited incorrect SQL evidence")
        require(evidence["data"]["date"]==flow.fixture["queryDate"],"Evidence date differs from the original query")
        text=result["answer"]["text"].replace("*","").replace("`","")
        require(bool(re.search(r"弹幕(?:数|数量|记录数)?\s*(?:为|是|[:：])?\s*3(?=\D|$)",text)),"Real model answer did not match the known count")
        require("当前已入库且仍保留" in text,"Mandatory data scope qualification missing")
        flow.done(result); flow.check("real_model_business_sql_evidence_answer")
        flow.stop()
        records=[]
        source=Path(args.source_trace).resolve() if args.source_trace else trace
        for path in (source/"archive").glob("*.jsonl"):
            records.extend(json.loads(line) for line in path.read_text(encoding="utf-8").splitlines())
        if args.source_trace:
            for path in (source/"pending").glob("*.pending"):
                records.append(json.loads(path.read_text(encoding="utf-8")))
        records=list({r["eventId"]:r for r in records}.values())
        spans=[r for r in records if r["traceId"]==result["traceId"]]
        models=sorted({r["attributes"]["model.name"] for r in spans if "model.name" in r["attributes"]})
        require(len(models)==2,"Expected separate preparation and Harness models")
        flow.report.update(status="PASS",answer=result["answer"],models=models,
                           providerRequestsThisVerification=0 if args.existing_run else result["totalModelCalls"],
                           observedInputTokens=sum(r["attributes"].get("model.input_tokens",0) for r in spans),
                           observedOutputTokens=sum(r["attributes"].get("model.output_tokens",0) for r in spans))
    except Exception as error:
        flow.report.update(status="FAIL",error=str(error)); raise
    finally:
        flow.stop()
        if flow.bridge and flow.bridge.poll() is None:
            flow.bridge.terminate()
            try:flow.bridge.wait(timeout=10)
            except subprocess.TimeoutExpired:flow.bridge.kill();flow.bridge.wait(timeout=5)
        flow.server.server_close()
        for log in flow.logs:log.close()
        flow.report["finishedAt"]=datetime.now(timezone.utc).isoformat()
        (flow.output/"report.json").write_text(json.dumps(flow.report,ensure_ascii=False,indent=2),encoding="utf-8")
        print(json.dumps({"status":flow.report["status"],"models":flow.report.get("models",[]),"modelCalls":flow.report.get("externalModelCalls")}),flush=True)


if __name__=="__main__":main()
