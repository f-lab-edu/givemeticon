# 복원된 결정: baseline·분모·최종 관측·보존

상태: [ADR-001](../adr/ADR-001-baseline.md)의 계약·과거 진단 복원. #166/#173의 유효한 동조건 비교와 RabbitMQ 개선은 확인하지 못했다.

## 비교·최종성 계약

주 baseline은 b941a04의 V1 묶음 접수+발급이다. stock/Redisson 동기 경로는 별도 비교이며 API·업무·트랜잭션 차이를 숨기지 않는다. 목표는10초의 고유 최초 신청10k→50k→100k, 중복10%·조회는 별도 부하다. broker를 포함한 서비스 전체 CPU/RAM 예산과 생성기 자원·실제 생성량을 고정·관측한다.

초기 전체 최초 신청자의 ISSUED/SOLD_OUT100% 해석은 ADR-001의 후속 terminal outcome 절로 정정됐다. CLOSED 이전에 내구 접수되지 않은 요청에 application을 억지로 만들지 않는다.

- 업무 결과 수렴: 전체 최초 요청 분모에서 ISSUED/SOLD_OUT/확정된 사전 접수 ENDED를 별도 집계.
- 발급 coverage: 같은 분모의 ISSUED/SOLD_OUT 비율, admitted 조건부 완료율은 추가 지표.
- CHECKING·503·timeout·관측 누락은 분모에 남긴다. 재시도의 ENDED가 과거 미확정 접수를 해결하지 않는다.
- UPDATE의 UTC_TIMESTAMP(6)는 commit·회원 GET 관측 시각이 아니다. DB 최종 상태와 고객의 결과 인지는 다르다.

## 실행 이력의 의미

| 근거 | 확인 범위·한계 |
| --- | --- |
| ADR-001 local 진단 | stock1000/high500, admitted1050/10001 및1100/10000, 나머지 ENDED. 상한 미강제·관측 제한. 현재 SHA 재검증 아님 |
| [37586432949](https://github.com/f-lab-edu/givemeticon/actions/runs/37586432949) | 최종 FAILURE / 1e268d9. 보고상 stock2029/V1 4843 최초 요청으로10k 미달. 다른 모집단을 개선율로 비교하지 않음 |
| [38096702816](https://github.com/f-lab-edu/givemeticon/actions/runs/38096702816) | SUCCESS / b1e08f7. 소규모 GET/capability calibration.10k baseline·180초SLO 아님 |
| [38137748465](https://github.com/f-lab-edu/givemeticon/actions/runs/38137748465) | 최종 FAILURE / 0c59657. 댓글의 in_progress는 과거 상태. conclusion만으로 실패 원인을 추정하지 않음 |
| [38188972241](https://github.com/f-lab-edu/givemeticon/actions/runs/38188972241) | SUCCESS / 6d81826. 낮은 부하 warmed 진단·보존 후보 출처. 목표10k/50k/100k나 MQ 개선이 아님 |

상태·SHA·artifact metadata는 이번에 직접 조회했다. raw workload 분석·실행은 반복하지 않았다. app throttling·자원 압박·계측 공백을 단독 병목 원인으로 확정하지 않는다. VU 초기화 성공도 실제 HTTP burst 수용의 증거가 아니다.

## 하니스·잔여 결함

OPEN Draft [#178](https://github.com/f-lab-edu/givemeticon/pull/178)/[#179](https://github.com/f-lab-edu/givemeticon/pull/179)/[#180](https://github.com/f-lab-edu/givemeticon/pull/180)/[#181](https://github.com/f-lab-edu/givemeticon/pull/181)의 원격 head는1e268d9/4618b82/3989547/87b68bd다. develop에 들어 있다고 가정하지 않는다. #181 로컬 보존 후보8c6e693·safety 후보6307eb0는 모두 별도 local commit이며 develop ancestor가 아니다.

6307eb0의 로컬 검토는 **5/200 스모크를 exit0으로 처리해 다음10k 단계를 허용하는 MAJOR**를 보고한다. 실행 경계 검토는 MAJOR1·MINOR5를 보고했다. MySQL3306의 모든 binding이 의도한 loopback/port인지 DB 쓰기·k6 이전에 검사해야 하며 verify-calibration-caps만 보강하면 baseline 진입점을 놓친다. drop 누락을0으로 취급하는 표기, 조회하지 않은 요청을 timeout으로 기록, baseline 사후 생존/한도 검사, 오류 시 증거 부재가 남는다. 이는 develop의 현재 활성 workflow 결함이라는 뜻은 아니다. develop tracked Actions는 ci/cd/sonar3개이며 hosted workload workflow는 없다.

출처 해시는 [source manifest](source-manifest.json)에 있고, 원본 로컬 #177 execution-boundary-verifier·harness-safety-verifier 기록은 실제 검증 근거가 있어 보존한다. 이번에 합성 재현을 반복하지 않았다. 후속 수정·분석 정독 완료 증거는 확인하지 못했다.

## 시간 민감한 증거

조회 시 expired=false이며 API expires_at의 KST 환산이다.

| artifact | 만료 KST |
| --- | --- |
| 11686091033 / calibration b1e08f7 | **2026-10-18 09:01:30** |
| 11702151619 / warmed 0c59657 | **2026-10-18 21:05:05** |
| 11721680127 / warmed 6d81826 | **2026-10-19 09:50:46** |

최초 맥락 조사에서는 로컬 인계의 10월16일 원격 영구 보존 목표에 대한 완료 commit 연결을 찾지 못했다. 이후 사용자의 보존 지시로 위 3개 원본 ZIP을 다운로드해 이 문서 브랜치에 보관하고 GitHub digest·내부 checksum·run SHA를 대조했다. [2026-10-17 보존 결과](artifact-preservation-20261017.md)를 따른다. 원본 덮어쓰기·Actions trigger·retry·기한 변경은 하지 않았다.

재개 전 환경·owner·포트·credential·생성/관측 gate·분석 코드를 확인하고 정확 SHA의 검증과 해당 실행 권한을 확정한다. baseline 전체 완료는 #170/#171 코드 작성의 선행 조건은 아니지만 #173 비교·운영 전환에는 필요하다.

8c6e693 보존 후보의 소규모 shell/policy PASS와 6307eb0 safety 후보의 검토는 실행 승인·분석 코드 정독 완료와 별개다. 원본 로컬 #177 인계·테스트 로그를 삭제하거나 이번 정리의 새 PASS로 옮기지 않는다. 과거 72시간·quota 예약·1회 실행 허가는 새 실행 권한이 아니다.
