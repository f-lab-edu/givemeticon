#!/usr/bin/env python3
"""E0 측정 run의 접수 요청 시간 분해(묶음 대기 → 커넥션 획득 → 잠금 SELECT → 잠금 후 작업 → 커밋)와
발급 워커·MySQL 행 잠금 지표를 한 표로 정리한다. 계산만 하며 판단 문장은 출력하지 않는다.

사용: analyze-bottleneck.py <출력 디렉터리> <run 디렉터리> [<run 디렉터리> ...]
      analyze-bottleneck.py <출력 디렉터리> --group <라벨> <run 디렉터리>... [--group <라벨> <run 디렉터리>...]
      (--group 형태는 그룹별 평균·표준편차·최소·최대를 담은 J 표를 만들고, run 수가 많아지는 I 표는 만들지 않는다)
출력: <출력 디렉터리>/bottleneck-summary.json, bottleneck-summary.md
입력(run 디렉터리): app{1,2}-{before,quiescent,after}.prom, fast-prom.csv, fast-mysql-locks.csv, mysql.csv, run.env

계산 규칙
- Prometheus 누적값(_count/_sum/_bucket)은 항상 (quiescent − before) 차분이다. 음수 차분 시리즈는 결과에서 빼고 경고에 남긴다.
- _max는 Micrometer 감쇠 창 최대값이라 차분할 수 없다. quiescent/after 값을 참고용으로만 기록한다.
- 분위수는 histogram_quantile과 같은 방식(누적 bucket 선형 보간, 첫 구간 하한 0, +Inf 구간은 아래 유한 le 반환)이다.
  두 앱 합산은 count/sum/bucket을 먼저 더한 뒤 계산한다(앱별 분위수를 평균하지 않는다).
"""
import csv
import json
import math
import os
import re
import statistics
import sys

# 실제 결과 파일(http_server_requests_seconds_count)에서 확인한 접수 POST 엔드포인트 라벨 값이다.
ADMISSION_URI = "/test-support/coupon-events/{eventId}/applications"
APPS = ("app1", "app2")
ENV_KEYS = ("run_id", "kind", "rate", "duration", "monitor_lock_introspection",
            "issuance_worker_enabled", "admission_batch_size", "issuance_batch_size")  # 출력 허용 키(비밀값 방지)
UNRECORDED = "미기록(기본값으로 실행됨)"

# 이름 -> (지표 기본 이름, 라벨 필터, 그룹 라벨, 단위 배율, 단위)
SPECS = {
    "http_admission_post": ("http_server_requests_seconds", {"method": "POST", "uri": ADMISSION_URI}, ("status", "outcome"), 1000.0, "ms"),
    "queue_wait": ("coupon_admission_batch_queue_wait_seconds", {}, (), 1000.0, "ms"),
    "hikari_acquire": ("hikaricp_connections_acquire_seconds", {}, (), 1000.0, "ms"),
    "lock_select": ("coupon_admission_select_for_update_seconds", {}, ("caller", "outcome"), 1000.0, "ms"),
    "post_lock_work": ("coupon_admission_post_lock_work_seconds", {}, (), 1000.0, "ms"),
    "commit": ("coupon_admission_commit_seconds", {}, (), 1000.0, "ms"),
    "admission_tx": ("coupon_admission_transaction_seconds", {}, ("outcome",), 1000.0, "ms"),
    "issuance_tx": ("coupon_issuance_transaction_seconds", {}, ("outcome",), 1000.0, "ms"),
    "admission_batch_size": ("coupon_admission_batch_size", {}, (), 1.0, "items"),
    "issuance_batch_size": ("coupon_issuance_batch_size", {}, (), 1.0, "items"),
    "existing_lookup_batch_size": ("coupon_admission_existing_lookup_batch_size", {}, (), 1.0, "items"),
}

# 차분 대상: 표에서 쓰는 지표의 누적 시리즈만. 그 밖의 *_sum(예: http_server_requests_active_seconds_duration_sum은
# 진행 중 요청의 합이라 감소할 수 있는 값)은 누적값이 아니므로 음수 경고에서 제외한다.
DELTA_NAMES = frozenset(base + suffix for base, *_ in SPECS.values() for suffix in ("_count", "_sum", "_bucket"))

FIXED_NOTE = ("분위수는 구간끼리 더할 수 없다. 구간 비중 비교에는 평균을 사용한다. "
              "잠금 SELECT 시간에는 InnoDB 잠금 대기 외에 DB 실행·네트워크 시간이 포함된다.")


# ---------------------------------------------------------------- Prometheus 파싱
def parse_prom_text(text):
    """{(이름, ((라벨, 값), ...정렬)): float}. 형식이 깨진 줄은 건너뛰고 개수를 센다."""
    out = {}
    bad = 0
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        try:
            m = re.match(r"[A-Za-z_:][A-Za-z0-9_:]*", line)
            if not m:
                raise ValueError("name")
            name, i, labels = m.group(0), m.end(), {}
            if i < len(line) and line[i] == "{":
                i += 1
                while line[i] != "}":
                    if line[i] in ", ":
                        i += 1
                        continue
                    eq = line.index("=", i)
                    key = line[i:eq].strip()
                    i = eq + 1
                    if line[i] != '"':
                        raise ValueError("label")
                    i += 1
                    val = []
                    while line[i] != '"':
                        if line[i] == "\\":
                            val.append("\n" if line[i + 1] == "n" else line[i + 1])
                            i += 2
                        else:
                            val.append(line[i])
                            i += 1
                    i += 1
                    labels[key] = "".join(val)
                i += 1
            value = float(line[i:].split()[0])
        except (ValueError, IndexError):
            bad += 1
            continue
        out[(name, tuple(sorted(labels.items())))] = value
    return out, bad


def read_prom(path, warnings):
    try:
        with open(path, encoding="utf-8") as f:
            parsed, bad = parse_prom_text(f.read())
    except OSError:
        warnings["missing_file"].append(os.path.basename(path))
        return None
    if bad:
        warnings["parse_errors"].append({"file": os.path.basename(path), "lines": bad})
    return parsed


def delta_snapshot(before, quiescent, app, warnings):
    """누적 시리즈의 (quiescent − before). 음수 차분은 결과에서 빼고 경고에 남긴다."""
    out = {}
    for key, q in quiescent.items():
        name = key[0]
        if name not in DELTA_NAMES or math.isnan(q):
            continue
        d = q - before.get(key, 0.0)
        if d < 0:
            warnings["negative_delta"].append({"app": app, "series": name, "labels": dict(key[1])})
            continue
        out[key] = d
    return out


# ---------------------------------------------------------------- 히스토그램
class Hist:
    def __init__(self):
        self.count = 0.0
        self.sum = 0.0
        self.buckets = {}  # le(float) -> 누적 count

    def add(self, other):
        self.count += other.count
        self.sum += other.sum
        for le, c in other.buckets.items():
            self.buckets[le] = self.buckets.get(le, 0.0) + c
        return self


def collect(deltas, base, flt, group_by):
    """차분 시리즈에서 base 지표의 {그룹 튜플: Hist}를 모은다(필터 라벨이 맞는 시리즈만)."""
    groups = {}
    for (name, labels), v in deltas.items():
        if name not in (base + "_count", base + "_sum", base + "_bucket"):
            continue
        lab = dict(labels)
        if any(lab.get(k) != want for k, want in flt.items()):
            continue
        h = groups.setdefault(tuple(lab.get(k) for k in group_by), Hist())
        if name.endswith("_count"):
            h.count += v
        elif name.endswith("_sum"):
            h.sum += v
        else:
            le = float(lab["le"])
            h.buckets[le] = h.buckets.get(le, 0.0) + v
    return groups


def merge(groups, pred=lambda g: True):
    total = Hist()
    for g, h in groups.items():
        if pred(g):
            total.add(h)
    return total


def quantile(hist, q):
    """(값 또는 None, 플래그). 플래그: None / upper_bound_hit / non_monotonic / no_buckets / empty."""
    if not hist.buckets:
        return None, "no_buckets"
    items = sorted(hist.buckets.items())
    cums = [c for _, c in items]
    if any(b < a for a, b in zip(cums, cums[1:])):
        return None, "non_monotonic"
    total = cums[-1]
    if total <= 0:
        return None, "empty"
    rank = q * total
    prev_le, prev_c = 0.0, 0.0
    for le, c in items:
        if c >= rank:
            if math.isinf(le):
                finite = [x for x, _ in items if not math.isinf(x)]
                return (finite[-1] if finite else None), "upper_bound_hit"
            if c == prev_c:
                return le, None
            return prev_le + (le - prev_le) * (rank - prev_c) / (c - prev_c), None
        prev_le, prev_c = le, c
    return None, "empty"


def stats(hist, scale, unit, ctx, warnings):
    """한 Hist의 count/평균/분위수 요약. 분위수 경고는 ctx 이름으로 경고 목록에 남긴다."""
    out = {"unit": unit, "count": hist.count, "mean": (hist.sum / hist.count * scale) if hist.count else None,
           "p50": None, "p95": None, "p99": None, "quantile_flags": {}}
    for name, q in (("p50", 0.50), ("p95", 0.95), ("p99", 0.99)):
        val, flag = quantile(hist, q)
        out[name] = None if val is None else val * scale
        if flag in ("upper_bound_hit", "non_monotonic"):
            out["quantile_flags"][name] = flag
            key = "upper_bound_hit" if flag == "upper_bound_hit" else "non_monotonic_buckets"
            warnings[key].append({"metric": ctx, "quantile": name})
    return out


def max_reference(snaps, base, flt):
    """_max 시리즈(감쇠 창 최대값)의 quiescent/after 값. 참고용이며 차분하지 않는다."""
    out = {}
    for which in ("quiescent", "after"):
        snap = snaps.get(which)
        vals = []
        if snap:
            for (name, labels), v in snap.items():
                lab = dict(labels)
                if name == base + "_max" and all(lab.get(k) == w for k, w in flt.items()):
                    vals.append(v)
        out[which] = max(vals) if vals else None
    return out


# ---------------------------------------------------------------- CSV 시계열
def num(x):
    try:
        v = float(x)
        return None if math.isnan(v) else v
    except (TypeError, ValueError):
        return None


def read_rows(path, warnings):
    try:
        with open(path, newline="", encoding="utf-8") as f:
            return list(csv.DictReader(f))
    except OSError:
        warnings["missing_file"].append(os.path.basename(path))
        return None


def first_last(rows, col):
    vals = [v for v in (num(r.get(col)) for r in rows) if v is not None]
    return (vals[0], vals[-1]) if vals else (None, None)


def timeseries(run_dir, warnings):
    ts = {"hikari": {}, "mysql_locks": None, "mysql_csv": None}
    rows = read_rows(os.path.join(run_dir, "fast-prom.csv"), warnings)
    if rows is not None:
        for app in APPS:
            mine = [r for r in rows if r.get("app") == app]
            pend = [v for v in (num(r.get("pending")) for r in mine) if v is not None]
            act = [v for v in (num(r.get("active")) for r in mine) if v is not None]
            first, last = first_last(mine, "timeout_total")
            ts["hikari"][app] = {
                "pending_max": max(pend) if pend else None,
                "active_max": max(act) if act else None,
                "samples_pending_gt0": sum(1 for v in pend if v > 0),
                "samples_total": len(pend),
                "error_rows": len(mine) - len(pend),
                "timeout_total_delta": (last - first) if first is not None else None,
            }
    rows = read_rows(os.path.join(run_dir, "fast-mysql-locks.csv"), warnings)
    if rows is not None:
        w0, w1 = first_last(rows, "row_lock_waits")
        t0, t1 = first_last(rows, "row_lock_time_ms")
        cur = [v for v in (num(r.get("row_lock_current_waits")) for r in rows) if v is not None]
        dw = (w1 - w0) if w0 is not None else None
        dt = (t1 - t0) if t0 is not None else None
        if (dw is not None and dw < 0) or (dt is not None and dt < 0):
            warnings["negative_delta"].append({"app": "mysql", "series": "fast-mysql-locks.csv", "labels": {}})
            dw = dt = None
        ts["mysql_locks"] = {
            "samples": len(rows), "delta_row_lock_waits": dw, "delta_row_lock_time_ms": dt,
            "avg_wait_ms": (dt / dw) if (dw and dw > 0 and dt is not None) else None,
            "row_lock_current_waits_max": max(cur) if cur else None,
        }
    rows = read_rows(os.path.join(run_dir, "mysql.csv"), warnings)
    if rows is not None:
        o0, o1 = first_last(rows, "innodb_os_log_written")
        ts["mysql_csv"] = {"samples": len(rows), "delta_innodb_os_log_written": (o1 - o0) if o0 is not None else None}
    return ts


# ---------------------------------------------------------------- run 단위 분석
def read_env(run_dir):
    env = {}
    try:
        with open(os.path.join(run_dir, "run.env"), encoding="utf-8") as f:
            for line in f:
                if "=" in line:
                    k, v = line.strip().split("=", 1)
                    if k in ENV_KEYS:
                        env[k] = v
    except OSError:
        pass
    return env


def analyze_run(run_dir):
    warnings = {"negative_delta": [], "non_monotonic_buckets": [], "upper_bound_hit": [], "missing_file": [], "parse_errors": []}
    env = read_env(run_dir)
    label = env.get("run_id") or os.path.basename(os.path.normpath(run_dir))
    m = re.search(r"stock(\d+)-(\d+)", label)
    result = {"label": label, "env": env, "stock_total": m.group(1) if m else None, "apps_used": [], "metrics": {},
              "max_reference": {}, "not_computed": []}

    deltas, raw = {}, {}
    for app in APPS:
        before = read_prom(os.path.join(run_dir, f"{app}-before.prom"), warnings)
        quiescent = read_prom(os.path.join(run_dir, f"{app}-quiescent.prom"), warnings)
        after = read_prom(os.path.join(run_dir, f"{app}-after.prom"), warnings)
        if before is None or quiescent is None:
            continue
        deltas[app] = delta_snapshot(before, quiescent, app, warnings)
        raw[app] = {"quiescent": quiescent, "after": after}
        result["apps_used"].append(app)

    for name, (base, flt, group_by, scale, unit) in SPECS.items():
        per_app = {app: collect(deltas[app], base, flt, group_by) for app in deltas}
        combined_groups = {}
        for groups in per_app.values():
            for g, h in groups.items():
                combined_groups.setdefault(g, Hist()).add(h)
        entry = {"group_by": list(group_by), "combined": {}, "per_app": {}}
        entry["combined"]["all"] = stats(merge(combined_groups), scale, unit, f"{name}/combined", warnings)
        entry["combined"]["groups"] = {"|".join(g): stats(h, scale, unit, f"{name}/combined/{'|'.join(g)}", warnings)
                                       for g, h in sorted(combined_groups.items())}
        for app, groups in per_app.items():
            entry["per_app"][app] = {
                "all": stats(merge(groups), scale, unit, f"{name}/{app}", warnings),
                "groups": {"|".join(g): stats(h, scale, unit, f"{name}/{app}/{'|'.join(g)}", warnings)
                           for g, h in sorted(groups.items())},
            }
        # 합산 기준 표에서 쓰는 부분 집계
        if name == "lock_select":
            for caller in ("admission", "issuance"):
                entry["combined"][f"caller={caller}"] = stats(
                    merge(combined_groups, lambda g, c=caller: g[0] == c), scale, unit, f"{name}/combined/caller={caller}", warnings)
        result["metrics"][name] = entry
        result["max_reference"][name] = {app: max_reference(raw[app], base, flt) for app in raw}

    for name in ("hikari_acquire", "admission_batch_size", "issuance_batch_size", "existing_lookup_batch_size"):
        result["not_computed"].append({"metric": f"{name} p50/p95/p99", "reason": "_bucket 시리즈가 없어 평균(sum/count)만 계산"})
    result["timeseries"] = timeseries(run_dir, warnings)
    result["derived"] = derive(result)
    result["warnings"] = warnings
    return result


# ---------------------------------------------------------------- 파생 값(대기 배수 등)
def ratio(num, den):
    return (num / den) if (num is not None and den is not None and den > 0) else None


def derive(run):
    """잠금 보유 시간(잠금 후 작업 평균 + 커밋 평균), 대기 배수, InnoDB/앱 비율, 트랜잭션 수. 계산 불가면 None."""
    m = run["metrics"]
    post, commit = m["post_lock_work"]["combined"]["all"]["mean"], m["commit"]["combined"]["all"]["mean"]
    lock_avg = m["lock_select"]["combined"]["caller=admission"]["mean"]
    hold = (post + commit) if (post is not None and commit is not None) else None
    innodb = (run["timeseries"]["mysql_locks"] or {}).get("avg_wait_ms")
    issuance_groups = m["issuance_tx"]["combined"]["groups"]
    return {
        "lock_hold_ms": hold,
        "wait_multiple": ratio(lock_avg, hold),
        "innodb_avg_wait_ms": innodb,
        "innodb_to_app_ratio": ratio(innodb, lock_avg),
        "admission_tx_count": m["admission_tx"]["combined"]["all"]["count"],
        "issuance_tx_count": sum(issuance_groups.get(k, {"count": 0})["count"] for k in ("committed", "empty")),
    }


def group_stats(values):
    """None을 뺀 값의 평균·표본 표준편차·최소·최대. 값이 하나면 표준편차는 None."""
    vals = [v for v in values if isinstance(v, (int, float))]
    return {"n": len(vals), "mean": statistics.mean(vals) if vals else None,
            "stdev": statistics.stdev(vals) if len(vals) >= 2 else None,
            "min": min(vals) if vals else None, "max": max(vals) if vals else None}


# ---------------------------------------------------------------- 표 구성
F_ROWS = (
    ("http 접수 POST 전체", "http_admission_post", "all"),
    ("묶음 대기", "queue_wait", "all"),
    ("커넥션 획득", "hikari_acquire", "all"),
    ("잠금 SELECT (caller=admission)", "lock_select", "caller=admission"),
    ("잠금 후 작업", "post_lock_work", "all"),
    ("커밋", "commit", "all"),
    ("접수 트랜잭션 전체", "admission_tx", "all"),
)


def fmt(v, digits=3):
    if v is None:
        return "-"
    if isinstance(v, float) and v == int(v) and abs(v) < 1e12:
        return str(int(v))
    return f"{v:.{digits}f}" if isinstance(v, float) else str(v)


def md_table(headers, rows):
    cell = lambda c: str(c).replace("|", "/")  # 그룹 이름의 `|` 구분자가 표 열을 깨지 않게 한다(JSON은 원문 유지)
    lines = ["| " + " | ".join(cell(h) for h in headers) + " |", "|" + "|".join("---" for _ in headers) + "|"]
    lines += ["| " + " | ".join(cell(c) for c in r) + " |" for r in rows]
    return "\n".join(lines)


def stat_cells(s):
    flag = lambda k: "*" if k in s["quantile_flags"] else ""  # * = 상한 구간(+Inf)에 걸린 값
    return [fmt(s["count"]), fmt(s["mean"]), fmt(s["p50"]) + flag("p50"), fmt(s["p95"]) + flag("p95"), fmt(s["p99"]) + flag("p99")]


def comparison_rows(run):
    """I 표용 {행 이름: 값}."""
    rows = {}
    for title, metric, key in F_ROWS:
        s = run["metrics"][metric]["combined"][key]
        rows[f"{title} 평균(ms)"] = s["mean"]
        rows[f"{title} p95(ms)"] = s["p95"]
    ls = run["metrics"]["lock_select"]["combined"]["caller=issuance"]
    rows["잠금 SELECT (caller=issuance) 평균(ms)"] = ls["mean"]
    rows["발급 batch size 평균"] = run["metrics"]["issuance_batch_size"]["combined"]["all"]["mean"]
    hik = run["timeseries"]["hikari"]
    for app in APPS:
        if app in hik:
            rows[f"hikari pending 최대 ({app})"] = hik[app]["pending_max"]
            rows[f"hikari pending>0 표본 수 ({app})"] = hik[app]["samples_pending_gt0"]
    lk = run["timeseries"]["mysql_locks"] or {}
    rows["Δrow_lock_waits"] = lk.get("delta_row_lock_waits")
    rows["Δrow_lock_time_ms"] = lk.get("delta_row_lock_time_ms")
    rows["잠금 1회당 평균 대기(ms)"] = lk.get("avg_wait_ms")
    rows["row_lock_current_waits 최대"] = lk.get("row_lock_current_waits_max")
    rows["Δinnodb_os_log_written"] = (run["timeseries"]["mysql_csv"] or {}).get("delta_innodb_os_log_written")
    d = run["derived"]
    rows["잠금 보유 시간(ms)"] = d["lock_hold_ms"]
    rows["대기 배수"] = d["wait_multiple"]
    rows["InnoDB/앱 비율"] = d["innodb_to_app_ratio"]
    rows["접수 트랜잭션 수"] = d["admission_tx_count"]
    rows["발급 트랜잭션 수(committed+empty)"] = d["issuance_tx_count"]
    rows["접수 batch size 평균"] = run["metrics"]["admission_batch_size"]["combined"]["all"]["mean"]
    return rows


J_ROWS = ("대기 배수", "잠금 SELECT (caller=admission) 평균(ms)", "잠금 보유 시간(ms)", "묶음 대기 평균(ms)", "묶음 대기 p95(ms)",
          "http 접수 POST 전체 p95(ms)", "잠금 1회당 평균 대기(ms)", "접수 트랜잭션 수", "발급 트랜잭션 수(committed+empty)",
          "접수 batch size 평균")


def group_summary(runs, groups):
    """groups: [(라벨, [run 라벨...])] -> {라벨: {"runs": [...], "stats": {행: group_stats}, "values": {행: [...]}}}"""
    by_label = {r["label"]: r for r in runs}
    out = {}
    for name, labels in groups:
        members = [by_label[l] for l in labels]
        rows = [comparison_rows(r) for r in members]
        values = {k: [row.get(k) for row in rows] for k in J_ROWS}
        out[name] = {"runs": labels, "values": values, "stats": {k: group_stats(v) for k, v in values.items()},
                     "conditions": {key: sorted({r["env"].get(key, UNRECORDED) for r in members})
                                    for key in ("issuance_worker_enabled", "admission_batch_size", "issuance_batch_size")}
                                   | {"stock_total": sorted({r["stock_total"] or "-" for r in members})}}
    return out


def render_j(runs, groups):
    summary = group_summary(runs, groups)
    names = list(summary)
    out = ["## J. 조건 비교 (그룹별 run 값의 평균·표본 표준편차·최소·최대)", "",
           "행 = 지표×통계, 열 = 그룹. n은 값이 있는 run 수다. 표준편차는 n이 2 이상일 때만 계산한다.", ""]
    out.append(md_table(["그룹", "run 수", "STOCK_TOTAL", "issuance_worker_enabled", "admission_batch_size", "issuance_batch_size"],
                        [[n, len(summary[n]["runs"]), ",".join(summary[n]["conditions"]["stock_total"]),
                          ",".join(summary[n]["conditions"]["issuance_worker_enabled"]),
                          ",".join(summary[n]["conditions"]["admission_batch_size"]),
                          ",".join(summary[n]["conditions"]["issuance_batch_size"])] for n in names]))
    rows = []
    for key in J_ROWS:
        for stat, title in (("mean", "평균"), ("stdev", "표준편차"), ("min", "최소"), ("max", "최대"), ("n", "n")):
            rows.append([key, title] + [fmt(summary[n]["stats"][key][stat]) for n in names])
    out += ["", md_table(["지표", "통계"] + names, rows), "", "### 대기 배수 run별 값", ""]
    out.append(md_table(["그룹", "run별 값(실행 순서)"],
                        [[n, ", ".join(fmt(v) for v in summary[n]["values"]["대기 배수"])] for n in names]))
    out.append("")
    return out


def render_i(runs):
    out = []
    baseline = [r for r in runs if r["env"].get("monitor_lock_introspection") != "true"]
    monitored = [r for r in runs if r["env"].get("monitor_lock_introspection") == "true"]
    cols = baseline + monitored
    per_run = {r["label"]: comparison_rows(r) for r in cols}
    names = list(per_run[cols[0]["label"]]) if cols else []
    headers = ["지표"] + [r["label"] for r in baseline] + ["baseline 평균", "baseline 표준편차"] + [r["label"] for r in monitored]
    rows = []
    for n in names:
        base_vals = [per_run[r["label"]].get(n) for r in baseline]
        nums = [v for v in base_vals if isinstance(v, (int, float))]
        mean = statistics.mean(nums) if len(nums) == len(base_vals) and nums else None
        sd = statistics.stdev(nums) if len(nums) == len(base_vals) and len(nums) >= 2 else None
        rows.append([n] + [fmt(v) for v in base_vals] + [fmt(mean), fmt(sd)] + [fmt(per_run[r["label"]].get(n)) for r in monitored])
    out += ["## I. run 비교 (baseline = monitor_lock_introspection≠true인 run, 별도 열은 평균에 섞지 않음)", "", md_table(headers, rows), ""]

    return out


def render_markdown(runs, groups=None):
    out = ["# 접수·발급 구간 분해 (계산 결과)", ""]
    out.append(md_table(["run", "RATE", "DURATION", "STOCK_TOTAL", "monitor_lock_introspection",
                         "issuance_worker_enabled", "admission_batch_size", "issuance_batch_size"],
                        [[r["label"], r["env"].get("rate", "-"), r["env"].get("duration", "-"), r["stock_total"] or "-",
                          r["env"].get("monitor_lock_introspection", "-"), r["env"].get("issuance_worker_enabled", UNRECORDED),
                          r["env"].get("admission_batch_size", UNRECORDED), r["env"].get("issuance_batch_size", UNRECORDED)]
                         for r in runs]))
    out += ["", "두 앱 합산 기준이며 모든 누적 지표는 (quiescent − before) 차분이다. 분위수 열의 `*`는 +Inf 구간에 걸려 "
            "바로 아래 유한 bucket 값을 쓴 경우다. 행마다 count의 단위가 다르다(http·묶음 대기·커넥션 획득은 요청/획득 1건, "
            "나머지 트랜잭션 계열은 플러시 1건). 커넥션 획득은 풀 전체의 획득 횟수다.", ""]
    for r in runs:
        out += [f"## {r['label']}", "", "### F. 접수 시간 분해", ""]
        out.append(md_table(["구간", "count", "평균(ms)", "p50(ms)", "p95(ms)", "p99(ms)"],
                            [[t] + stat_cells(r["metrics"][m]["combined"][k]) for t, m, k in F_ROWS]))
        d = r["derived"]
        out += ["", FIXED_NOTE, "", "#### 잠금 대기 배수 (합산 기준)", ""]
        out.append(md_table(["항목", "값"], [
            ["잠금 보유 시간(ms) = 잠금 후 작업 평균 + 커밋 평균", fmt(d["lock_hold_ms"])],
            ["대기 배수 = 잠금 SELECT(caller=admission) 평균 ÷ 잠금 보유 시간", fmt(d["wait_multiple"])],
            ["InnoDB 잠금 1회당 평균 대기(ms, MySQL 기준)", fmt(d["innodb_avg_wait_ms"])],
            ["InnoDB/앱 비율 = InnoDB 1회당 평균 대기 ÷ 잠금 SELECT(caller=admission) 평균", fmt(d["innodb_to_app_ratio"])],
            ["접수 트랜잭션 수", fmt(d["admission_tx_count"])],
            ["발급 트랜잭션 수 (committed + empty)", fmt(d["issuance_tx_count"])]]))
        out += ["", "대기 배수는 잠금을 기다린 시간이 앞선 트랜잭션의 작업 시간의 몇 배인지를 나타낸다.", "",
                "#### http 접수 POST (status / outcome별)", ""]
        out.append(md_table(["status|outcome", "count", "평균(ms)", "p50(ms)", "p95(ms)", "p99(ms)"],
                            [[g] + stat_cells(s) for g, s in r["metrics"]["http_admission_post"]["combined"]["groups"].items()]))
        out += ["", "#### 잠금 SELECT (caller|outcome별)", ""]
        out.append(md_table(["caller|outcome", "count", "평균(ms)", "p50(ms)", "p95(ms)", "p99(ms)"],
                            [[g] + stat_cells(s) for g, s in r["metrics"]["lock_select"]["combined"]["groups"].items()]))
        out += ["", "### G. 발급", ""]
        g_rows = [["잠금 SELECT (caller=issuance)"] + stat_cells(r["metrics"]["lock_select"]["combined"]["caller=issuance"])]
        for g, s in r["metrics"]["issuance_tx"]["combined"]["groups"].items():
            g_rows.append([f"발급 트랜잭션 outcome={g}"] + stat_cells(s))
        out.append(md_table(["구간", "count", "평균(ms)", "p50(ms)", "p95(ms)", "p99(ms)"], g_rows))
        ib = r["metrics"]["issuance_batch_size"]["combined"]["all"]
        out += ["", f"발급 batch size 평균: {fmt(ib['mean'])} (count {fmt(ib['count'])})", "", "### H. 묶음 크기", ""]
        out.append(md_table(["지표", "count", "평균"],
                            [[t, fmt(r["metrics"][m]["combined"]["all"]["count"]), fmt(r["metrics"][m]["combined"]["all"]["mean"])]
                             for t, m in (("접수 batch size", "admission_batch_size"),
                                          ("기존 접수 조회 batch size", "existing_lookup_batch_size"),
                                          ("발급 batch size", "issuance_batch_size"))]))
        out += ["", "### E. 시계열 지표", ""]
        e_rows = []
        for app, h in r["timeseries"]["hikari"].items():
            e_rows.append([f"hikari {app}", fmt(h["pending_max"]), fmt(h["active_max"]),
                           f"{h['samples_pending_gt0']}/{h['samples_total']}", fmt(h["timeout_total_delta"])])
        out.append(md_table(["대상", "pending 최대", "active 최대", "pending>0 표본/전체", "Δtimeout_total"], e_rows))
        lk, mc = r["timeseries"]["mysql_locks"], r["timeseries"]["mysql_csv"]
        if lk:
            out += ["", md_table(["Δrow_lock_waits", "Δrow_lock_time_ms", "잠금 1회당 평균 대기(ms)", "row_lock_current_waits 최대", "표본 수"],
                                 [[fmt(lk["delta_row_lock_waits"]), fmt(lk["delta_row_lock_time_ms"]), fmt(lk["avg_wait_ms"]),
                                   fmt(lk["row_lock_current_waits_max"]), fmt(lk["samples"])]])]
        if mc:
            out += ["", f"Δinnodb_os_log_written: {fmt(mc['delta_innodb_os_log_written'])} (mysql.csv 표본 {mc['samples']})"]
        out += ["", "#### 참고용 _max (감쇠 창, 차분 불가: quiescent / after, ms)", ""]
        ref_rows = []
        for m_name, apps in r["max_reference"].items():
            scale = SPECS[m_name][3]
            for app, v in apps.items():
                q, a = v["quiescent"], v["after"]
                if q is not None or a is not None:
                    ref_rows.append([m_name, app, fmt(None if q is None else q * scale), fmt(None if a is None else a * scale)])
        out.append(md_table(["지표", "app", "quiescent", "after"], ref_rows))
        out.append("")

    # I. run 비교 (그룹 모드에서는 run 수가 많아 J 표로 대체한다)
    if groups is None:
        out.extend(render_i(runs))
    else:
        out.extend(render_j(runs, groups))
    out += ["## 경고", ""]
    any_warning = False
    for r in runs:
        for kind, items in r["warnings"].items():
            if items:
                any_warning = True
                out.append(f"- {r['label']} / {kind}: {json.dumps(items, ensure_ascii=False)}")
    if not any_warning:
        out.append("- 없음")
    out += ["", "## 계산하지 않은 지표", ""]
    seen = set()
    for r in runs:
        for item in r["not_computed"]:
            if (item["metric"], item["reason"]) not in seen:
                seen.add((item["metric"], item["reason"]))
                out.append(f"- {item['metric']}: {item['reason']}")
    return "\n".join(out) + "\n"


def parse_groups(args):
    """['--group', 'a', d1, d2, '--group', 'b', d3] -> [('a', [d1, d2]), ('b', [d3])]. 형식이 틀리면 None."""
    groups = []
    i = 0
    while i < len(args):
        if args[i] != "--group" or i + 1 >= len(args):
            return None
        label, i = args[i + 1], i + 2
        dirs = []
        while i < len(args) and args[i] != "--group":
            dirs.append(args[i])
            i += 1
        if not dirs:
            return None
        groups.append((label, dirs))
    return groups


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2
    out_dir, rest = argv[1], argv[2:]
    groups_spec = parse_groups(rest) if rest[0] == "--group" else None
    if rest[0] == "--group" and groups_spec is None:
        print("usage error: --group <라벨> <run 디렉터리>...", file=sys.stderr)
        return 2
    run_dirs = [d for _, dirs in groups_spec for d in dirs] if groups_spec else rest
    runs = [analyze_run(d) for d in run_dirs]
    groups = None
    if groups_spec:
        labels_by_dir = {d: r["label"] for d, r in zip(run_dirs, runs)}
        groups = [(name, [labels_by_dir[d] for d in dirs]) for name, dirs in groups_spec]
    os.makedirs(out_dir, exist_ok=True)
    payload = {"runs": runs, "note": FIXED_NOTE}
    if groups:
        payload["groups"] = group_summary(runs, groups)
    with open(os.path.join(out_dir, "bottleneck-summary.json"), "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)
    with open(os.path.join(out_dir, "bottleneck-summary.md"), "w", encoding="utf-8") as f:
        f.write(render_markdown(runs, groups))
    counts = {k: sum(len(r["warnings"][k]) for r in runs) for k in runs[0]["warnings"]} if runs else {}
    print(json.dumps({"out_dir": out_dir, "runs": len(runs), "groups": [g for g, _ in (groups or [])],
                      "warning_counts": counts}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
