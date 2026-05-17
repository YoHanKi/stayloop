---
name: verify-code
description: |
  Stayloop 기능 구현/리팩토링 직후, verify-architecture 보다 먼저 호출되어 코드 본문의 사일런트 결함을 잡는 게이트.
  **오케스트레이터** — 실제 룰은 5 sub-skill (`verify-code-scope` / `-invariants` / `-flow` / `-parity` / `-cold-eye`) 에 분산.
  **무조건 2 사이클 이상 실행**. Cycle 1 = 1차 발견. Cycle 2 = 직전 fix 가 만든 새 결함 + P2 보류 13 패턴 재의심.
  두 사이클 모두 P0/P1 = 0 + CE-1~CE-6 6 항목 ✅ 일 때만 PASS.
  **기본 = 냉정 + 검증자 + 꼼꼼 모드 (최대 깊이)** — 표면 grep 으로 끝내지 않고 race window / 분포 시나리오 / 어셔런스 사각지대까지 명시 라벨링.
  사용자가 *"회귀 모드 / 자체 반복 / 스탑할 때까지"* 명시 시에만 **수정자 모드** (fix 까지 직접 적용) 로 전환, defect-zero 도달까지 사이클 누적 (최대 5; 초과 시 *전제* 재검토).
  자동 호출 순서: **verify-code → verify-architecture → verify-tests**. 본 스킬 FAIL 시 다음 게이트 진행 금지.
user-invocable: true
---

# verify-code (orchestrator)

본 스킬은 *오케스트레이터*. 실제 룰 본문은 5 sub-skill 에 분산되어 있고, 매 호출마다 *반드시* 5 phase 를 *최소 2 사이클* 돌린다.

## Sub-skills (단계)

- **Phase 0 — `verify-code-scope`**: 변경 scope / 카테고리 매핑 (Catch-or-Miss Matrix) / `.github/instructions/*.md` 활성화.
- **Phase 1 — `verify-code-invariants`**: 정적 본문 — null / 시그니처 누출 / 캐시 SSOT / 입력 검증 + 컬럼-가드 정합 / 매직 상수 / 가시성 / 인덱스.
- **Phase 2 — `verify-code-flow`**: 흐름 — 자식 ID / 멱등 / 사일런트·Boolean 무시 / 동시성·락 순서 / 자원 / 시간 / 예외·메시지 / 트랜잭션 / 화이트리스트 / 보안.
- **Phase 3 — `verify-code-parity`**: 정합 — 결정성 / DRY / Read-then-Write / 어휘 / 운영-테스트 더블 동치 / KDoc·DisplayName 정합.
- **Phase 4 — `verify-code-cold-eye`**: 최종 게이트 — CE-1~CE-6 6 항목 어셔런스 + P2 보류 13 패턴 재의심 + 회귀 방지 테스트 권고.

각 sub-skill 은 자기 영역의 발견만 P0/P1/P2 로 보고. 최종 PASS/FAIL 통합은 orchestrator.

## 절차 — 무조건 2 사이클 이상

### Cycle 1 — 1 차 발견

1. Phase 0 호출 → 변경 파일 / 활성 카테고리 / 활성 instruction 박제.
2. Phase 1~3 sub-skill 을 *변경 카테고리 매치 여부와 무관하게 전부* 호출. 작은 변경에도 부분 grep 으로 확인 — *어떤 룰이 적용 안 됨* 의 판단 근거를 출력에 드러낸다.
3. Phase 4 호출 — CE-1~CE-6 통과 어셔런스 *명시 라벨링* 강제. 어셔런스 없이 PASS 금지.
4. 발견을 P0/P1/P2 분류 후 사용자 보고.

### Cycle 2 — 자체 회귀 (반드시 실행, Cycle 1 결과와 무관)

본 사이클은 *Cycle 1 PASS 여부와 관계없이 반드시 한 번 더* 돈다. 핵심 가치는 *fix-자체가-결함-생산자* 패턴 제거 + *P2 보류 항목 외부 리뷰 격상* — 1 사이클로 끝내지 않는다.

Cycle 1 fix 가 다음을 어겼는지 다시 본다: 새 const / 새 분기 / 새 가드가 매직 상수·DisplayName·DRY 위반을 만들지 않았는가, 새 정책이 운영-테스트 동치를 깼는가, 새 메시지가 외부 식별자 / JPA 예외 원문 노출을 만들었는가, 새 시그니처가 외부 라이브러리 누출을 만들었는가, 새 KDoc 의 부정 약속이 실 가드와 일치하는가, 새 `create()` / 팩토리가 캐시 컬럼을 매개변수로 받지 않는가, `.github/instructions` 의 *수용된 트레이드오프* 를 무심코 깨뜨리지 않았는가.

**P2 보류 13 패턴 재의심** — Phase 4 cold-eye 가 박제한 13 패턴 중 Cycle 1 에서 P2 로 보류한 항목이 있으면 외부 리뷰가 P1+ 로 잡는 패턴인지 매치 — 매치되면 P1 격상. Phase 1~4 sub-skill 을 *전부 다시* 돌리며 새 발견이 있는지 확인. **Cycle 1 PASS 결론 복붙 금지**.

호출자 영향 검토 — Cycle 1 변경된 시그니처/정책의 사용처 grep, 새 가드가 기존 호출을 깨지 않는지.

### 종료 조건

- **Cycle 2 PASS** — P0/P1 = 0 ∧ CE 6/6 ✅ ∧ 호출자 영향 0 → verify-code PASS, verify-architecture 진행.
- **Cycle 2 FAIL** — fix 적용 후 Cycle 3 진입.
- **Cycle 5 초과** — *전제 (아키텍처 / 도메인 모델 / 박제 형식)* 자체 재검토.

## 모드 분기 (default = 검증자 + 냉정 + 꼼꼼)

default 모드는 매 사이클 5 phase 풀 호출 + CE-1~CE-6 명시 라벨링. fix 는 *권고* 만, 코드 변경 X. 사용자가 *"수정해줘 / 회귀로 돌려줘 / 자체 반복 / 스탑할 때까지"* 명시 시에만 **수정자 모드** 로 전환되어 Cycle 1 에서 P0/P1 즉시 fix 적용 + Cycle 2 부터 직전 fix 가 만든 새 결함까지 자체 회귀. defect-zero 도달까지 사이클 누적.

기본 깊이는 이미 최대 — 표면 grep + KDoc 원문 + 호출 시그니처 + 분포 시나리오 + 어설션 강도 + 다중 throw 경로 + 박제 ↔ 코드 정합 모두 매 사이클 명시 라벨링. *"어설션이 통과한다 = 결함 없음"* 으로 결론 내지 않는다. *"본 PR scope 외"* 같은 모호한 사유로 P1 을 P2 로 보류 금지.

## 출력 (orchestrator 통합)

각 sub-skill 의 결과를 Phase 별로 받아 Cycle 1 / Cycle 2 로 묶어 보고. Cycle 2 에 *직전 fix 가 만든 새 결함* + *P2 보류 13 패턴 재의심* + *호출자 영향 검토* 결과를 박제한다. 최종 게이트 결정은 PASS / FAIL + Cycle 3 진입 사유 + P0/P1 박제.

## 원칙

- 2 사이클 미만 종결 금지. Cycle 1 PASS 라도 Cycle 2 자체 회귀 필수.
- 표면 grep 으로 끝내지 않는다. race window / 분포 시나리오 / 어셔런스 사각지대 모두 명시.
- Cycle 1 PASS 결론 복붙 금지. Cycle 2 는 같은 영역도 다른 차원에서 본다.
- 수정자 모드 = 명시 요청 시만. 커밋·푸시 자동 진행 금지.
- 본 스킬 FAIL 인 동안 verify-architecture / verify-tests 진행 금지.
- 한국어 응답.
