import json
import pathlib
import re
import sys
from datetime import datetime
from datetime import date as date_cls
from datetime import time as time_cls

import requests


def read_dotenv(path: pathlib.Path) -> dict:
    out = {}
    if not path.exists():
        return out
    for line in path.read_text(encoding="utf-8").splitlines():
        s = line.strip()
        if not s or s.startswith("#") or "=" not in s:
            continue
        k, v = s.split("=", 1)
        out[k.strip()] = v.strip()
    return out


def build_url(base_url: str) -> str:
    b = base_url.strip().rstrip("/")
    if not b.endswith("/v1"):
        b = b + "/v1"
    return b + "/chat/completions"


def sanitize_json_object(text: str) -> str:
    if not text:
        return "{}"
    s = text.strip()
    if s.startswith("```"):
        first = s.find("{")
        last = s.rfind("}")
        if first >= 0 and last > first:
            return s[first : last + 1].strip()
    first = s.find("{")
    last = s.rfind("}")
    if first >= 0 and last > first:
        return s[first : last + 1].strip()
    return s


def main() -> int:
    root = pathlib.Path(__file__).resolve().parents[1]
    env = read_dotenv(root / ".env")
    api_key = env.get("OPENAI_API_KEY") or env.get("spring.ai.openai.api-key") or ""
    base_url = env.get("BASE_URL") or env.get("spring.ai.openai.base-url") or ""
    model = env.get("MODEL") or env.get("spring.ai.openai.chat.options.model") or "qwen-max"

    if not api_key or not base_url:
        print("missing OPENAI_API_KEY or BASE_URL in .env", file=sys.stderr)
        return 2

    plan_date = env.get("PLAN_DATE", "2026-04-13")
    d = datetime.strptime(plan_date, "%Y-%m-%d").date()
    dow = d.isoweekday()

    csv_path = root / "test-data" / "schedule.csv"
    rows = []
    if csv_path.exists():
        lines = [ln.strip() for ln in csv_path.read_text(encoding="utf-8").splitlines() if ln.strip()]
        if len(lines) >= 2:
            header = [h.strip().replace("\ufeff", "") for h in lines[0].split(",")]
            idx = {name: i for i, name in enumerate(header)}
            for ln in lines[1:]:
                cols = [c.strip() for c in ln.split(",")]
                try:
                    course = cols[idx.get("课程")]
                    row_dow = int(cols[idx.get("星期")])
                    start = cols[idx.get("开始时间")]
                    end = cols[idx.get("结束时间")]
                    if row_dow and row_dow == dow:
                        rows.append((course, start, end))
                except Exception:
                    continue

    def parse_hhmm(s: str) -> int:
        s = s.strip()
        if len(s) == 4 and s[1] == ":":
            s = "0" + s
        hh, mm = s.split(":")
        return int(hh) * 60 + int(mm)

    study_start = 8 * 60
    study_end = 22 * 60
    occupied = []
    for _, st, et in rows:
        a = parse_hhmm(st)
        b = parse_hhmm(et)
        if b <= a:
            continue
        a = max(a, study_start)
        b = min(b, study_end)
        if b > a:
            occupied.append((a, b))
    occupied.sort()
    merged = []
    for a, b in occupied:
        if not merged:
            merged.append([a, b])
            continue
        la, lb = merged[-1]
        if a <= lb:
            merged[-1][1] = max(lb, b)
        else:
            merged.append([a, b])

    free_slots = []
    cur = study_start
    for a, b in merged:
        if a > cur:
            free_slots.append({"start": f"{plan_date}T{cur//60:02d}:{cur%60:02d}:00", "end": f"{plan_date}T{a//60:02d}:{a%60:02d}:00"})
        cur = max(cur, b)
    if cur < study_end:
        free_slots.append({"start": f"{plan_date}T{cur//60:02d}:{cur%60:02d}:00", "end": f"{plan_date}T{study_end//60:02d}:{study_end%60:02d}:00"})

    if not free_slots:
        free_slots = [{"start": f"{plan_date}T08:00:00", "end": f"{plan_date}T22:00:00"}]
    tasks = [
        {"id": 101, "title": "理解极限与连续", "estimatedMinutes": 60, "priority": 3},
        {"id": 102, "title": "完成练习题", "estimatedMinutes": 45, "priority": 2},
        {"id": 103, "title": "研究连续性的概念", "estimatedMinutes": 30, "priority": 1},
    ]

    prompt = (
        "你是一个学习计划排程助手。请把任务分散安排到 freeSlots 中，严禁与课表冲突（必须完全落在 freeSlots 内）。\n"
        "请避免精疲力尽：采用 45 分钟学习 + 10 分钟休息的节奏，当天学习总时长不超过 240 分钟。\n\n"
        "输出必须是严格 JSON（不要 Markdown、不要额外文字），格式：\n"
        '{"note":"说明","candidateSchedules":[{"taskId":123,"startTime":"YYYY-MM-DDTHH:mm:ss","endTime":"YYYY-MM-DDTHH:mm:ss"}]}\n\n'
        f"planDate: {plan_date}\n"
        f"freeSlots: {json.dumps(free_slots, ensure_ascii=False)}\n"
        f"tasks: {json.dumps(tasks, ensure_ascii=False)}\n"
    )

    url = build_url(base_url)
    body = {"model": model, "temperature": 0.2, "messages": [{"role": "user", "content": prompt}]}
    resp = requests.post(
        url,
        headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        timeout=60,
    )
    print("status", resp.status_code)
    data = resp.json()
    content = (((data.get("choices") or [{}])[0].get("message") or {}).get("content")) or ""
    print("\nraw_content:\n" + content)

    cleaned = sanitize_json_object(content)
    try:
        parsed = json.loads(cleaned)
        print("\nparsed_ok schedules=", len(parsed.get("candidateSchedules") or []))
        print(json.dumps(parsed, ensure_ascii=False, indent=2))
    except Exception as e:
        print("\njson_parse_failed:", str(e), file=sys.stderr)
        print("cleaned:\n" + cleaned, file=sys.stderr)
        return 3

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
