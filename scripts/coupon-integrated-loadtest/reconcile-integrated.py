#!/usr/bin/env python3
"""통합 부하테스트 1회 실행의 원본 로그를 회원 ID 단위로 DB와 대조한다.
- 접수 성공(admission, category=success) 응답의 회원 전원이 DB에 있는지
- 조회(final) 단계가 보고한 최종 상태가 실제 DB 상태와 일치하는지
- 중복 재신청(duplicate)이 원본과 같은 requestId로 수렴했는지
- 180초 내 확정 비율(첫 유효 신청 기준, 재시도로 갱신되지 않음)
사용법: reconcile-integrated.py <run_dir>
"""
import json
import re
import sys
import pathlib
import collections

MSG_PATTERN = re.compile(r'msg="(.*)"\s*$')


def parse_log(path: pathlib.Path):
    records = []
    if not path.exists():
        return records
    for raw in path.read_text(errors="replace").splitlines():
        line = raw.strip()
        m = MSG_PATTERN.search(line)
        payload = m.group(1) if m else (line if line.startswith("{") else None)
        if payload is None:
            continue
        payload = payload.replace('\\"', '"').replace("\\\\", "\\")
        try:
            records.append(json.loads(payload))
        except ValueError:
            continue
    return records


def main():
    run_dir = pathlib.Path(sys.argv[1])
    log = parse_log(run_dir / "k6-failures.log")

    admissions = [r for r in log if r.get("kind") == "admission"]
    duplicates = [r for r in log if r.get("kind") == "duplicate"]
    finals = [r for r in log if r.get("kind") == "final"]

    admission_category_counts = collections.Counter(r.get("category", "unknown") for r in admissions)
    success_admissions = {r["memberId"]: r.get("requestId") for r in admissions if r.get("category") == "success"}

    db_by_member = {}
    apps_path = run_dir / "applications.tsv"
    if apps_path.exists():
        for line in apps_path.read_text(errors="replace").splitlines():
            parts = line.split("\t")
            if len(parts) >= 4 and parts[0].strip().isdigit():
                member_id = int(parts[0])
                db_by_member[member_id] = {
                    "acceptance_sequence": parts[1],
                    "status": parts[2],
                    "request_id": parts[3] if parts[3] != "NULL" else None,
                }

    # 1) 접수 성공 응답 -> DB 존재 여부(ID 단위)
    missing_success_in_db = []
    requestid_mismatch = []
    for member_id, request_id in success_admissions.items():
        row = db_by_member.get(member_id)
        if row is None:
            missing_success_in_db.append(member_id)
        elif row["request_id"] != request_id:
            requestid_mismatch.append((member_id, request_id, row["request_id"]))

    # 2) 중복 재신청이 원본과 같은 requestId로 수렴했는지(둘 다 확정된 경우만 비교 가능)
    duplicate_mismatches = []
    for r in duplicates:
        primary_id = r.get("primaryRequestId")
        dup_id = r.get("requestId")
        if primary_id and dup_id and primary_id != dup_id:
            duplicate_mismatches.append((r.get("memberId"), primary_id, dup_id))

    # 3) 조회(final) 최종 상태 vs DB 실제 상태
    final_status_mismatches = []
    final_status_counts = collections.Counter()
    within_budget_count = 0
    for r in finals:
        final_status_counts[r.get("finalStatus")] += 1
        member_id = r.get("memberId")
        reported = r.get("finalStatus")
        row = db_by_member.get(member_id)
        if reported in ("ISSUED", "SOLD_OUT"):
            if r.get("withinBudget"):
                within_budget_count += 1
            if row is None or row["status"] != reported:
                final_status_mismatches.append((member_id, reported, row["status"] if row else None))

    result = {
        "admission_category_counts": dict(admission_category_counts),
        "success_admissions_count": len(success_admissions),
        "missing_success_in_db_count": len(missing_success_in_db),
        "requestid_mismatch_count": len(requestid_mismatch),
        "duplicate_attempts_count": len(duplicates),
        "duplicate_mismatch_count": len(duplicate_mismatches),
        "final_records_count": len(finals),
        "final_status_counts": dict(final_status_counts),
        "final_status_mismatch_count": len(final_status_mismatches),
        "within_180s_budget_count": within_budget_count,
        "within_180s_budget_rate_pct": round(within_budget_count / len(finals) * 100, 1) if finals else None,
        "unresolved_after_quiescence": (run_dir / "unresolved-after-quiescence.txt").read_text().strip()
        if (run_dir / "unresolved-after-quiescence.txt").exists() else None,
    }

    (run_dir / "reconciliation-summary.json").write_text(json.dumps(result, indent=2, ensure_ascii=False))
    (run_dir / "reconciliation-missing-success-member-ids.txt").write_text(
        "\n".join(str(m) for m in missing_success_in_db)
    )
    (run_dir / "reconciliation-requestid-mismatches.tsv").write_text(
        "\n".join(f"{m}\t{sent}\t{db}" for m, sent, db in requestid_mismatch)
    )
    (run_dir / "reconciliation-duplicate-mismatches.tsv").write_text(
        "\n".join(f"{m}\t{primary}\t{dup}" for m, primary, dup in duplicate_mismatches)
    )
    (run_dir / "reconciliation-final-status-mismatches.tsv").write_text(
        "\n".join(f"{m}\t{reported}\t{actual}" for m, reported, actual in final_status_mismatches)
    )

    print(json.dumps(result, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
