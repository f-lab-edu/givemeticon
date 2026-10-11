"""before/after .prom 에서 '시리즈가 before 에 없음'을 baseline 0 으로 추론해도 되는지 판정한다(직접 관측이 아니라 추론).

근거 구조(한 가지로 합치지 않는다):
  A. 본문에서 확인 가능한 범위(structural, "checkable-range")
     anchors_present  : process_start_time_seconds / process_uptime_seconds / jvm_info 가 before·after 에 있음, 해석 불가 줄 0
     same_process     : process_start_time 값·라벨 동일, uptime after >= before          (재시작이 '확인 가능한 범위에서' 없음)
     no_counter_reset : 양쪽에 있는 count/sum/total/bucket 시리즈 감소 없음 (LongTaskTimer *_active_* 제외)
     exposure_unchanged: before 의 count/sum/total/bucket 시리즈가 after 에서 사라지지 않음 (필터/라벨 변경이 '확인 가능한 범위에서' 없음)
  B. 수집 시점에 기록된 외부 provenance (<app>-scrape-provenance.json). 본문만으로는 줄 경계에서 잘린 응답·선택적으로 제거된 시리즈를 구별할 수 없다.
     collection_success  : before/after exit_code==0, http_status==200
     target_verified     : effective_target == expected_target (before/after 동일)
     body_integrity      : 기록된 body_bytes/body_sha256 이 실제 파일과 일치 (수집 후 무결성; 서버가 전체 시리즈를 노출했다는 증명은 아님)
     exposure_policy     : MeterFilter/registry.remove/필터 미사용 근거(문자열 evidence + Spring/Micrometer 버전 + 등록 경로)가 기록돼 있음
                           (이 모듈은 기록의 존재/형식만 검사하며 주장의 참을 증명하지 않는다)
  provenance 가 없거나 불완전하면 자동 추론하지 않는다(MISSING). 과거 artifact 에 대해 provenance 를 사후 생성하지 않는다.
  allow_manual=True 이면 A 만 통과해도 CONDITIONAL_MANUAL_INFERENCE 로 표기하되 OK 로 취급하지 않는다.
  측정 전 활동 여부(prior activity)는 추론 가능성과 무관하므로 진단 필드로만 남긴다(예열 후 새 태그 시리즈 추론을 막지 않는다).
"""
import hashlib, json, re

# label 값 안의 '}' (예: uri="/x/{eventId}")를 라벨 블록 종료로 오인하지 않도록 따옴표 문자열 단위로 파싱한다
LINE = re.compile(r'^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{((?:[^}"]|"(?:[^"\\]|\\.)*")*)\})?\s+([0-9.eE+\-]+|NaN|\+Inf|-Inf)\s*$')
LABEL = re.compile(r'(\w+)="((?:[^"\\]|\\.)*)"')
COUNTERISH = ("_count", "_sum", "_total", "_bucket")
# LongTaskTimer(진행 중 요청) 계열은 이름이 _sum/_count 로 끝나도 gauge 처럼 감소하므로 counter 검사에서 제외한다
NOT_COUNTER_MARK = "_active_"
SCHEMA = "scrape-provenance/v1"
ANCHORS = ("process_start_time_seconds", "process_uptime_seconds", "jvm_info")
# 모든 Micrometer meter 가 첫 기록 시 등록된다고 일반화하지 않는다: family 별 실제 등록 경로만 허용한다.
# Spring Boot 3.1.3 (build.gradle) -> Micrometer 1.11.x. 아래 근거는 이 저장소 소스(coupon.*)와 해당 버전의 라이브러리 동작이며,
# http_server_requests 의 등록 경로는 라이브러리 동작에 대한 서술이다(이 환경에서 jar 소스로 재검증하지 않았다).
LAZY_FAMILIES = {
    "coupon_redis_lock_acquire": "DistributedLockAop: Timer.builder(\"coupon.redis_lock.acquire\").tags(outcome).register(..) at call time",
    "coupon_redis_lock_hold": "DistributedLockAop: Timer.builder(\"coupon.redis_lock.hold\").register(..) at call time",
    "coupon_admission_batch_queue_wait": "CouponBatchAdmissionAcceptor:175 Timer.builder(\"coupon.admission.batch.queue_wait\")...register(..) per flush",
    "coupon_admission_transaction": "CouponAdmissionTransactionTimers: Timer.builder(\"coupon.admission.transaction\").register(..) at call time",
    "coupon_admission_commit": "CouponAdmissionTransactionTimers: Timer.builder(\"coupon.admission.commit\").register(..) at call time",
    "coupon_admission_post_lock_work": "CouponAdmissionTransactionTimers: Timer.builder(\"coupon.admission.post_lock_work\").register(..) at call time",
    "http_server_requests": "Spring Boot 3.1.x server observation (ServerHttpObservationFilter -> DefaultMeterObservationHandler): Timer per uri/status/method/outcome/exception tag-set registered when the first matching request completes",
}


def is_counter(name): return name.endswith(COUNTERISH) and NOT_COUNTER_MARK not in name


def read_prom(path):
    """None(파일 없음) 또는 {"rows": [(name, labels, value)], "bad_lines": n, "empty": bool}"""
    if not path.exists(): return None
    rows, bad = [], 0
    for l in path.read_text(errors="replace").splitlines():
        if not l.strip() or l.startswith("#"): continue
        m = LINE.match(l)
        if not m: bad += 1; continue
        v = m.group(4)
        rows.append((m.group(1), dict(LABEL.findall(m.group(3) or "")), float("nan") if v == "NaN" else float(v.replace("+Inf", "inf"))))
    return {"rows": rows, "bad_lines": bad, "empty": not rows}


def read_provenance(path):
    """None(없음) 또는 dict. 깨진 JSON 은 {'_invalid': msg}."""
    if not path.exists(): return None
    try: return json.loads(path.read_text())
    except ValueError as e: return {"_invalid": str(e)}


def key(labels): return tuple(sorted(labels.items()))


def _one(rows, name):
    r = [(lab, v) for n, lab, v in rows if n == name]
    return r[0] if len(r) == 1 else None


def _sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()


def scrape_evidence(b, a, prov=None, paths=None):
    """b, a: read_prom 결과(None 가능). prov: read_provenance 결과. paths: (before_path, after_path) - 해시 대조용.
    반환 {"structural_ok","provenance_ok","checks","provenance_checks","diagnostics"}"""
    c, pc = {}, {}
    def put(d, n, ok, det): d[n] = {"pass": bool(ok), "detail": det}
    if b is None or a is None:
        put(c, "anchors_present", False, "before/after prom file absent")
        return {"structural_ok": False, "provenance_ok": False, "checks": c, "provenance_checks": {}, "diagnostics": {}}
    pb, pa = _one(b["rows"], "process_start_time_seconds"), _one(a["rows"], "process_start_time_seconds")
    ub, ua = _one(b["rows"], "process_uptime_seconds"), _one(a["rows"], "process_uptime_seconds")
    miss = [n for n in ANCHORS for side, r in (("before", b), ("after", a)) if not any(m == n for m, _, _ in r["rows"])]
    put(c, "anchors_present", not miss and b["bad_lines"] == 0 and a["bad_lines"] == 0 and pb and pa,
        f"rows={len(b['rows'])}/{len(a['rows'])}, unparsed={b['bad_lines']}/{a['bad_lines']}, missing_anchors={miss}")
    put(c, "same_process", pb and pa and pb == pa and ub and ua and ua[1] >= ub[1],
        f"start_time={pb and pb[1]}/{pa and pa[1]}, labels_equal={bool(pb and pa and pb[0] == pa[0])}, uptime={ub and ub[1]}/{ua and ua[1]} (restart-free within checkable range)")
    bv = {(n, key(l)): v for n, l, v in b["rows"] if is_counter(n)}
    av = {(n, key(l)): v for n, l, v in a["rows"] if is_counter(n)}
    resets = [k for k, v in bv.items() if k in av and av[k] < v]
    put(c, "no_counter_reset", not resets, f"{len(resets)} series decreased" + (f", e.g. {resets[0][0]}" if resets else ""))
    gone = [k for k in bv if k not in av]
    put(c, "exposure_unchanged", not gone, f"{len(gone)} counter series vanished" + (f", e.g. {gone[0][0]}" if gone else "") + " (filter-change-free within checkable range)")
    act = sum(v for n, l, v in b["rows"] if n == "http_server_requests_seconds_count" and not l.get("uri", "").startswith("/actuator"))
    diag = {"non_actuator_http_count_before": act, "note": "diagnostic only; prior activity does not decide inference validity"}
    if prov is None or "_invalid" in (prov or {}):
        put(pc, "provenance_present", False, "no scrape provenance file" if prov is None else "invalid JSON: " + prov["_invalid"])
    else:
        put(pc, "provenance_present", prov.get("schema") == SCHEMA, f"schema={prov.get('schema')!r}, supported={SCHEMA!r}")
        sc = prov.get("scrapes") or {}
        sb, sa = sc.get("before") or {}, sc.get("after") or {}
        put(pc, "collection_success", sb.get("exit_code") == 0 and sa.get("exit_code") == 0 and sb.get("http_status") == 200 and sa.get("http_status") == 200,
            f"exit/status before={sb.get('exit_code')}/{sb.get('http_status')} after={sa.get('exit_code')}/{sa.get('http_status')}")
        exp = prov.get("expected_target")
        put(pc, "target_verified", bool(exp) and all(x.get("requested_target") == exp and x.get("effective_target") == exp for x in (sb, sa)),
            f"expected(run contract)={exp} requested={sb.get('requested_target')}/{sa.get('requested_target')} effective={sb.get('effective_target')}/{sa.get('effective_target')} (redirect/wrong port rejected)")
        if paths and all(p.exists() for p in paths):
            ok = (sb.get("body_sha256") == _sha(paths[0]) and sb.get("body_bytes") == paths[0].stat().st_size
                  and sa.get("body_sha256") == _sha(paths[1]) and sa.get("body_bytes") == paths[1].stat().st_size)
            put(pc, "body_integrity", ok, "recorded bytes/sha256 vs files (post-collection integrity; not proof the server exposed every series)")
        else: put(pc, "body_integrity", False, "files or recorded hashes unavailable")
        mp = prov.get("meter_policy") or {}
        ecfg = mp.get("effective_metrics_config") if isinstance(mp.get("effective_metrics_config"), dict) else {}
        fmf = mp.get("framework_meter_filters")
        put(pc, "exposure_policy", mp.get("custom_app_meter_filter_or_registry_remove_used") is False and bool(str(mp.get("evidence", "")).strip())
            and bool(mp.get("spring_boot")) and bool(mp.get("micrometer")) and isinstance(mp.get("registration_paths"), dict) and bool(mp.get("registration_paths"))
            and isinstance(fmf, list) and bool(fmf) and all(isinstance(x, str) and x.strip() for x in fmf)
            and type(ecfg.get("max_uri_tags")) is int and ecfg.get("max_uri_tags") > 0 and isinstance(ecfg.get("enable"), dict) and bool(str(ecfg.get("source_sha256", "")).strip()),
            "recorded: custom-app filter/remove claim (not framework filters) + evidence + versions + registration paths + framework_meter_filters list + effective_metrics_config(max_uri_tags, enable overrides, source hash); existence/format only, claim truth not proven here")
    rp = (prov.get("meter_policy") or {}).get("registration_paths") if isinstance(prov, dict) else None
    uc = (prov.get("meter_policy") or {}).get("http_uri_contract") if isinstance(prov, dict) else None
    after_uris = sorted({l.get("uri", "") for n, l, v in a["rows"] if n == "http_server_requests_seconds_count"})
    mx = ((prov.get("meter_policy") or {}).get("effective_metrics_config") or {}).get("max_uri_tags") if isinstance(prov, dict) else None
    s_ok = all(x["pass"] for x in c.values())
    p_ok = all(x["pass"] for x in pc.values()) if pc else False
    return {"structural_ok": s_ok, "provenance_ok": p_ok, "checks": c, "provenance_checks": pc, "diagnostics": diag,
            "registration_paths": rp if isinstance(rp, dict) else {}, "http_uri_contract": uc if isinstance(uc, dict) else None,
            "after_http_uris": after_uris, "max_uri_tags": mx}


def lazy_zero_allowed(ev, family, allow_manual=False):
    """family: 접미사 없는 meter 이름. 반환 (status|None, 사유). status: OK_INFERRED_ZERO_BASELINE | CONDITIONAL_MANUAL_INFERENCE"""
    fam = next((f for f in LAZY_FAMILIES if family == f or family.startswith(f + "_")), None)
    if fam is None: return None, f"{family} not in LAZY_FAMILIES (registration path not established)"
    if not ev["structural_ok"]:
        return None, "structural evidence failed: " + ",".join(k for k, x in ev["checks"].items() if not x["pass"])
    bad = [k for k, x in ev["provenance_checks"].items() if not x["pass"]] or (["provenance_present"] if not ev["provenance_checks"] else [])
    rp = str(ev.get("registration_paths", {}).get(fam, "")).strip()   # 요청한 family 자신의 등록 근거가 있어야 한다(다른 family 경로로 대체 불가)
    if not rp and ev["provenance_checks"]: bad.append(f"registration_paths[{fam}]")
    if fam == "http_server_requests" and ev["provenance_checks"]:
        # 선언된 제한 URI 계약이 있고 after 에 관측된 모든 uri 가 그 안에 있으며 distinct uri 수가 유효 max-uri-tags 미만일 때만(필요조건).
        # 이것만으로 과거 deny 부재를 확정하지 않는다: 계약/유효 필터 근거가 없으면 HTTP absent-before 는 MISSING.
        c = ev.get("http_uri_contract") or {}
        allowed = c.get("allowed_uris") if isinstance(c.get("allowed_uris"), list) else []
        mx = ev.get("max_uri_tags")
        if not allowed or not set(ev.get("after_http_uris", [])) <= set(allowed) or not (type(mx) is int and len(ev.get("after_http_uris", [])) < mx):
            bad.append("http_uri_contract")
    if ev["provenance_ok"] and not bad: return "OK_INFERRED_ZERO_BASELINE", "structural + external provenance verified; registration evidence for %s: %s; code basis: %s" % (fam, rp, LAZY_FAMILIES[fam])
    if allow_manual:
        return "CONDITIONAL_MANUAL_INFERENCE", "manual: structural only; provenance unverified (" + ",".join(bad) + "); registration basis: " + LAZY_FAMILIES[fam]
    return None, "provenance not verified: " + ",".join(bad)


def lock_call_sites_evidenced(prov):
    s = (prov or {}).get("lock_call_sites") if isinstance(prov, dict) else None
    ok = isinstance(s, dict) and s.get("worker_disabled") is True and s.get("recovery_disabled") is True and bool(str(s.get("evidence", "")).strip())
    return ok, (s if isinstance(s, dict) else None)
