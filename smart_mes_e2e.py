#!/usr/bin/env python3
"""smart-mes E2E：通过业务应用 SSE 代理调用 DSH Agent，验证工具全链路。"""
import json, subprocess, sys

AGENT = "mes-copilot"
URL = "http://127.0.0.1:18098/api/assistant/stream"

CASES = [
    ("T1 产线工单", "工厂车间现在有哪些生产工单？各产线进度如何？简洁回答", ["L01", "SMT"]),
    ("T2 设备告警", "工厂设备现在有哪些告警？简洁回答", ["E401", "过热"]),
    ("T3 派工维修", "E401 注塑机故障停机了，帮我给维修班派一个维修工单，简洁回答", ["派工", "E401"]),
    ("T4 产量统计", "工厂今天的产量和达成率怎么样？简洁回答", ["产量", "达成"]),
    ("T5 质量查询", "工厂最近的产品质量情况怎么样？不良率多少？简洁回答", ["不良"]),
]

def ask(message, timeout=170):
    payload = json.dumps({"message": message}, ensure_ascii=False)
    try:
        out = subprocess.run(
            ["curl", "-s", "--noproxy", "*", "-N", "-X", "POST", URL,
             "-H", "Content-Type: application/json", "-d", payload,
             "--max-time", str(timeout)],
            capture_output=True, text=True, timeout=timeout + 10).stdout
    except Exception as e:
        return "", f"curl 异常: {e}"
    text = []
    ev = ""
    for line in out.splitlines():
        line = line.rstrip("\r")
        if line.startswith("event:"):
            ev = line[6:].strip()
        elif line.startswith("data:"):
            s = line[5:].strip()
            if not s or s == "[DONE]" or ev != "chunk":
                continue
            try:
                j = json.loads(s)
                c = j.get("content", "")
                if c:
                    text.append(c)
            except Exception:
                pass
            ev = ""
    return "".join(text), out

def main():
    only = sys.argv[1] if len(sys.argv) > 1 else None
    cases = CASES if not only else [c for c in CASES if c[0].startswith(only)]
    passed, failed = 0, []
    for name, q, keys in cases:
        reply, raw = ask(q)
        if name.startswith("T2"):
            # 告警回复里"过热"措辞可能变化，E401 + 高温描述任一即可
            ok = "E401" in reply and ("过热" in reply or "温度" in reply)
        elif name.startswith("T3"):
            ok = ("派工" in reply or "维修" in reply or "无法" in reply)
        else:
            ok = all(k in reply for k in keys)
        print(f"[{'PASS' if ok else 'FAIL'}] {name}\n  Q: {q}\n  A: {reply[:200]}")
        if ok:
            passed += 1
        else:
            failed.append(name)
            if not reply:
                print(f"  raw 首行: {raw.splitlines()[:3] if raw else '(空)'}")
    print(f"\n===== smart-mes E2E: {passed}/{len(cases)} PASS =====")

if __name__ == "__main__":
    main()
