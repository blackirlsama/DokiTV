"""Render a JSONL trace and its private, sanitized snapshots as a standalone call tree.

Example: python scripts/render_trace.py --trace var/trace/archive/spans.jsonl
The exported HTML includes snapshot contents; share it only with authorized readers.
No third-party Python dependencies or external page resources are used.
"""
import argparse
import hashlib
import html
import json
from datetime import datetime, timezone
from pathlib import Path


def escape(value):
    return html.escape(str(value))


def read_payload(run_id, reference, directory):
    try:
        span, direction, length, digest = reference.split("/")
        key = hashlib.sha256(f"{run_id}/{span}/{direction}/{digest}".encode()).hexdigest()
        body = (directory / f"td_{key}.json").read_bytes()
        if len(body) != int(length) or hashlib.sha256(body).hexdigest() != digest:
            raise ValueError("integrity mismatch")
        return json.loads(body)
    except (OSError, ValueError, KeyError):
        return {"state": "UNAVAILABLE", "message": "详情未下载或文件校验失败"}


STATES = {"COMPLETE": "完整", "TRUNCATED": "已截断", "NOT_CAPTURED": "未采集", "NOT_REPORTED": "未提供",
          "PARTIAL": "部分提供", "REPORTED": "已提供", "NOT_APPLICABLE": "不适用", "DISABLED": "已关闭",
          "CAPTURE_FAILED": "采集失败", "LIMIT_REACHED": "达到采集限额", "UNAVAILABLE": "不可用", "SUCCEEDED": "成功", "FAILED": "失败",
          "TIMED_OUT": "超时", "CANCELLED": "已取消", "RUNNING": "运行中", "SKIPPED": "已跳过"}


def render(trace_path, output, details_dir=None, run_id=None):
    spans = [json.loads(line) for line in trace_path.read_text(encoding="utf-8").splitlines() if line.strip()]
    roots = [s for s in spans if s["name"] == "agent.run" and (not run_id or s.get("attributes", {}).get("run.id") == run_id)]
    if not roots:
        raise ValueError("No agent.run root found")
    request = max(roots, key=lambda s: s["startTimeUnixNano"])
    run_id = request["attributes"]["run.id"]
    spans = [s for s in spans if s["traceId"] == request["traceId"]]
    # eventId identifies transport replay; spanId identifies a node in this trace.
    spans = list({s["spanId"]: s for s in spans}.values())
    by_id = {s["spanId"]: s for s in spans}
    children = {s["spanId"]: [] for s in spans}
    top = []
    for span in spans:
        if span.get("parentSpanId") in by_id:
            children[span["parentSpanId"]].append(span)
        else:
            top.append(span)
    for group in [top, *children.values()]:
        group.sort(key=lambda s: (s["startTimeUnixNano"], s["spanId"]))
    start = min(s["startTimeUnixNano"] for s in spans)
    end = max(s["endTimeUnixNano"] for s in spans)
    extent = max(1, end - start)
    details_dir = details_dir or trace_path.parent.parent / "details"

    def item(title, value):
        return f'<div class="fact"><span>{escape(title)}</span><b>{escape(value)}</b></div>'

    def payload(span, direction, title):
        attrs = span.get("attributes", {})
        state = attrs.get(f"detail.{direction}_state", "NOT_CAPTURED")
        reference = attrs.get(f"detail.{direction}_ref")
        snapshot = read_payload(run_id, reference, details_dir) if reference and reference != "UNAVAILABLE" else {}
        state = snapshot.get("state", state)
        text = STATES.get(state, state)
        if snapshot.get("redacted"):
            text += " · 已脱敏"
        value = snapshot.get("value", snapshot.get("preview", snapshot.get("message")))
        body = '<p class="muted">无可用详情</p>' if value is None else '<pre>' + escape(json.dumps(value, ensure_ascii=False, indent=2) if not isinstance(value, str) else value) + '</pre>'
        count = snapshot.get("originalBytes", attrs.get(f"detail.{direction}_bytes"))
        if count is not None:
            text += f" · {count:,} bytes"
        kind = snapshot.get("kind", "")
        return f'<details class="payload"><summary>{escape(title)}<span class="payload-state">{escape(text)}</span></summary><div class="muted kind">{escape(kind)}</div>{body}</details>'

    def node(span, ancestry=frozenset()):
        if span["spanId"] in ancestry:
            raise ValueError("Trace contains a parent cycle")
        attrs = span.get("attributes", {})
        typ = attrs.get("node.type", "UNKNOWN")
        status = attrs.get("node.status", "FAILED" if span.get("status") == "ERROR" else "UNKNOWN")
        ms = span["durationNanos"] / 1e6
        left = max(0, (span["startTimeUnixNano"] - start) / extent * 100)
        width = max(.3, min(100 - left, span["durationNanos"] / extent * 100))
        extra = attrs.get("tool.name", attrs.get("skill.name", attrs.get("span.type", "")))
        facts = item("节点类型", typ) + item("状态", STATES.get(status, status)) + item("耗时", f"{ms:.2f} ms")
        for key, title in [("execution.queue_ms", "排队"), ("execution.active_ms", "实际执行"), ("execution.timeout_ms", "超时预算"), ("execution.attempt", "第几次尝试"), ("execution.retry_count", "重试次数"), ("loop.step", "Agent 轮次")]:
            if key in attrs:
                facts += item(title, str(attrs[key]) + (" ms" if key.endswith("_ms") else ""))
        if typ == "MODEL":
            facts += item("模型", attrs.get("model.name", "未提供")) + item("服务商", attrs.get("model.provider", "未提供"))
            for key, title in [("model.input_tokens", "输入 token"), ("model.output_tokens", "输出 token"), ("model.total_tokens", "总 token"), ("model.cache_hit_tokens", "缓存命中 token"), ("model.cache_miss_tokens", "缓存未命中 token"), ("model.reasoning_tokens", "reasoning token")]:
                facts += item(title, attrs.get(key, "未提供"))
            facts += item("Token 统计状态", STATES.get(attrs.get("model.usage_state"), "未提供"))
            facts += item("结束原因", attrs.get("model.finish_reason", "未提供"))
            facts += item("首 token 耗时", STATES.get(attrs.get("model.first_token_state"), "未提供"))
            for key, title in [("model.temperature", "temperature"), ("model.max_tokens", "max_tokens"), ("model.message_count", "消息数"), ("model.streaming", "流式调用")]:
                if key in attrs:
                    facts += item(title, attrs[key])
        if "error.code" in attrs:
            facts += item("错误码", attrs["error.code"])
        stamp = lambda nanos: datetime.fromtimestamp(nanos / 1e9, timezone.utc).isoformat(timespec="milliseconds")
        identifiers = f'<div class="identifiers">开始 {escape(stamp(span["startTimeUnixNano"]))} · 结束 {escape(stamp(span["endTimeUnixNano"]))}<br>spanId {escape(span["spanId"])} · parentSpanId {escape(span.get("parentSpanId", ""))}</div>'
        io = payload(span, "input", "输入") + payload(span, "output", "输出")
        for direction, title in [("provider_output", "模型服务原始响应"), ("error", "异常详情")]:
            if f"detail.{direction}_state" in attrs:
                io += payload(span, direction, title)
        nested = ''.join(node(child, ancestry | {span["spanId"]}) for child in children[span["spanId"]])
        raw = escape(json.dumps(span, ensure_ascii=False, indent=2))
        return f'''<details class="span" {'open' if not ancestry or typ in ('REQUEST','LOOP') else ''}>
<summary><span class="name"><span class="badge {escape(typ)}">{escape(typ)}</span><strong>{escape(span['name'])}</strong><small>{escape(extra)}</small></span><span class="timeline"><i style="left:{left:.3f}%;width:{width:.3f}%"></i></span><span class="duration">{ms:.2f} ms</span><span class="status {escape(status)}">{escape(STATES.get(status,status))}</span></summary>
<div class="body"><div class="facts">{facts}</div>{identifiers}<div class="io">{io}</div><details class="raw"><summary>完整 span 元数据</summary><pre>{raw}</pre></details><div class="children">{nested}</div></div></details>'''

    tree = ''.join(node(span) for span in top)
    if sum(1 for _ in by_id) != len(spans):
        raise ValueError("Duplicate span IDs")
    rootattrs = request.get("attributes", {})
    totals = item("span 数量", len(spans)) + item("请求耗时", f'{request["durationNanos"]/1e6:.2f} ms')
    totals += item("输入 token", rootattrs.get("usage.input_tokens", "未提供")) + item("输出 token", rootattrs.get("usage.output_tokens", "未提供"))
    totals += item("Token 统计状态", STATES.get(rootattrs.get("usage.state"), "未提供"))
    page = '''<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Agent Trace 调用详情</title><style>
*{box-sizing:border-box}body{margin:0;padding:24px;background:#f3f6f9;color:#213748;font:14px/1.55 system-ui,-apple-system,"Segoe UI",sans-serif}main{max-width:1440px;margin:auto}.panel{background:white;border:1px solid #dfe7ed;border-radius:14px;margin-bottom:16px;padding:22px}h1{margin:5px 0 12px;font-size:25px}.muted{color:#728491;font-size:12px}.facts{display:grid;grid-template-columns:repeat(auto-fit,minmax(140px,1fr));gap:8px;margin:10px 0}.fact{padding:10px 12px;border-radius:8px;background:#f1f6f8;min-width:0}.fact span{display:block;color:#71818d;font-size:11px}.fact b{display:block;font-size:14px;overflow-wrap:anywhere}.toolbar{display:flex;gap:8px;align-items:center;margin-bottom:14px}button{padding:7px 12px;border:1px solid #cbdbe4;background:#f5f9fb;border-radius:7px;color:#20566c;cursor:pointer}.span{border-top:1px solid #e8edf1}.span>summary{display:grid;grid-template-columns:minmax(220px,1.2fr) minmax(120px,1fr) 80px 55px;align-items:center;gap:10px;padding:10px 5px;cursor:pointer;list-style:none}.span>summary::-webkit-details-marker{display:none}.span>summary:hover{background:#f5f9fb}.name{display:flex;flex-wrap:wrap;align-items:center;gap:6px}.name strong{font-size:13px}.name small{color:#7b8c96;font-size:11px}.badge{border-radius:5px;padding:2px 5px;background:#e9eef3;color:#617482;font-size:9px;font-weight:750}.MODEL{background:#eee8fa;color:#7452a4}.TOOL{background:#e5f4ef;color:#247f62}.SKILL{background:#fff2dc;color:#94661a}.REQUEST{background:#dfeef6;color:#226180}.status{font-size:10px;color:#71818d;text-align:right}.SUCCEEDED{color:#20775a}.FAILED,.TIMED_OUT{color:#b54040}.duration{text-align:right;color:#607786;font-size:12px;font-variant-numeric:tabular-nums}.timeline{position:relative;height:12px;border-left:1px solid #d4dfe5;background:repeating-linear-gradient(to right,#fff,#fff calc(25% - 1px),#e7edf1 calc(25% - 1px),#e7edf1 25%)}.timeline i{position:absolute;height:7px;top:2px;border-radius:4px;min-width:3px;background:#2789a0}.body{padding:2px 6px 14px 18px}.identifiers{font:10px/1.7 ui-monospace,Consolas,monospace;color:#80929d;overflow-wrap:anywhere;margin:10px 0}.io{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}.payload{background:#f9fbfc;border:1px solid #e0e8ed;border-radius:9px;padding:9px 12px;min-width:0}.payload>summary{cursor:pointer;color:#205f78;font-weight:650;font-size:12px}.payload-state{margin-left:10px;font-size:10px;color:#7b8e99;font-weight:400}.kind{margin-top:8px}.raw{margin:10px 0;color:#6e8390;font-size:11px}.raw>summary{cursor:pointer}pre{max-height:410px;overflow:auto;margin:9px 0 0;background:#f0f4f7;padding:10px;border-radius:6px;color:#2b4658;font:11px/1.6 ui-monospace,Consolas,monospace;white-space:pre-wrap;overflow-wrap:anywhere}.children{margin:12px 0 0 8px;padding-left:12px;border-left:2px solid #e2eaf0}.lead{color:#637d8c;margin:4px 0;font-size:12px}.traceid{overflow-wrap:anywhere;font:11px/1.7 ui-monospace,Consolas,monospace}.notice{padding:9px 12px;background:#fff7e8;border-radius:7px;color:#805e24;font-size:12px;margin:12px 0}@media(max-width:800px){body{padding:10px}.panel{padding:14px}.timeline{display:none}.span>summary{grid-template-columns:minmax(160px,1fr) 70px 45px;gap:5px}.io{grid-template-columns:1fr}.body{padding-left:5px}.children{margin-left:1px;padding-left:5px}}
</style></head><body><main><header class="panel"><div class="muted">TRACE INSPECTOR · 本地导出预览</div><h1>Agent 调用树与节点详情</h1><div class="traceid">runId: __RUN__<br>traceId: __TRACE__</div><div class="facts">__TOTALS__</div><p class="lead">点击节点展开输入、输出、token 与执行信息。时间条显示并行关系；输入输出采用经过脱敏的私有快照。</p><div class="notice">模型使用本地 Demo 时，token 和服务商参数会显示“未提供”。不会把未知用量记为 0。当前模型适配器使用非流式请求，首 token 耗时标为“不适用”。</div></header><section class="panel"><div class="toolbar"><button id="expand">展开所有节点</button><button id="collapse">收起所有节点</button><span class="muted">属性缺失与执行失败分别展示</span></div>__TREE__</section></main><script>document.getElementById('expand').onclick=()=>document.querySelectorAll('details.span').forEach(x=>x.open=true);document.getElementById('collapse').onclick=()=>document.querySelectorAll('details.span').forEach(x=>x.open=false);</script></body></html>'''
    page = page.replace('__RUN__', escape(run_id)).replace('__TRACE__', escape(request['traceId'])).replace('__TOTALS__', totals).replace('__TREE__', tree)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(page, encoding='utf-8')
    return {"runId": run_id, "traceId": request["traceId"], "spanCount": len(spans), "modelNodes": sum(s.get("attributes", {}).get("node.type") == "MODEL" for s in spans), "output": str(output)}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--trace', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=Path('var/demo-trace-call-tree.html'))
    parser.add_argument('--details', type=Path)
    parser.add_argument('--run-id')
    args = parser.parse_args()
    print(json.dumps(render(args.trace, args.output, args.details, args.run_id), ensure_ascii=False))
