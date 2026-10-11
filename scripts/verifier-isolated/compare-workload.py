#!/usr/bin/env python3
"""V1 실행과 stock 실행이 같은 workload 계약(ADR-001: 고유 최초 요청 N + 결정적 10% 중복 재요청)을 보냈는지 원본 로그로 대조한다.
사용: compare-workload.py <v1_run_dir> <v1_member_start> <stock_run_dir> <stock_user_start>
비교: 최초 요청 수, offset 집합(연속 0..N-1), 앱 분배(offset%2), 중복 offset 집합과 대상 앱(다른 앱), 중복 비율. 같으면 exit 0, 다르면 exit 1.
요청 시각/지연/응답은 비교하지 않는다(구조·환경이 다름). 실행하지 않은 부하의 결과를 만들지 않는다 - 입력은 이미 존재하는 원본 로그뿐이다.
"""
import json, pathlib, re, sys

MSG = re.compile(r'msg="(.*)"\s*$')
def parse(path):
    out = []
    for raw in pathlib.Path(path).read_text(errors="replace").splitlines():
        line = raw.strip(); m = MSG.search(line)
        payload = m.group(1) if m else (line if line.startswith("{") else None)
        if payload is None: continue
        try: out.append(json.loads(payload.replace('\\"', '"').replace("\\\\", "\\")))
        except ValueError: pass
    return out

def v1_contract(run_dir, start):
    recs = parse(pathlib.Path(run_dir) / "k6-failures.log")
    first = {r["memberId"] - start: r["target"] for r in recs if r.get("kind") == "admission"}
    dup = {r["memberId"] - start: r["target"] for r in recs if r.get("kind") == "duplicate"}
    return first, dup

def stock_contract(run_dir, start):
    recs = [r for r in parse(pathlib.Path(run_dir) / "k6-failures.log") if r.get("kind") == "stock_request"]
    first = {r["userId"] - start: r["target"] for r in recs if r.get("attempt", 1) == 1}
    dup = {r["userId"] - start: r["target"] for r in recs if r.get("attempt", 1) == 2}
    return first, dup

def describe(name, first, dup):
    n = len(first)
    return {"name": name, "first_requests": n, "offsets_contiguous_0_to_N-1": sorted(first) == list(range(n)),
            "app_split(target1,target2)": [sum(1 for t in first.values() if t == 1), sum(1 for t in first.values() if t == 2)],
            "duplicates": len(dup), "dup_rate": round(len(dup) / n, 4) if n else None,
            "dup_target_is_other_app": all(dup[o] != first.get(o) for o in dup)}

if __name__ == "__main__":
    v1f, v1d = v1_contract(sys.argv[1], int(sys.argv[2])); sf, sd = stock_contract(sys.argv[3], int(sys.argv[4]))
    a, b = describe("v1", v1f, v1d), describe("stock", sf, sd)
    same = {"first_count": len(v1f) == len(sf), "first_offsets": set(v1f) == set(sf), "first_target_assignment": v1f == sf,
            "dup_offsets": set(v1d) == set(sd), "dup_targets": v1d == sd}
    result = {"v1": a, "stock": b, "identical": same, "verdict": "SAME_WORKLOAD_CONTRACT" if all(same.values()) else "DIFFERENT"}
    print(json.dumps(result, indent=2, ensure_ascii=False))
    sys.exit(0 if all(same.values()) else 1)
