# #177 artifact 보존 — 2026-10-17

원본 GitHub artifact ZIP 3개를 만료 전에 다운로드했다. ZIP 바이트를 수정하지 않고 이 문서 옆 [evidence/issue-177/](evidence/issue-177/)에 보존한다. 저장소 PR은 문서와 이 증거 보관본만 포함한다. 별도 로컬 복사본은 `/Users/jinhyuck/Desktop/projects/givemeticon/기록/issue-177-artifacts/20261017/`에 있다. 로컬 `기록/`은 Git ignore 대상이므로 원격 보관은 이 문서 브랜치의 ZIP을 기준으로 한다.

| Artifact / 원본 run | 크기 | GitHub 만료 시각(KST) | ZIP SHA-256 |
| --- | --- | --- | --- |
| [11686091033](https://github.com/f-lab-edu/givemeticon/actions/runs/38096702816) | 191,936 B | 10월 18일 09:01:30 | `341fbfed5420cc318b3f8e4698855a71775f2df01fcbf482e7da0348c9341b1b` |
| [11702151619](https://github.com/f-lab-edu/givemeticon/actions/runs/38137748465) | 50,538 B | 10월 18일 21:05:05 | `1952497a1a00a637f82df9d9d9c0d85854c6d82c35943de9882c7397b5524b2b` |
| [11721680127](https://github.com/f-lab-edu/givemeticon/actions/runs/38188972241) | 1,011,516 B | 10월 19일 09:50:46 | `f96520de48ccddfc3185b3a9aa0dcd519c25d4cf4ff78efdcaa1be5c7b293c49` |

## 원본·내용 검증

[보존 manifest](evidence/issue-177/preservation-manifest.json)는 각 ZIP의 API 메타데이터, 원본 파일명, 크기, ZIP 해시, 모든 내부 파일의 크기·SHA-256을 연결한다. 원본 메타데이터 JSON과 바깥 [SHA256SUMS](evidence/issue-177/SHA256SUMS)도 보관했다.

- GitHub API의 `digest`와 실제 ZIP SHA-256: 3/3 일치. API 크기도 일치했고 다운로드 시점에 모두 `expired=false`였다.
- ZIP CRC: 전체 641개 entry 통과. 중복·절대/상위 경로·symlink entry 없음. 원본을 풀어서 다시 압축하지 않았다.
- ZIP 내부 `SHA256SUMS`: 각각 141 / 61 / 436개, 합계 638개 파일의 실제 바이트 해시 일치. 각 ZIP의 SHA256SUMS 자신을 제외한 누락 0개.
- 원본 `artifact-manifest.json`: 각각 89 / 8 / 382개 선언 파일의 크기·해시 일치. 이 manifest 자체의 수집 범위는 제한적이며, 별도 전체 파일 목록을 보존 manifest에 기록했다.
- `checked-sha.txt`는 API의 각 run `head_sha`와 일치: `b1e08f7d0093c9431f735f8b21639c19e9186229`, `0c59657c92c33e61168a573feeafe72d84a4ce1c`, `6d81826f4da9876c201e5129a8cde76eb4399973`.
- 원본은 모두 `redaction_applied=true`, 합성 격리 실행 증거다. 알려진 GitHub/OpenAI/Anthropic 토큰·개인키 패턴 검사에서 일치 없음. 이 검사가 모든 민감정보의 부재를 증명하지는 않는다.
- 이전 [run 37586432949](https://github.com/f-lab-edu/givemeticon/actions/runs/37586432949)의 artifact API는 현재 빈 목록이다. 해당 원본을 이번에 다운로드하거나 복원했다고 주장하지 않는다. 과거 로컬 보존 보고는 [baseline 진단](../experiments/baseline-10k-diagnostic.md)과 원본 로컬 #177 검증 기록에 한정하며 이번 다운로드 결과와 구분한다.

재확인 예:

```sh
cd docs/decisions/evidence/issue-177
shasum -a 256 -c SHA256SUMS
python3 -c 'import zipfile,pathlib; [print(p.name, zipfile.ZipFile(p).testzip()) for p in pathlib.Path(".").glob("*.zip")]'
```

내부 파일은 `zipfile.ZipFile(...).read(path)`의 SHA-256을 보존 manifest의 `files` 및 원본 SHA256SUMS와 대조할 수 있다. CRC의 `None`은 오류가 없다는 뜻이다.

보존 검증 PASS는 유효한 10k/50k/100k baseline, 시스템 정합성·복구·SLO PASS가 아니다. 기존 실패·MISSING/UNKNOWN·관측 한계를 유지한다. Actions 재실행·새 부하·컨테이너 기동·Issue 수정은 수행하지 않았다. 기존 맥락 복원 검사 [verification.json](verification.json)은 최초 조사 단계의 기록이며, 이 후속 보존 및 PR 생성 기록과 구분한다.

문서 브랜치 검토: AGENTS의 지속 규칙과 설계/현행 구현/로컬 후보 구분을 검토했다. 상대 링크 115개·repo 출처 43개·로컬 출처 19개 해시와 기존 Git 이력 metadata 검사를 통과했다. 변경 허용 경로는 AGENTS.md 및 docs/decisions/뿐이며 src·기존 scripts·build·Actions diff는 0이다. 비즈니스 테스트는 실행하지 않았다.
