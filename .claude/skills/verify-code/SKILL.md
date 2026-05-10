---
name: verify-code
description: |
  Stayloop 기능 구현/리팩토링 직후, verify-architecture 보다 먼저 호출되어 코드 자체의 결함을 잡는 게이트.
  Copilot/시니어 리뷰가 자주 지적하는 패턴 — 불변식 보호 누락, null 일관성, 자식 entity ID 동기화,
  외부 라이브러리 누출, 컬렉션 정렬 결정성, 캐시-원본 불일치, 입력 검증 누락, 멱등 깨짐, 사일런트 디폴트,
  동시성 사고, 자원 누수, 시간/Clock 의존, 예외 삼킴, 매직 상수, 코드 중복, 가시성 이탈,
  성능 함정(N+1/eager), 보안(시크릿 노출/주입) — 을 점검한다.
  기본은 **검증자 모드 + 냉정 모드 (§0-B)** — 코드를 새로 작성하거나 리팩토링하지 않으며, 결함을 드러내고 개선 선택지를 제시한다.
  냉정 모드는 *항상* 활성 — 표면 grep 으로 끝내지 않고 race window / 분포 시나리오 / 어설션 강도 / 다중 throw 경로까지 추론한다.
  사용자가 "회귀 모드 / 자체 반복 / 스탑할 때까지" 를 명시한 경우에 한해 **수정자 모드 + 자체 회귀 루프** 로 전환되어,
  Round N 검증 → fix → Round N+1 자체 회귀를 defect-zero 도달까지 반복한다 (§0-A 회귀 모드 절차).
  검증 룰의 두 축: **본 스킬의 §0-B 냉정 모드 체크리스트 + §1~§19-B + `.github/instructions/*.md` (Copilot 동일 기준)** — 모두 통과해야 PASS.
  자동 호출 순서: **verify-code → verify-architecture → verify-tests**.
  본 스킬이 FAIL 인 동안 verify-architecture / verify-tests 는 의미가 없다 — 코드 본문에 사일런트 사고가 남기 때문.
user-invocable: true
---

Stayloop 의 기능 구현/리팩토링은 **이 스킬을 통과한 뒤에야 verify-architecture 로 넘어간다.**

세 게이트의 역할 분담:

| 게이트 | 본다 |
|---|---|
| **verify-code** (본 스킬) | **코드 본문**의 사일런트 결함 — 불변식 / null / 동시성 / 자원 / 시간 / 예외 / 가독성 / 보안 |
| verify-architecture | 계층 의존 방향 / 패키지 명명 / 어노테이션 누출 / 멀티모듈 경계 |
| verify-tests | 테스트 피라미드 / 더블 사용 / 명세성 / 실패·경계 시나리오 / `./gradlew test && ktlintCheck` |

이 스킬은 **검증자의 관점**으로 동작하며, 코드를 새로 짜주지 않고 **위험한 패턴**을 식별한다.

> **수정자 모드 vs 검증자 모드.** 사용자가 명시적으로 "직접 고쳐줘 / 회귀로 돌려줘" 라고 지시한 경우에 한해
> 본 스킬은 **수정자 모드** 로 전환되어 fix 까지 직접 적용한다. 그 외에는 검증자 관점 — 위반만 적시한다.

---

### 0️⃣-B 냉정 모드 (Cold-Eye Mode) — 기본 검증의 *깊이* 강제

**언제 냉정 모드인가**: 본 스킬은 *항상* 냉정 모드로 동작한다. 사용자가 *"꼼꼼하게 / 냉정하게 / 더 깊게"* 를 명시했거나 변경이 *동시성 / 트랜잭션 / 예외 흐름* 영역이면 본 절차를 *명시적으로 따른다*. **표면 grep 으로 끝내지 않는다** — race window / 분포 시나리오 / 어설션 강도 / 다중 throw 경로까지 추론한다.

**냉정 점검 체크리스트** (검증 라운드마다 *반드시* 통과해야 할 6 항목):

#### CE-1. *try/catch 의 catch scope 정합* — Facade 가 흡수해야 할 *모든 throw 위치* 가 try 안에 있는가

Facade 가 `try { repo.save(...) } catch (e: JpaException) { throw CoreException(...) }` 패턴을 쓸 때, **try 블록 *밖* 에서 같은 도메인 사고의 *다른 throw 경로* 가 있는지** 추론한다.

| 패턴 | 의심 |
|---|---|
| Facade 가 `try { repo.save(model) } catch (...)` 만 감싸는데 `model.someMethod()` 가 그 위에서 throw 가능 | model.someMethod() 의 도메인 throw 가 *catch 밖* 으로 흘러 *다른 메시지* 로 응답 |
| 하나의 도메인 사고 (예: "이미 사용된 쿠폰") 가 *2 흐름* (REPEATABLE_READ snapshot 분포) 으로 발생 가능 | 흐름 1 = JPA 예외 (catch 됨, 일반화 메시지), 흐름 2 = 도메인 메서드 throw (catch 안 됨, 다른 메시지) → 클라이언트 응답 일관성 깨짐 |
| `try` 안에 *Repository 호출만* 있고 *도메인 메서드* 는 밖 | 도메인 메서드의 `CoreException` 이 `customMessage` 로 직행 (§12 메시지 노출 정합 깨짐) |

**가드**: try 블록은 *그 도메인 사고가 발생할 수 있는 모든 호출* 을 감싼다. `CoreException` 도 catch 후 *errorType 별 분기* 로 메시지 정규화 — 같은 errorType 은 같은 메시지로.

```kotlin
// ✗ 안 좋음 — use() throw 가 catch 밖
issue.use(...)
try { repo.save(issue) } catch (e: OptimisticLockingFailureException) { throw CoreException(CONFLICT, "이미 사용된 쿠폰입니다.", cause = e) }

// ✓ 좋음 — use() 도 try 안 + CoreException CONFLICT 도 catch 후 메시지 정규화
try {
    issue.use(...)
    repo.save(issue)
} catch (e: OptimisticLockingFailureException) { throw CoreException(CONFLICT, "이미 사용된 쿠폰입니다.", cause = e) }
catch (e: DataIntegrityViolationException) { throw CoreException(CONFLICT, "이미 사용된 쿠폰입니다.", cause = e) }
catch (e: CoreException) {
    if (e.errorType == ErrorType.CONFLICT) throw CoreException(CONFLICT, "이미 사용된 쿠폰입니다.", cause = e)
    throw e  // BAD_REQUEST / FORBIDDEN 등은 도메인 의미 그대로 전파
}
```

**점검 명령**:
```
Grep "try \{" path=apps/.../application glob="*Facade.kt"
→ 각 try 블록 내 *모든 호출* 추출 후, *그 위 라인* 에서 같은 도메인 자원에 대한 mutation/검증 호출이 있는지 확인
→ 그 호출이 throw 가능 (도메인 메서드 / 검증 가드) 인지 시그니처 추적
```

#### CE-2. *분포 시나리오 추론* — REPEATABLE_READ snapshot / commit 시점 분포로 *같은 사고* 가 *서로 다른 경로* 발생 가능한가

동시성 자원에 대한 트랜잭션 흐름을 보면, **TX 시작 시점 vs 다른 thread 의 commit 시점** 분포에 따라 *같은 의도의 요청* 이 *다른 throw 경로* 로 갈린다.

| 시나리오 | 분포 1 | 분포 2 |
|---|---|---|
| 다중 thread 의 같은 자원 갱신 | TX_B 가 TX_A commit 이전 시작 → snapshot 에 *변경 전 상태* 보임 → memory 검증 통과 → commit 시점에 stale version → JPA 예외 | TX_B 가 TX_A commit 이후 시작 → snapshot 에 *변경 후 상태* 보임 → memory 검증 *실패* → 도메인 throw |

**가드**: 동시성 코드의 try/catch 는 *두 분포 모두 같은 메시지* 를 응답하도록 설계. ConcurrentXxxTest 는 *deterministic latch* 라 분포 1 에 치우치는 경향이 있으므로 *분포 2 가 발동되는 시나리오* 도 의식적 추가 (예: `Thread.sleep` + 사전 commit 으로 분포 2 강제).

#### CE-3. *어설션 강도* — DisplayName 의 모든 동사·정책 단어가 *어설션* 으로 1:1 검증되는가

§19-B 의 확장 — 동시성 E2E 어설션은 *상태 정합* (성공 카운트, DB 값) 만 보고 *메시지 일관성* 을 미검증하는 패턴이 흔하다.

| DisplayName 단어 | 필요 어설션 |
|---|---|
| "정확히 N건 성공" | `assertThat(successes).isEqualTo(N)` ✓ |
| "M건 CONFLICT" | `assertThat(conflicts).isEqualTo(M)` ✓ + **catch 한 customMessage 가 *모두 같은 메시지* 인가** |
| "raw 예외 0" | `others.isZero()` ✓ |
| "메시지 일관성" / "사용자 응답 일관" | catch list 의 customMessage 가 단일 set: `failures.map { it.customMessage }.toSet().size <= 1` |

**가드**: 동시성 E2E 의 catch 한 예외 list 를 단순히 카운트만 세지 말고 *메시지 일관성* 까지 어설션 — CE-1 의 회귀 가드.

#### CE-4. *3축 (DB / 도메인 메모리 / 응답)* 정합 — 같은 사고가 어디서 잡히는가

운영 코드의 한 도메인 사고는 다음 3축에서 *동일 결정* 을 만들어야 한다:

1. **DB 레벨** — UNIQUE 제약 / `@Version` / `WHERE x > 0` 가드
2. **도메인 메모리 레벨** — `canTransitTo(...)` / `init { require(...) }` / `requireOwner(...)`
3. **Facade 응답 레벨** — try/catch 변환 + 메시지 일반화

**가드**: 한 사고가 3축 모두 *같은 ErrorType + 같은 customMessage* 로 응답되는지 확인. 한 축만 막고 다른 축은 *다른 메시지* 면 분포 시나리오에서 회귀.

#### CE-5. *3 회 더 의심* — Round 종료 전 마지막 sanity check

검증을 끝내기 *전에* 다음 3 질문을 한 번 더 한다:

1. **이 PR 의 어설션 100% 가 통과한다고 *진짜로* 동시성 사고 0 인가?** — 어설션이 검증하지 않는 사각지대 (메시지 / stale snapshot / cache staleness) 가 있는가?
2. **이 catch 블록이 잡지 못하는 throw 경로가 있는가?** — 같은 try 안의 *그 위 라인* 까지 거슬러 throw 가능 위치 추적.
3. **이 KDoc 의 강한 약속 ("차단" / "보장" / "방지" / "불가능") 이 *실제 코드* 와 정합한가?** — §19-B 와 한 쌍.

3 회 모두 통과해야 *PASS 판정* 가능. 한 항목이라도 *불확실* 하면 fix 권고로 박제.

#### CE-6. *PR 박제 ↔ 실 코드 정합* — pr.md / experiments-results.md 의 박제가 *실제 보호 수준* 과 일치하는가

박제 문서 (pr.md / experiments-results.md / decision.md) 가 *미해결 위험* 으로 적은 항목이 *실제로는 코드에 가드가 있는* 경우, 또는 *해결됐다고 적은 항목* 이 *실제로는 race window 가 남아있는* 경우는 §19-B 의 *문서 거짓말* 의 PR 박제 버전.

**점검**: 박제 문서의 "미해결 위험" / "트레이드오프" 섹션을 *코드 본문 grep* 으로 1:1 검증. 박제와 코드가 어긋나면 *박제 갱신* 또는 *코드 fix* 둘 중 하나.

**원칙 (냉정 모드)**:
- 표면 grep 으로 끝내지 않는다. *race window / 분포 시나리오* 추론까지 한다.
- "어설션이 통과한다 = 결함 없음" 으로 결론 내지 않는다. *어설션이 검증하지 않는 사각지대* 가 본질적 위험.
- *Facade try/catch 가 발견되면 try 블록 위 라인까지 거슬러* throw 가능 위치 추적.
- 박제와 코드의 정합을 *마지막에 한 번 더* 본다.

---

### 0️⃣-A 회귀 모드 (Self-Regression Loop)

**언제 회귀 모드를 쓰는가:**

- 사용자가 명시적으로 "회귀 모드 / 자체 반복 / 스탑할 때까지" 요청한 경우
- 외부 리뷰(Copilot 등) 가 *같은 영역에서 반복적으로 새 결함* 을 잡고 있을 때 — fix 자체가 결함 생산자가 됐다는 신호. 외부 리뷰에 의존해 한 라운드씩 풀면 비용이 누적된다.
- 변경 단위가 크거나 한 fix 가 다른 영역을 건드리는 횡단 변경일 때

**회귀 루프 절차:**

0. **Round 0 — Scope 확정**. 변경 *파일* 이 아니라 **PR 전체 변경 영역** 을 점검 대상으로 잡는다.
   ```
   git diff origin/main...HEAD --name-only
   ```
   직전 라운드에 fix 한 파일만 다시 보면, *기존* 파일에 잠복한 동일 패턴 결함을 놓친다 (예: 컬럼 length 가드 누락이 한 VO 에서 발견되면 *모든* `@Column(length=N)` VO 를 grep 으로 횡단 점검). Copilot 은 매 리뷰마다 PR 전체를 보므로, 회귀도 그래야 한다.
1. **Round N 검증** — 두 축을 모두 본다.
   - **본 스킬 §1~§19-B** 풀 점검 (verify-code 자체 룰)
   - **`.github/instructions/*.md`** 의 모든 룰 (Copilot 리뷰가 따르는 동일 기준) — 적용 범위 (`applyTo`) 가 본 PR 변경 파일과 매치되는 instruction 만 활성화
   - 발견 사항을 위치/카테고리/심각도(P0/P1/P2) 표로 정리. 카테고리 컬럼에는 verify-code §번호 *또는* `.github/instructions` 파일명을 적어 출처를 드러낸다.
   - **횡단 grep 필수**: 같은 패턴(예: `@Column.*length=`, `@OrderBy`, `assertThatThrownBy { ... }`) 이 PR 전체에 몇 번 등장하는지를 먼저 세고, 그 *모든* 위치에서 가드가 일관되는지 확인한다.
2. **Fix 적용** — P0/P1 만 처리. P2 는 본 PR scope 밖이면 후속 권고로만 메모.
3. **자체 회귀 (Round N+1)** — 방금 적용한 fix 가 다음을 어겼는지 다시 본다:
   - 새 const / 새 분기 / 새 검증 가드가 **§13 매직 상수** / **§19-B DisplayName** / **§14 DRY** 위반을 만들지 않았는가
   - 새 정책이 **§19-A 운영-테스트 동치성** 을 깨지 않았는가
   - 새 메시지가 **§12 메시지 노출** 을 만들지 않았는가
   - 새 시그니처가 **§3 외부 라이브러리 누출** 을 만들지 않았는가
   - 새 KDoc 의 **"차단 / 보장 / 방지 / 불가능"** 약속이 *실제 가드* 와 일치하는가 (§19-B — 문서 거짓말 방지)
   - 새 `create()` / 팩토리가 **캐시 컬럼을 직접 매개변수로 받지 않는가** (§5 — 생성자 우회 경로 차단)
   - **`.github/instructions` 의 "수용된 트레이드오프 (재지적 금지)"** 를 무심코 깨뜨리지 않았는가 (예: `var ... protected set` 패턴, 테스트 fake reflection 격리)
4. **호출자 영향 검토** — 변경된 시그니처/정책의 사용처를 grep 으로 조사. 새 가드가 기존 호출을 깨뜨리지 않는지.
5. **종료 조건 검사:**
   - 신규 라운드에서 P0/P1 발견 0건 **AND**
   - `.github/instructions` 룰 위반 0건 **AND**
   - 모든 호출자 영향 검토 통과 **AND**
   - `./gradlew ktlintCheck && test` PASS
   - → **defect zero 도달, 회귀 종료**.
6. **종료되지 않으면 Round N+2 로 진입.** 라운드 수가 3을 넘기면 전제(아키텍처/도메인 모델) 자체를 의심.

**`.github/instructions` 활성화 규칙:**

각 instruction 의 frontmatter `applyTo` 글롭과 변경 파일 경로를 매치해 활성화 — 매치 안 되면 N/A 로 표기하고 본 라운드에서 제외한다. 예:

| 변경 파일 | 활성 instruction |
|---|---|
| `domain/property/PropertyModel.kt` | `domain.instructions.md`, `kotlin.instructions.md`, `copilot-instructions.md` |
| `infrastructure/property/PropertyJpaRepository.kt` | `repository.instructions.md`, `kotlin.instructions.md`, `copilot-instructions.md` |
| `application/property/PropertyFacade.kt` | `service.instructions.md`, `kotlin.instructions.md`, `copilot-instructions.md` |
| `interfaces/api/property/PropertyV1Controller.kt` | `controller.instructions.md`, `kotlin.instructions.md`, `copilot-instructions.md` |
| `test/.../*Test.kt` | `test.instructions.md`, `kotlin.instructions.md`, `copilot-instructions.md` |
| `build.gradle.kts` | `gradle.instructions.md` |
| `application*.yml` | `spring-config.instructions.md` |

**회귀 모드 보고 포맷 (각 라운드):**

```markdown
## Round N
### 발견
| # | 위치 | § | 심각도 | 내용 |

### Fix 적용
- ...

### 자체 회귀 (이 fix 가 만들어낸 새 결함)
- §13: ...
- §19-B: ...

### 다음 라운드 필요 여부
- ✅ defect zero — 종료
- ❌ Round N+1 진입 사유: ...
```

**회귀 모드 종료 후:**

- 작업 트리 상태(어떤 파일이 수정·추가됐는지) 를 **반드시** 보고한다.
- **커밋·푸시는 자동으로 하지 않는다** — 사용자 confirmation 후에만 진행. 외부에 노출되는 작업이므로.
- 누적 라운드 수와 각 라운드의 핵심 결함을 한 줄씩 요약해 PR pr.md "고민과 선택" 섹션에 반영하기 좋게 정리.

**원칙:**

- "한 번 fix 했으면 끝" 이 아니라 "fix 가 새 결함을 안 만들었나" 까지 확인해야 회귀가 의미 있다. Copilot 이 한 라운드 더 해서 같은 곳을 다시 잡는 패턴은 대부분 자체 회귀를 안 돌렸기 때문.
- 매 라운드 fix 는 **최소 단위로 쪼갠다** — 한 라운드에 3개 이상의 독립 변경이 섞이면 다음 라운드에서 어떤 fix 가 결함을 만들었는지 추적이 어렵다.
- P2 는 회귀 루프에서 제외 — 누적되면 무한 루프. 본 PR scope 안의 P0/P1 만 종료 조건에 포함.

---

### 0️⃣ 컨텍스트 수집

- 이번 변경의 단위는 무엇인가? (Aggregate / 모델 메서드 / Converter / Repository 시그니처 / Facade / 외부 어댑터)
- 어떤 레이어가 추가/수정되었는가?
- DB 컬럼 스키마(NOT NULL / UNIQUE / 자연 키)가 도메인 타입과 일관되는가?
- 외부 라이브러리(Spring Data, Jackson, Redis client, HTTP client, PG SDK 등) 가 새로 도입되었는가?
- 새 외부 호출(DB 외) 이 도입되었는가? (트랜잭션·재시도·타임아웃 검토 대상)
- **`.github/instructions/*.md` 중 어떤 것이 활성화되는가?** (변경 파일 경로 ↔ instruction `applyTo` 매치) — §0-A 의 활성화 규칙 표 참고
- **변경이 동시성 / 트랜잭션 / 예외 흐름 영역인가?** — 그렇다면 §0-B 냉정 모드 6 체크리스트 (CE-1 ~ CE-6) 를 *명시적으로* 통과시킨다. *표면 grep 으로 끝내지 않는다.*

> 출력: 검증 대상 파일과 활성 instruction 목록을 한 줄로 요약한 뒤 본격적인 점검을 시작한다.
> **종료 직전 §0-B CE-5 (3 회 더 의심) 통과 어셔런스가 없으면 PASS 판정 금지** — 어설션이 통과한다고 결함 0 을 단언하지 않는다.

---

### 1️⃣ Null 일관성 (Three-way Alignment)

도메인 타입 / DB 스키마 / 컨버터 시그니처 — **세 곳 모두** null 정책이 일치해야 한다.

| 확인 | 위반 예 |
|---|---|
| DB 컬럼 `nullable = false` 인데 컨버터가 `attribute: T?` 로 null 통과 | `convertToDatabaseColumn(null)` 이 `null` 또는 `"{}"` 반환 → 제약 위반 / 사일런트 디폴트 |
| 도메인 타입 non-null 인데 `convertToEntityAttribute` 가 `T?` 반환 | 호출자 NPE |
| **같은 코드베이스의 컨버터들이 null 정책이 다름** | 일부는 거절, 일부는 `EMPTY` 반환 — 일관성 깨짐. 한 코드베이스의 모든 컨버터를 한 정책으로 통일 |
| Kotlin 의 `T?` 와 DB `NULL` 의 의미가 코드 주석으로만 추론됨 | 의도가 코드로 드러나지 않음 |

**가드**: NOT NULL 컬럼 컨버터는 null 입력 즉시 `INTERNAL_ERROR`. NULL 허용이면 도메인 타입도 일관되게 `T?`. **여러 컨버터가 있다면 동일 정책** — 한 번에 grep 으로 일치 여부 확인.

---

### 2️⃣ 자식 Entity / Aggregate 내부 식별자 동기화

`@OneToMany` / `@OneToOne` 자식이 부모 PK 를 들고 있을 때 **영속화 전 부모 PK = 0** 이라는 사실이 자주 잊혀진다.

| 확인 | 위반 예 |
|---|---|
| `child = ChildModel(parentId = this.id, ...)` 형태 — 부모 영속화 전이면 0 으로 굳음 | 신규 부모의 자식들이 모두 `parentId=0` 으로 저장 |
| 자식의 `parentId` 컬럼과 부모의 `@JoinColumn` 이 동시에 쓰기 가능 | 두 곳이 같은 컬럼을 채우려고 경합 |
| `@OneToMany` 가 `cascade=ALL` 없이 부모 라이프사이클 분리 | 부모 삭제 시 자식 고아 |
| `_children.remove(...)` 만 하고 `orphanRemoval = false` | DB 에서 안 지워짐 |

**가드**: 부모 PK 컬럼은 `@JoinColumn` 단독 관리 + `insertable=false, updatable=false`. 자식의 `parentId` 는 읽기 전용.

---

### 3️⃣ 외부 라이브러리 / 프레임워크 누출 (Signature-level)

verify-architecture 가 *import* 차원만 본다면, verify-code 는 **시그니처 차원** 누출을 잡는다.

| 확인 | 위반 예 |
|---|---|
| `domain` Repository 가 `org.springframework.data.domain.{Page, Pageable, Sort}` 사용 | 도메인이 Spring Data 에 결합 |
| 도메인 모델이 `JsonNode`, `ObjectMapper`, `RedisTemplate`, `RestTemplate` 인자 | 도메인이 인프라 의존 |
| 도메인 VO 가 `@JsonProperty` / `@JsonInclude` / `@field:NotNull` 보유 | 표현 / 검증 어노테이션이 도메인에 |
| 도메인이 `org.springframework.web.server.ResponseStatusException` 사용 | HTTP 표현이 도메인에 |
| 도메인 시그니처가 `kotlinx.coroutines.flow.Flow` / `reactor.core.publisher.Mono` | 비동기 모델이 도메인에 (선택 — 의식적 결정 시 OK) |

**가드**: 도메인은 자체 VO 만 알고, 변환은 RepositoryImpl / Facade 에서.

확인:
```
Grep "^import org\.springframework\."  path=apps/stay-api/src/main/kotlin/com/stayloop/domain
Grep "^import com\.fasterxml\."        path=apps/stay-api/src/main/kotlin/com/stayloop/domain
Grep "^import io\.lettuce\.|^import redis\." path=apps/stay-api/src/main/kotlin/com/stayloop/domain
```

---

### 4️⃣ 컬렉션 / 페이지 결정성

`@OneToMany` 컬렉션과 페이징 응답이 **DB 재조회 후에도 같은 순서** 인가.

| 확인 | 위반 예 |
|---|---|
| 자식 컬렉션에 `displayOrder` 가 있는데 `@OrderBy` / `@OrderColumn` 누락 | 매번 다른 순서 |
| `findAll()` / `findByX()` 가 정렬 없이 응답 | 페이지마다 흔들림 |
| 페이지 응답의 `total` 을 `List.size` 로 잘못 사용 | 페이지 응답이 잘못됨 |
| `Set` / `HashMap` 으로 순서 의미 있는 컬렉션 표현 | 비결정 순회 |
| **`@OrderBy` 단일 키만 — tie-breaker 없음** (`@OrderBy("displayOrder ASC")`) | 1차 키 동률일 때 비결정 순서, "이건 unique 라고 가정" 은 깨질 약속 |

**가드**:
- `@OneToMany` + `@OrderBy("col ASC")` 또는 메서드 시그니처가 정렬 인자 보유.
- **단일 키 `@OrderBy` 는 항상 `id` 같은 안정적 tie-breaker 와 함께** — `@OrderBy("displayOrder ASC, id ASC")`. "displayOrder 가 unique 다" 라고 가정하지 않는다 (DB 제약이 없으면 깨질 수 있음).

---

### 5️⃣ 캐시 / 역정규화 / 단일 진실 원천 (SSOT)

성능을 위해 도입한 캐시 컬럼(`mainImageUrl`, `wishCount`, `reviewAvg`) 이 **원본과 항상 동기** 인가.

| 확인 | 위반 예 |
|---|---|
| 캐시 컬럼만 갱신, 원본 컬렉션 플래그 미동기화 | `mainImageUrl = url` 만 → `is_main = TRUE` 가 0개/2개 |
| 원본은 갱신했는데 캐시 컬럼 누락 | 캐시 stale |
| 캐시 갱신이 **원본 검증 없이** 입력을 캐시에 저장 | 갤러리에 없는 URL 이 `mainImageUrl` 에 들어감 |
| **생성자/팩토리(`create()`) 가 캐시 컬럼을 직접 매개변수로 받음** — 메서드 가드는 잘 잡아도 *생성 시점의 우회 경로* 가 열림 | `PropertyModel.create(mainImageUrl="...")` 가 갤러리 없이 캐시만 세팅 — 메서드 SSOT 가드를 무력화 |
| `internal` 생성자(JPA hydration 용) 와 *공개* 팩토리(`create()`) 의 책임 구분 부재 | JPA hydration 은 DB 가 일관성 보장한 상태에서만 호출되지만, `create()` 는 외부 입력 — 두 진입점이 같은 매개변수를 받으면 외부도 우회 가능 |

**가드**:
- 캐시 갱신 *메서드* 는 (1) 원본 검증 → (2) 둘 동시 갱신 → (3) 검증 실패 시 둘 다 불변.
- **`create()` 팩토리는 캐시 컬럼을 매개변수로 받지 않는다.** 캐시는 *상태 변경 메서드* (`addImage(isMain=true)` / `replaceMainImage()`) 를 통해서만 갱신. JPA hydration 용 `internal constructor` 는 매개변수를 유지하되 *외부 진입점 (`create()`) 과 분리* — DB 의 일관성 보장에 의존하는 경로는 하나만 둔다.

---

### 6️⃣ 입력 검증 / 도메인 불변식 보호

생성자 / `create()` / `from()` 에 **모든** 도메인 불변식이 검증되는가.

| 확인 | 위반 예 |
|---|---|
| `@Column(nullable = false)` 인데 도메인 init 검증 없음 | DB 가 막아주길 기다림 |
| **`@Column(length = N)` 인데 init length 가드 없음** | DB 제약 위반(500) — 사용자에게는 `BAD_REQUEST` 가 아니라 알 수 없는 500 응답 |
| **컬럼 length 와 init 가드의 상수가 분리되어 있음** | 한 쪽만 변경 시 어긋남 — 동일 `companion const val X_MAX_LENGTH` 로 묶여야 함 |
| **캐시 컬럼(역정규화)에 length 가드가 빠짐** (예: `mainImageUrl`) | 원본 컬럼은 가드돼도 캐시 갱신 경로가 우회 |
| 인공 PK 참조가 0/음수 허용 | `propertyId: Long` 0 통과 |
| 음수 / 0 / 빈 문자열 / 너무 긴 문자열 가드 누락 | `Money(-1)` / `Name("")` 통과 |
| 빈 컬렉션 허용 안 되는데 `emptyList()` 통과 | `BedConfig(emptyMap())` |
| 멱등 흐름에서 카운터 음수 진입 | `decrement()` 가 가드 없음 |
| 상태 전이가 `canTransitTo` 검증 없이 직접 set | `status = CANCELLED` 직접 대입 |
| Map / Set 컬렉션 입력의 키 / 값 nullable 검증 누락 | `Map<K, V>` 의 V 가 null 통과 |
| **상태 변경 → 검증 순서** — 변경을 먼저 하고 검증이 뒤에 오면 예외 시 부분 변경이 영속화 | `unmarkAsMain()` × N 후에 `create()` 가 예외 → main 플래그 사라짐 |
| **`@Embedded VO` 와 외부 entity 가 같은 컬럼명 매핑 (`@Column(name="X")`)** | `@Embedded property: PropertySnapshot` 의 `propertyId @Column(name="property_id")` + 외부 `var propertyId @Column(name="property_id")` → Hibernate bootstrap **컬럼 중복 매핑 예외**. 단위 테스트는 통과 (JPA 부트스트랩 미수반) — `@DataJpaTest` / 풀 컨텍스트 시점에 폭발 |
| **cheap input 가드가 부수효과 *후* 에 발동** | Service 가 외부 자원(`inventories.forEach { reserveOne() }`)을 먼저 변경하고 *그 다음* `ReservationModel.create()` 가 `guestCount <= 0` 거절 — Facade TX 가 없거나 호출자가 예외를 잡으면 부분 변경 잔존. 검증 순서 위반의 강화판 |
| **호출자 주입 컬렉션 ↔ Aggregate 정합 가드 부재** | `cancel(reservation, inventories)` 에서 inventories 의 roomTypeId / dates 가 reservation 과 일치하는지 검증 안 함 → 잘못된 자원 복원 가능 |

**가드**:
- `init { require(...) }` 또는 `if (...) throw CoreException(ErrorType.X, "...")`. DB 제약은 *마지막* 방어선.
- **컬럼 length ↔ 도메인 가드 일관성**: `@Column(length = N)` 가 있는 모든 String 필드에 대응하는 `init { if (value.length > N) throw ... }` 가 있어야 한다. 두 N 은 *같은* `companion object` 의 `const val MAX_LENGTH` 로 묶어 한 곳에서 관리. `Name.kt` / `Address.kt` / `PropertyImageModel.kt` 가 정렬 패턴.
- 캐시 컬럼(역정규화) 도 동일 길이 가드 — 원본만 가드하고 캐시 setter 를 우회하면 위반 (verify-code 회귀 사각지대).
- **상태 변경 메서드의 순서**: 검증·생성 먼저, 상태 변경은 통과 후에. Aggregate 메서드가 도중에 예외를 던져도 객체는 일관된 상태로 남아야 함 (Strong Exception Safety / Copilot 패턴).
- **JPA 컬럼 중복 매핑 방지**: 한 entity 내에서 동일 `@Column(name = "X")` 가 두 곳에서 매핑되면 Hibernate bootstrap 시 폭발. 박제 VO (`@Embedded`) 가 FK 컬럼을 들고 있으면 외부 entity 는 *위임 프로퍼티* (`val propertyId: Long get() = property.propertyId`) 로 노출하고 자체 `@Column` 매핑하지 않는다. 다른 해결책: `insertable=false, updatable=false` 또는 `@AttributeOverride` — 다만 SSOT 가 흐려지므로 **위임 패턴이 1순위**.
- **cheap input early guard**: 부수효과 (외부 자원 mutation, 상태 전이, 영속화) *이전* 에 *비싸지 않은* 입력 검증 (`> 0`, `not blank`, `length <= N`) 을 끝낸다. Service / Facade 의 메서드 시작 직후 검증을 모아두는 패턴 — Strong Exception Safety 의 service-level 강화.
- **외부 주입 컬렉션 일관성**: Service 가 호출자로부터 entity / VO 컬렉션을 받을 때, *Aggregate Root 가 보유한 식별자* (예: `reservation.roomTypeId`, `reservation.period.datesToReserve()`) 와 일치하는지 가드. mutate (`reserveOne()` / `releaseOne()`) 이전에. 호출자 실수로 다른 자원을 변경하지 않게.

**점검 명령** (이 가드 누락 일괄 검출):
```
Grep "@Column.*length\s*=\s*\d+" path=apps/.../main → 모든 length 컬럼 추출
→ 같은 파일에 `length >` 가드가 있는지 대조

Grep "@Column\(name\s*=\s*\"" path=apps/.../main → 같은 entity 내 동일 컬럼명이 두 곳에 등장하는지 횡단
```

---

### 7️⃣ 멱등성 / 부수효과 일관성

같은 요청을 두 번 호출해도 도메인 상태가 일관되게 유지되는가.

| 확인 | 위반 예 |
|---|---|
| `wish` 멱등인데 카운트가 두 번 증가 | 같은 요청 2번 → wish_count +2 |
| `unwish` 가 미찜 상태에 호출되면 카운트 음수 | `wishCount` 가드 없음 |
| `cancel` 이 이미 취소된 상태에 호출되면 inventory 두 번 복원 | 상태 전이 검증 누락 |
| 외부 호출이 트랜잭션 중간에 일어나 롤백 시 부수효과만 남음 | TX 내 SMS 발송 |

**가드**: 멱등 진입점 = (1) 현재 상태 검사 → (2) 변화 없으면 noop → (3) 변화 있으면 한 번만.

---

### 8️⃣ 사일런트 디폴트 / 빈 값 변환

null / 빈 입력이 **조용히** 정상 값으로 변환되어 버그를 늦게 폭발시키는 패턴.

| 확인 | 위반 예 |
|---|---|
| `convertToDatabaseColumn(null)` → `"{}"` 빈 맵 JSON | 사일런트 디폴트 |
| `find...()` 결과 null 을 빈 객체로 변환 | "없음" 을 인지 못함 |
| `?: defaultValue` 가 비즈니스 의미 검토 없이 사용 | 잘못된 디폴트가 정답인 양 |
| `try { ... } catch { return emptyList() }` | 진짜 실패 무시 |
| `if (x.isNullOrBlank()) "" else x` 같은 정규화 없는 변환 | 의미 손실 |

**가드**: null / 빈 값이 정상 도메인 값이 아니면 즉시 예외.

---

### 9️⃣ 동시성 / 스레드 안전성

| 확인 | 위반 예 |
|---|---|
| 도메인 모델의 `var` 필드를 다중 스레드가 동시 변경 | 데이터 경합 |
| 정적 mutable 컬렉션 (`mutableListOf<>` companion) | 동시 변경 |
| `ThreadLocal` 누수 (요청 종료 시 clear 누락) | 스레드 풀에서 데이터 누출 |
| `lazy { }` 가 동기화 없이 stateful 객체 초기화 | 첫 호출 race |
| `@Async` 메서드가 트랜잭션 컨텍스트 전파 가정 | 트랜잭션 끊김 |
| 컬렉션 순회 중 변경 (`ConcurrentModificationException` 위험) | `for (x in list) list.remove(x)` |
| 단일 스레드 가정의 카운터를 production 으로 그대로 가져감 | `wishCount += 1` race |
| **다일자 (다중 row) 비관적 락 시 `ORDER BY` 미명시** — 락 획득 순서가 비결정적이면 두 트랜잭션이 *서로 다른 순서* 로 잡으면서 cycle / 데드락 발생 | `findInventoriesForUpdate(roomTypeId, dates)` 의 SQL 이 `WHERE date IN (?, ?, ...) FOR UPDATE` 만 있고 `ORDER BY date ASC` 누락 — 두 thread 가 dates `[5/10, 5/11]` ↔ `[5/11, 5/10]` 순서로 락 시도 → InnoDB cycle 검출 → 한쪽 deadlock victim. **락 순서를 SQL 과 *진입점 (Facade)* 양쪽에 명시** — Facade 가 `period.datesToReserve().sorted()` 로 호출하고 SQL 도 `ORDER BY date ASC` 박제. 둘 중 한쪽만 있으면 *문서 ↔ 가드 정합* 깨짐 (§19-B 와 한 쌍). |
| **운영 atomic UPDATE 의 InMemory 더블이 *같은 인스턴스* 를 mutate** — Facade 응답값을 `entity.field + 1` 형태로 작성 시 운영(stale entity) ↔ InMemory(mutated entity) 응답이 갈림 | `WishlistFacade.wish` 가 `WishlistToggleInfo(wishCount = property.wishCount + 1)` — 운영(QueryDSL `update().execute()`) 은 entity manager 우회로 `property.wishCount` stale 유지, InMemory 의 `atomicIncrementWishCount` 가 도메인 메서드로 인스턴스 mutate. 결과: 운영 1 / InMemory 2. **atomic 호출 *이전* 의 값을 별도 변수로 박제** (`countBefore`) 하는 패턴이 정합 — verify-code §19-A 운영-테스트 동치. |

**가드**:
- 본 라운드(2~3주차)는 단일 스레드 가정 — 다만 **4주차에서 락/원자 연산이 들어올 자리** 를 본 라운드 코드가 이미 막지 않게 한다 (예: 카운터 갱신을 모델 메서드로 캡슐화).
- **다일자 락 ORDER BY 강제** (4주차 ③ Phase A/D 학습): Facade `@Transactional` 안에서 다중 row 비관적 락을 잡을 때, *SQL 의 ORDER BY 정렬* 과 *Facade 진입점의 입력 정렬* 둘 다 명시. 한쪽만 있으면 문서 거짓말 또는 Hibernate Optimization 으로 정렬이 사라질 위험.

**점검 명령**:
```
Grep "@Lock\\(LockModeType\\.PESSIMISTIC_WRITE" path=apps/.../main → 모든 비관적 락 메서드
Grep "setLockMode\\(.*PESSIMISTIC" path=apps/.../main → QueryDSL 비관적 락
→ 각 메서드의 SQL 또는 QueryDSL 빌더에 `orderBy(...)` 가 있는지 확인
→ Facade 호출 측에 `.sorted()` 또는 동등한 정렬 보장이 있는지 확인
```

---

### 🔟 자원 관리 (Resource Lifecycle)

| 확인 | 위반 예 |
|---|---|
| `Closeable` / `AutoCloseable` 자원이 `use { }` / try-with-resources 없이 사용 | 누수 |
| 외부 클라이언트(HTTP, Redis, JDBC) 의 connection / pool 정리 누락 | 풀 고갈 |
| 파일 / Stream 처리에서 close 누락 | FD 누수 |
| `Flux` / `Mono` 의 dispose / cancel 처리 누락 | 메모리 누수 |
| `Schedulers` 직접 생성하고 종료 안 함 | 스레드 누수 |

**가드**: 자원은 항상 `use { }` 또는 framework 의 lifecycle 에 위임.

---

### 1️⃣1️⃣ 시간 / Clock / Timezone

| 확인 | 위반 예 |
|---|---|
| 도메인이 `LocalDateTime.now()` / `Instant.now()` 직접 호출 | 테스트에서 시간 고정 불가 |
| 시간 계산이 `Clock` 주입 없이 진행 | 비결정 테스트 |
| `LocalDateTime` 과 `ZonedDateTime` 의 무분별 혼용 | 시간대 사고 |
| `Date` (legacy java.util.Date) 사용 | 문자열 기반 시간대 사고 |
| KST/UTC 변환 없이 DB `TIMESTAMP` 와 도메인 `LocalDate` 매핑 | `04-erd.md §0` 위반 |

**가드**: 도메인은 `Clock` 을 인자로 받아 `LocalDateTime.now(clock)`. 1주차의 `config/ClockConfig.kt` 사용. DATE 변환 시 명시적으로 `ZoneId.of("Asia/Seoul")`.

---

### 1️⃣2️⃣ 예외 처리 / 에러 전략 / Cause 체인 / 메시지 노출

| 확인 | 위반 예 |
|---|---|
| `catch (e: Exception) { ... }` / `catch (e: Throwable) { ... }` 의 광범위 catch | 진짜 실패 삼킴 |
| catch 블록에서 로그만 찍고 swallow | "No-op except log" |
| 도메인이 `RuntimeException` / `IllegalArgumentException` 을 직접 throw | `CoreException(ErrorType, ...)` 컨벤션 위반 |
| `e.printStackTrace()` 사용 | 표준 로거 미사용 |
| **catch 후 새 예외 throw 시 `cause` 보존 안 함** | stack trace 단절, 운영 디버깅 불가 |
| `try { ... } catch { return null }` 형태 | 호출자가 실패 인지 못함 |
| **외부 입력 / 외부 라이브러리 예외 메시지(`e.message`)를 사용자 응답에 노출** | 시스템 내부 정보 / 원본 데이터 / 스택 정보가 외부로 누출 — 보안 위험 |
| catch 시 메시지 일반화 없이 raw 메시지 그대로 wrap | `CoreException(INTERNAL_ERROR, "JSON 파싱: ${e.message}")` → 응답에 `path[0].field` 등 내부 구조 노출 |
| **읽기 측만 try/catch, 쓰기 측은 raw 통과** | `convertToEntityAttribute` 는 cause 보존하면서 `convertToDatabaseColumn` 의 `writeValueAsString` 예외는 무가공 — 같은 컨버터 안에서 정책이 갈림 |
| **로그에 raw payload 를 무제한 노출** (`log.warn("...dbData='{}'", dbData, e)`) | 길이/PII/시크릿 누설 — 로그 부피·보안 양쪽 함정 |
| **KDoc `@property` vs `@param` 혼동** | `val/var` 없는 생성자 매개변수에 `@property` 를 적으면 IDE 가 link 를 못 찾고 문서가 거짓말. property 만 `@property`, 단순 매개변수는 `@param` |
| **`CoreException` 의 `customMessage` 에 외부 식별자(LoginId / email / userId / 토큰 ID 등) 를 직접 박음** | `ApiControllerAdvice` 가 `customMessage` 를 응답으로 흘리므로 식별자가 클라이언트에 노출. 계정 enumeration / 식별자 추적 위험. `"사용자가 존재하지 않습니다: ${userId.value}"` 형태는 P1 보안 결함 |
| **JPA 예외 (`OptimisticLockingFailureException` / `DataIntegrityViolationException` / `LockTimeoutException` / `PessimisticLockingFailureException`) 가 도메인 메시지로 *그대로* 노출** | Facade 가 catch 하지 않고 throw 가 layer 를 뚫고 ApiControllerAdvice 에 도달하면, 응답 메시지가 *Hibernate / Spring 내부 텍스트* (`"could not execute statement [Duplicate entry '1-1' for key 'wishlists.PRIMARY']"` 등) 로 직행. (a) 식별자 노출 (PK 컬럼 / row 값), (b) 도메인 의미 부재 (사용자가 *왜* 실패했는지 모름), (c) ApiControllerAdvice 매핑 불일치 (500 응답). **Facade 의 `try { repo.save(...) } catch (e: OptimisticLockingFailureException) { throw CoreException(CONFLICT, "이미 사용된 쿠폰입니다.", cause = e) }`** 패턴으로 일반화 + cause 보존 (4주차 ③ Phase B 학습). |
| **Facade try/catch 의 *catch scope* 가 도메인 사고의 *모든 throw 경로* 를 감싸지 않음** (§0-B CE-1 정합) | Facade 가 `model.someMethod()` 호출 *후* `try { repo.save(model) } catch (e: JpaException) { ... }` — `model.someMethod()` 가 *자체 도메인 검증* (`canTransitTo(...)` / `requireOwner(...)`) 으로 throw 하면 catch 밖으로 흘러 *다른 메시지* 로 응답. 같은 사고 (예: "이미 사용된 쿠폰") 가 REPEATABLE_READ snapshot 분포에 따라 *흐름 1 (snapshot 이전 시작 → JPA 예외)* / *흐름 2 (snapshot 이후 시작 → 도메인 throw)* 로 갈리고, 두 메시지가 다르면 UX 일관성 깨짐 (분포 시나리오 §0-B CE-2). **try 블록을 그 도메인 사고의 *모든 throw 위치* (도메인 메서드 + Repository) 까지 확장** + `catch (e: CoreException) { if (e.errorType == ConflictType) throw CoreException(ConflictType, "<일반화 메시지>", cause = e); throw e }` 로 *errorType 별 분기 + 메시지 정규화*. 4주차 ③ PR 의 cold-eye 라운드에서 발견. |
| **catch 한 예외의 *cause 보존* 이 도중에 끊김** | `catch (e: JpaException) { throw CoreException(CONFLICT, "메시지") }` — `cause = e` 누락 시 stack trace 단절. 운영에서 *어느 SQL / 어느 row* 였는지 추적 불가. 모든 catch 후 throw 는 `cause = e` 명시 필수. |

**가드**:
- **`CoreException(errorType, customMessage, cause)` 시그니처를 항상 사용** — `cause` 로 원인 보존.
- **클라이언트 메시지는 일반화** ("정책 데이터 처리 실패"), **상세 원인은 로그로만** (`log.warn("...", e)`).
- 외부 라이브러리 예외(`Jackson`, `JDBC`, `Redis`) 의 메시지는 `e.message` 를 customMessage 에 넣지 않는다. `cause` 로 보존하고 로그에서 추적.
- **read 측이 try/catch 라면 write 측도 동일 정책으로 감싼다** — Jackson 의 `writeValueAsString` 도 `JsonProcessingException` 을 던질 수 있다. 한 컨버터 안에서 한 쪽만 감싸면 비대칭 (Copilot 3차 가드).
- **로그에 들어가는 raw payload 는 길이 + 프리뷰만**. 예: `log.warn("X 역직렬화 실패. length={}, preview='{}'", dbData.length, dbData.take(80), e)`. PREVIEW_LIMIT 은 `private const`.
- **`CoreException(ErrorType.X, customMessage)` 의 `customMessage` 는 *클라이언트 응답으로 직행* 한다** — `ApiControllerAdvice` 가 그대로 `ErrorResponse.message` 로 흘림. 따라서 식별자(LoginId / email / userId / reservationId / token) 를 메시지에 박지 않는다. 메시지는 `"사용자가 존재하지 않습니다."` 처럼 일반화하고, 식별자는 별도로 `log.warn("user not found loginId={}", loginId.value)` 로 서버 로그에만 남긴다. 운영-테스트 동치를 위해 InMemory 더블의 메시지도 동일 정책 (§19-A).
- **JPA 예외 → CoreException 변환은 Facade 책임** (4주차 ③ Phase B 박제): 도메인 / Repository 가 throw 한 raw JPA 예외 (`OptimisticLockingFailureException`, `DataIntegrityViolationException`, `LockTimeoutException`, `PessimisticLockingFailureException` 등) 는 *Application Layer 의 Facade try/catch* 에서 `CoreException(ErrorType.CONFLICT, "<도메인 메시지>", cause = e)` 로 변환. 같은 도메인 사고 (예: "이미 사용된 쿠폰") 가 여러 JPA 예외 경로 (낙관적 락 vs UNIQUE) 로 발생할 수 있으면 두 catch 블록의 *throw 메시지를 동일* 하게 — 클라이언트 응답이 일관되게 보이고, cause 의 분기 정보로 운영 디버깅은 가능.

```kotlin
override fun convertToDatabaseColumn(attribute: T?): String {
    if (attribute == null) throw CoreException(ErrorType.INTERNAL_ERROR, "X 데이터 처리 실패")
    return try {
        OBJECT_MAPPER.writeValueAsString(attribute)
    } catch (e: Exception) {
        log.warn("X JSON 직렬화 실패.", e)
        throw CoreException(ErrorType.INTERNAL_ERROR, "X 데이터 처리 실패", cause = e)
    }
}

override fun convertToEntityAttribute(dbData: String?): T {
    if (dbData.isNullOrBlank()) throw CoreException(ErrorType.INTERNAL_ERROR, "X 데이터 처리 실패")
    return try {
        OBJECT_MAPPER.readValue(dbData)
    } catch (e: Exception) {
        log.warn("X JSON 역직렬화 실패. length={}, preview='{}'", dbData.length, dbData.take(PREVIEW_LIMIT), e)
        throw CoreException(ErrorType.INTERNAL_ERROR, "X 데이터 처리 실패", cause = e)
    }
}
```

---

### 1️⃣3️⃣ 매직 상수 / 가독성

| 확인 | 위반 예 |
|---|---|
| 매직 넘버 / 매직 스트링이 코드 본문에 흩어짐 | `if (length > 50)` / `"WIFI"` |
| `companion object const` 로 추출되지 않은 임계값 | `MAX_LENGTH = 50` 누락 |
| 메서드 시그니처에 `Boolean` 플래그 (`fun foo(force: Boolean)`) 가 의미 모호 | 호출 측 가독성 |
| 깊은 중첩 `if`/`when` (3레벨 이상) | 분기 의도 불명확 |
| 함수가 100라인 초과 / 인자 6개 초과 | 책임 분해 부족 |
| 의미 없는 변수명 (`a`, `tmp`, `data`, `list`) | 도메인 어휘 누락 |

**가드**: 임계값은 `companion object` 의 `private const`. Boolean 플래그 대신 sealed class / enum.

---

### 1️⃣4️⃣ 코드 중복 (DRY)

| 확인 | 위반 예 |
|---|---|
| 같은 검증 로직이 여러 VO 에 중복 (예: 여러 곳의 `if (value.isBlank()) throw ...`) | 가드 함수 추출 후보 |
| Converter 들이 동일 ObjectMapper 초기화 코드 반복 | 공용 베이스 / Bean 추출 |
| Facade 들이 같은 인가 검증 (loginId 일치) 반복 | `requireOwner(...)` 추출 |
| Repository 구현이 비슷한 변환 로직 반복 | 공용 매퍼 |

**가드**: 3회 이상 반복 = 추출 권장. 단, 무리한 추상화는 더 큰 비용 — 의도가 다른 중복은 그대로 두는 게 낫다.

---

### 1️⃣5️⃣ 가시성 / 캡슐화

| 확인 | 위반 예 |
|---|---|
| `var` 필드가 `public` 노출 (`var wishCount: Int = 0`) | 외부에서 직접 변경 가능 |
| 내부 변경 메서드(`internal fun`) 가 `public` | 의도치 않은 호출 가능 |
| 도메인 모델의 컬렉션이 mutable 노출 (`val items: MutableList<>`) | 외부에서 add/remove |
| `protected set` 누락 | JPA 가 set 하는 것과 외부가 set 하는 것 분리 못함 |
| `internal constructor` 없는 모델 — `new Model(...)` 직접 호출 가능 | `create()` 우회 |

**가드**: 도메인 모델은 `internal constructor` + `protected set` + `companion object create()`. 컬렉션은 backing field `_items` + `val items: List<...> get() = _items.toList()`.

---

### 1️⃣6️⃣ 트랜잭션 / 영속성 함정

| 확인 | 위반 예 |
|---|---|
| Facade 가 `@Transactional` 없이 여러 Repository 호출 | 부분 실패 시 일관성 깨짐 |
| 도메인 서비스에 `@Transactional` (Stayloop 컨벤션 위반) | TX 단일 진입 위반 |
| `@Transactional(readOnly = true)` 안에서 쓰기 | 런타임 실패 |
| `@Transactional` 메서드가 같은 클래스의 다른 메서드 호출 (self-invocation) | TX 적용 안됨 |
| Lazy 컬렉션을 트랜잭션 밖에서 접근 | LazyInitializationException |
| `flush()` / `clear()` 직접 호출 | 영속성 컨텍스트 의도 깨짐 |
| **`@Lock(PESSIMISTIC_WRITE)` / `setLockMode(PESSIMISTIC_WRITE)` 메서드를 *트랜잭션 밖* 에서 호출** | Facade `@Transactional` 누락 또는 *별도 메서드의 `@Transactional` 가 없는 호출자* 가 직접 호출 → 락이 statement 종료 즉시 해제 → 후속 read-modify-write 가 race window 노출. 비관적 락의 *목적 자체가 무력화*. **반드시 Facade `@Transactional` 안에서만 호출** — 도메인 서비스 / Repository 내부에서 직접 호출 X. |
| **`saveAndFlush` 가 호출 시점 throw 의미를 약속하는데 `@Transactional` 밖에서 호출** | Facade 가 `@Transactional` 누락 시 `saveAndFlush` 가 *자체 TX* 로 commit → `@Version` 충돌은 *commit 시점* 에 throw. Facade try/catch 가 잡지 못함. **`saveAndFlush` 도 비관적 락과 동일하게 Facade `@Transactional` 안에서만 의미 있음** — 호출 시점 throw 의 의미 계약이 TX 경계에 의존. |

**가드**:
- `@Transactional` = Facade 한 곳. Lazy 컬렉션은 Facade 내부에서만 접근.
- **비관적 락 / saveAndFlush 의 트랜잭션 의무 명시**: 도메인 Repository 인터페이스 KDoc 에 *"본 메서드는 `@Transactional` 안에서만 호출되어야 한다"* 박제. 호출자가 무심코 `@Transactional` 누락 시 *문서 거짓말* 검출 (§19-B 와 한 쌍).

**점검 명령**:
```
Grep "@Lock\\(LockModeType\\.PESSIMISTIC|setLockMode\\(.*PESSIMISTIC|saveAndFlush" path=apps/.../main → 모든 비관적 락 / saveAndFlush 호출
→ 각 호출 측 메서드를 추적해 *진입점 (Facade)* 의 `@Transactional` 어노테이션 확인
→ KDoc 의 "@Transactional 안에서만" 약속 ↔ 실제 호출자의 가드 정합
```

---

### 1️⃣6️⃣-A 외부 입력 ↔ 내부 매핑 (Whitelisting)

도메인 어휘(정렬 키, 필터 키, 카테고리 등) 가 인프라 / SQL / Spring 의 내부 식별자로 변환될 때
**화이트리스트 없이 외부 입력이 그대로 흘러가면** 잘못된 path / 잘못된 SQL / 500 에러로 이어진다.

| 확인 | 위반 예 |
|---|---|
| 정렬 키 매핑이 `map[key] ?: key` 형태로 미등록 키를 그대로 통과 | `?sort=password DESC` 가 `password` 컬럼 정렬 시도 → SQL 폭발 / 정보 노출 |
| 도메인 정렬 키가 `@Embedded` VO 인데 매핑이 `vo` 만 (`vo.value` 누락) | `Sort.by("rating")` 이 `rating.value` 가 아니라 임베디드 객체로 정렬 시도 → 런타임 실패 |
| 운영 RepositoryImpl 과 InMemory 더블의 미등록 키 정책이 다름 | 한쪽은 무시, 한쪽은 실패 — 테스트가 운영을 신뢰할 수 없음 |
| 외부 입력 enum 매칭이 `valueOf` 직접 (예외 핸들링 없음) | `IllegalArgumentException` 이 500 으로 |
| **외부 입력을 받지만 조용히 무시 (silent ignore)** — 정렬 / 필터 / 페이징 / 검색 옵션 등 | `findByX(query: PageQuery)` 가 `query.sort` 를 받아놓고 항상 고정 정렬을 적용하면, 호출자는 자기 sort 가 작동하지 않는 걸 깨닫지 못함. 디버깅 시간만 늘어나는 silent bug. **고정 정책이면 sort 가 비어있지 않을 때 BAD_REQUEST 거절**, 아니면 화이트리스트 + 변환. 받지 않을 거면 시그니처에서 `PageQuery.sort` 를 못 받게 별도 타입으로 분리. |
| **`set(A) == set(B)` 만으로 list 동치 비교** — list 의 *중복* 을 못 잡음 | `inventories.map{date}.toSet() == expectedDates` 만 비교 시 `[5/10, 5/10, 5/11]` 이 `{5/10, 5/11}` 과 동일 set 으로 통과 → 같은 일자 자원이 두 번 mutate. **size + set 페어** 가 1:1 매칭의 최소 가드 (`list.size == set.size && list.toSet() == expected`). 1:1 매칭이 본질이면 `groupBy{date}.values.all{it.size==1}` 까지 가는 것도 검토 |
| **외부 주입 컬렉션이 Aggregate Root 의 식별자와 일치하는지 가드 부재** | `cancel(reservation, inventories)` / `reserve(..., inventories, rates)` 에서 `inventory.roomTypeId == roomTypeSnapshot.roomTypeId` 검증 누락 → 호출자 실수로 다른 객실의 inventory 를 주입해도 통과 → 잘못된 자원 mutate. mutate (`reserveOne()` / `releaseOne()`) **이전** 에 식별자 가드를 둔다 |

**가드**:
- **화이트리스트 + BAD_REQUEST 거절** — 미등록 키는 도메인이 받지 않는다. 운영 / 테스트 양쪽 동일 정책.
- `@Embedded` VO 의 정렬 경로는 `vo.field` 형태로 명시 (`Rating` → `rating.value`).
- enum 변환은 try/catch + BAD_REQUEST.

```kotlin
// 운영 RepositoryImpl
private val ALLOWED_SORT_KEYS: Map<String, String> = mapOf(
    "wishCount" to "wishCount",
    "rating" to "rating.value",   // @Embedded → 내부 필드
    "name" to "name.value",
)

private fun toSpringSort(keys: List<SortKey>): Sort = ...
    val column = ALLOWED_SORT_KEYS[key.property]
        ?: throw CoreException(ErrorType.BAD_REQUEST, "지원하지 않는 정렬 키: ${key.property}")
```

---

### 1️⃣7️⃣ 성능 함정 (조기 경보)

성능 최적화는 4주차 영역이지만, **본 라운드 코드가 4주차에서 풀기 어려운 함정을 만들지 않게** 본다.

| 확인 | 위반 예 |
|---|---|
| `findAll()` 후 메모리 필터링 | 항상 페이징/Where |
| 루프 안에서 Repository 호출 (N+1) | `properties.forEach { repo.findX(it.id) }` |
| `@OneToMany(fetch = EAGER)` 무분별 사용 | 항상 join |
| 큰 텍스트(`@Column(columnDefinition = "TEXT")`) 항상 SELECT | projection 미적용 |
| 인덱스 없는 컬럼으로 빈번 검색 | 본 라운드 ERD 와 정합 확인 |
| 배열 / 컬렉션 변환을 매 호출마다 (`toList()` 반복) | 캐싱 후보 |
| **`@IdClass` / `@EmbeddedId` 의 PK 컬럼·순서와 동일한 `@Table(indexes = ...)` 보조 인덱스 명시** | PK 자체가 같은 인덱스를 제공 — 중복 인덱스가 쓰기 amplification + 스토리지 비용으로 누적 |
| 단일 `@Id` 컬럼과 동일한 `@Table(indexes = ...)` (예: `id` 컬럼만 있는 보조 인덱스) | PK 가 cluster 인덱스로 충분 |
| **Read-then-Write 의 SELECT 중복** — `existsById → deleteById` / `existsById → save` / `findById → save` 등 동일 키에 대한 select 가 두 번 발생 | `deleteById` 자체가 내부적으로 select 후 delete — 외부에서 `existsById` 추가하면 SELECT 2회. silent noop 만 필요하면 `findById(key).ifPresent { delete(it) }` (1회 SELECT) 또는 `@Modifying @Query("DELETE ...")` (0회 SELECT, 1회 DELETE). 멱등 / 부재 허용을 위한 가드가 *쿼리 횟수* 를 늘리지 않게 한다. |

**가드**:
- 루프 안 Repository 호출 → `findAllByXIn(...)` 시그니처. EAGER 는 명시적 이유 없으면 LAZY.
- **`@Table(indexes = ...)` 를 추가하기 전에 PK 와 컬럼·순서가 겹치는지 확인**. 같으면 제거. *다른* 접근 패턴(예: 자식 entity 의 `(parent_id, display_order)` / `city` 단독 / 비-PK 컬럼) 일 때만 명시.
- 중복을 피하기 위해 KDoc 에 "**인덱스 정책**" 섹션을 두고 *왜 명시 안 했는지 / 어떤 접근 패턴에서만 추가하는지* 를 박아둔다 — 후임이 무심코 다시 추가하지 않게.

**점검 명령**:
```
Grep "indexes\s*=" path=apps/stay-api/src/main/kotlin → 모든 @Table indexes 추출
→ 같은 파일의 @IdClass / @EmbeddedId / @Id 컬럼과 columnList 비교
```

---

### 1️⃣8️⃣ 보안 (Stayloop 컨텍스트)

| 확인 | 위반 예 |
|---|---|
| 시크릿 / API 키 / DB 비밀번호가 코드 / `application.yml` 에 평문 | 외부화 필요 |
| 사용자 입력을 SQL native query 에 문자열 결합 | SQL injection — `@Param` 사용 |
| 로그에 PII (전화번호 / 이메일 / 비밀번호) 그대로 출력 | 마스킹 필요 |
| `X-Loopers-LoginId` 헤더 검증 없이 신뢰 | 인가 누락 |
| 본인 자원 검증이 도메인 안 (도메인이 누가 호출했는지 알아서는 안됨) | Application Layer 책임 |
| Path Traversal — 사용자 입력을 파일 경로에 사용 | sanitize 누락 |
| OWASP Mass Assignment — `@RequestBody` 가 도메인 모델 직접 받음 | DTO 분리 필요 |

**가드**: 시크릿 = 환경변수. 본인 자원 검증 = Application Layer. SQL 은 `@Query` + `@Param`.

---

### 1️⃣9️⃣ 도메인 어휘 / Ubiquitous Language

| 확인 | 위반 예 |
|---|---|
| 코드의 식별자가 `01-requirements.md §1` 의 어휘와 어긋남 | `Hotel` vs `Property` 혼용 |
| ENUM 값이 비즈니스 어휘가 아닌 기술 약자 | `STATE_1`, `T2` |
| 같은 개념이 여러 이름 (`user` / `member` / `account`) | 혼동 |
| 영문/한글 혼용 (`getUserMok` 같은) | 일관성 |

**가드**: `01-requirements.md §1 도메인 용어` 표를 단일 진실 원천으로.

---

### 1️⃣9️⃣-A 테스트 더블 ↔ 운영 동작 동치성 (Production-Test Parity)

테스트용 InMemory 구현은 **운영 Repository 와 동일한 의미론** 을 따라야 한다. 갈리면 회귀가 마스킹된다.

| 확인 | 위반 예 |
|---|---|
| 운영은 다중 키 정렬 지원, **테스트 더블은 첫 키만 적용** | 다중 키 회귀 발생 시 단위 테스트는 통과 |
| 운영은 빈 컬렉션 입력에 short-circuit, 테스트 더블은 일반 경로 | 동작 차이가 단위 테스트에 안 잡힘 |
| 운영은 정렬 키 화이트리스트로 거절, **테스트 더블은 fallback 으로 통과** | 의도치 않은 키가 단위 테스트에서만 통과 (Copilot #8 의 정확한 함정) |
| 운영의 페이지네이션 경계(예: empty page, last page partial) 가 테스트 더블에서 다르게 처리 | 응답 포맷 회귀 |
| 운영의 예외 정책(`BAD_REQUEST` vs `INTERNAL_ERROR`) 이 테스트 더블에서 다른 ErrorType | 컨트롤러 매핑 회귀 |

**가드**: 테스트 더블의 분기 의미론(정렬, 화이트리스트, null 정책, 예외 매핑)은 **운영 코드에서 그대로 카피해 검증** 한다. "테스트 더블이라 단순화" 는 결함이 아니라 **회귀 사각지대**.

```kotlin
// ✗ 안 좋음 — 운영은 다중 키, 더블은 first 만
val first = query.sort.first()
list.sortedBy { keySelector(first.property)(it) }

// ✓ 좋음 — 운영과 동일하게 fold/then 으로 합성
query.sort.map { comparatorFor(it.property, it.direction) }
    .reduce { acc, next -> acc.then(next) }
```

---

### 1️⃣9️⃣-B 문서 ↔ 실제 동작 정합 (Doc-Behavior Parity)

KDoc / `@DisplayName` / 주석은 **명세 문서** 다. 실제 동작과 어긋나면 *문서가 거짓말* 이 된다 — 호출자·리뷰어·후임이 잘못 신뢰한다.

세 영역으로 나누어 본다:

#### (1) KDoc / 주석의 약속 ↔ 실제 가드·구현

| 확인 | 위반 예 |
|---|---|
| **KDoc / 주석의 강한 약속(`차단한다` / `보장한다` / `방지한다` / `불가능`) 과 실제 가드 불일치** | "Int overflow 차단" 이라고 적혀 있는데 실제로는 size 상한만 있고 page 가드 없음 — 문서가 *거짓말*. 호출자/리뷰어가 잘못 신뢰 |
| **메서드 KDoc 의 *흐름 설명* (numbered steps / "1." "2." "3.") 과 실제 호출 순서·시그니처 불일치** | KDoc (2): "각 Property 의 RoomType 목록 조회 (`findAllByPropertyIds` 묶음)" 라고 batch 조회를 약속하는데, 실제 구현은 `for property → roomTypeRepository.findByPropertyId(property.id)` N+1. 문서는 batch 인 줄 알고 운영 부하 산정·후임 리팩토링 의사결정이 어긋남 |
| **KDoc 의 미완성 문장 / 끊긴 bullet** | "5. 가용 객실 중 *최저 합산가* 선택 — Property 단위 대표가" — `대표가` 뒤가 잘림. 의미 불완전 → 회귀 시 해당 단계의 *원래 의도* 를 후임이 추측 |
| **KDoc 의 "고정" / "비어있어야 함" 같은 강한 정책 약속이 실제 가드 부재** | "정렬은 wishedAt DESC 고정 (page.sort 비어있어야 함)" 이라고 했지만 Facade 가 `require(page.sort.isEmpty())` 가드 없이 그대로 Repository 로 전달 — Repository 단에서 막아도 Facade 계약이 모호해지고, 다른 호출자(테스트 / 다른 Facade) 가 신뢰할 기준이 흐려짐 |
| **KDoc 가 명시적 실패 (`NOT_FOUND` / `INTERNAL_ERROR`) 를 약속하지만, 구현은 `mapNotNull` / `?.let` 로 silent skip** | "누락된 Property 는 NOT_FOUND" 라고 적혀 있는데 `wishes.mapNotNull { properties[wish.propertyId]?.let { ... } }` 로 조용히 제외 — 운영에서 데이터 정합 깨짐을 *드러내지 않음* |
| **KDoc 의 트랜잭션·락·재시도 약속이 실제 어노테이션·코드와 불일치** | "낙관적 락" 이라고 했는데 `@Version` 없음 / "재시도 3회" 인데 `@Retryable` 없음 |
| **KDoc 가 가리키는 외부 문서(`docs/...`) 의 §번호가 실제 문서에 없거나 의미가 다름** | `docs/design/02-sequence-diagram.md §1` 인용했는데 §1 이 다른 시퀀스 / 문서가 갱신되지 않음 |

#### (2) `@DisplayName` ↔ 실제 검증 범위

| 확인 | 위반 예 |
|---|---|
| DisplayName 이 "공백이거나 100자 초과면" 인데 `@ValueSource` 는 공백만 | 100자 케이스 검증이 누락됐는지 알기 어려움 (Copilot 3차 지적) |
| DisplayName 이 "정상/실패 모두" 인데 정상만 검증 | 실패 회귀가 안 잡힘 |
| 한 `@Test` 안에서 두 가지 동작을 검증하는데 이름은 한 가지만 | given/when/then 분해 신호 |
| 한국어 자연어와 코드 동작이 시제·주체가 어긋남 (수동/능동, 거절/허용) | 명세 신뢰 저하 |
| **DisplayName 은 "INTERNAL_ERROR 로 거절" 이라고 명시했는데, 어설션이 `instanceof CoreException` 만 검증** | ErrorType 정책이 바뀌어도(BAD_REQUEST 로 변경 등) 테스트가 통과 — silent policy drift |
| **`assertThatThrownBy { ... }` 블록이 두 개 이상인데 각 블록의 어설션 강도가 다름** | 첫 블록은 `errorType` 까지, 두 번째는 `instanceof` 만 — 같은 실패 카테고리인데 회귀 가드 비대칭 |
| **DisplayName 이 "정렬" / "순서" / "DESC" / "ASC" / "최신순" / "오래된 순" 을 약속하는데 어설션이 `containsExactlyInAnyOrder` / `hasSize` / `containsAll` 로 순서 미검증** | "wishedAt DESC 순으로 반환" 인데 `containsExactlyInAnyOrder(first.id, second.id)` — 정렬이 깨져도 통과. fixedClock 으로 동률이라 어쩔 수 없다는 *주석* 이 있어도, 그러면 DisplayName 을 "조회 성공" 으로 완화하거나 seed 시각을 분리해 진짜 순서를 검증해야 함 — 둘 중 하나로 정합 |
| **DisplayName 이 "N건 반환" / "빈 리스트" / "단건" 처럼 cardinality 를 약속하는데 어설션이 `hasSize` / `isEmpty` / `hasSize(1)` 로 명시 검증 안 함** | 결과가 의도와 다른 size 여도 통과 |
| **DisplayName 이 "본인 자원만 조회" / "FORBIDDEN" 같은 인가 정책을 약속하는데 어설션이 ErrorType 까지 보지 않음** | 정책 회귀(FORBIDDEN → NOT_FOUND 등) 가 silent |
| **동시성 E2E 어설션이 `errorType` 만 검증, *catch 한 customMessage 일관성* 미검증** (§0-B CE-3 정합) | `ConcurrentXxxTest` 가 `assertThat(failures.errorType).isEqualTo(CONFLICT)` 만 보고 *서로 다른 메시지* (예: "이미 사용된 쿠폰입니다." vs "현재 상태(USED) 에서 USED 로 전이할 수 없습니다.") 가 응답으로 갈려도 통과. CE-1 (catch scope 정합) 의 회귀 가드 부재. **권고**: `failures.map { (it as CoreException).customMessage }.toSet().size <= 1` 또는 `expected: "이미 사용된 쿠폰입니다."` 로 단언 — 메시지 일관성 회귀 가드. |
| **동시성 E2E 어설션이 *commit 분포* 시나리오 미커버** | latch 동기화로 *분포 1 (snapshot 이전 시작)* 만 검증 — 분포 2 (snapshot 이후 시작) 의 도메인 throw 경로 미발동. CE-2 (분포 시나리오) 정합 깨짐. 별도 시나리오 (사전 commit + Thread.sleep) 추가 또는 *영구 한계 박제* 둘 중 하나. |

#### (3) 서로 다른 진실 원천(KDoc / DisplayName / `.github/instructions` / Repository 인터페이스 KDoc) 간 충돌

| 확인 | 위반 예 |
|---|---|
| Repository 인터페이스 KDoc 에는 "BAD_REQUEST 로 거절" 이 명시되어 있는데, 그 위 Facade KDoc 은 "고정" 만 적고 거절 동작은 침묵 | 호출자가 두 문서 중 어느 쪽을 믿어야 할지 불명확 — 위쪽(Facade) 도 동일 정책을 명시해야 정합 |
| 운영 RepositoryImpl KDoc 과 InMemory 더블 KDoc 의 정책 표현이 다름 | 한쪽만 읽은 후임이 잘못된 가정으로 변경 |
| `.github/instructions/*.md` 의 룰과 실제 KDoc 약속이 모순 | 두 진실 원천이 충돌 — 양쪽 동기화 필요 |

**가드**:
- KDoc 의 numbered flow 는 *실제 메서드 호출 / 시그니처* 와 1:1 로 대응한다. batch / N+1 / 트랜잭션 / 락 같은 *운영 의사결정에 영향* 가는 표현은 특히 정확해야 함. 표현 갭이 있으면 (a) KDoc 를 실제 동작에 맞추거나, (b) KDoc 의도대로 구현을 변경 — 둘 중 한 쪽으로 강제 정합.
- KDoc 의 *미완성 문장* 은 ktlint / Detekt 가 잡지 않는다 — 회귀 라운드에서 변경 파일의 KDoc 를 *전체 읽기* 해서 끊긴 bullet · "단어 뒤가" 같은 문장 종결 누락을 grep 한다.
- "고정" / "비어있어야 함" / "반드시" / "보장" 같은 정책 약속은 *Facade / 진입점* 에서 `require(...)` 또는 명시적 `throw` 가드로 박는다. Repository 가드만으로는 Facade 호출 계약이 모호해진다.
- KDoc 가 `NOT_FOUND` / `INTERNAL_ERROR` 같은 에러 정책을 약속하면 `mapNotNull` / `?.let` / `try { } catch { return ... }` 같은 silent skip 패턴은 위반.
- DisplayName 은 *실제로 검증되는 케이스만* 적는다. 추가 케이스는 별도 `@Test` 또는 `@ParameterizedTest` 로 분리하고 각각 자기 DisplayName 을 가진다.
- **DisplayName 에 ErrorType 명(`BAD_REQUEST` / `INTERNAL_ERROR` / `CONFLICT` / `UNAUTHORIZED` / `FORBIDDEN`) 이 들어가면, 어설션도 반드시 `extracting("errorType").isEqualTo(ErrorType.X)` 까지 본다.** instanceof 만으로는 정책 회귀가 안 잡힌다 (verify-tests 게이트의 명세성 점검과 한 쌍).
- **DisplayName 에 정렬·순서 단어가 들어가면 `containsExactly` / `isSortedAccordingTo(comparator)` / `extracting(...).containsExactly(...)` 로 순서를 검증한다.** seed 가 동률이면 동률이 *안 나오게* 분리하거나, DisplayName 을 "조회 성공" 으로 완화 — 둘 중 하나.
- 한 테스트 안에 `assertThatThrownBy` 블록이 여러 개라면 *모든 블록* 이 같은 어설션 강도를 가진다 — 한 쪽만 강하면 비대칭 회귀 사각지대.

**점검 명령** (KDoc 정합 일괄 검토):
```
Grep "차단|보장|방지|불가능|고정|비어있어야|반드시" path=apps/.../main glob="*.kt"
→ 각 약속의 위치를 보고 *같은 파일 / 같은 메서드 본문* 에 대응 가드가 있는지 대조

Grep "^\s*\*\s*\d+\.\s" path=apps/.../main glob="*.kt"
→ KDoc 의 numbered step 추출 후 메서드 본문의 호출 시그니처와 1:1 대조

Grep "containsExactlyInAnyOrder" path=apps/.../test glob="*Test.kt"
→ 같은 테스트의 @DisplayName 에 "정렬|순서|DESC|ASC|최신순" 단어가 있는지 교차
```

---

### 2️⃣0️⃣ 회귀 방지 / 테스트 시나리오 권고

각 항목에서 **위반이 발견되면**, 보완 코드와 함께 **회귀 방지 테스트** 도 권고한다.
verify-tests 게이트가 그 테스트를 강제 — 두 게이트는 한 쌍.

권고 예시:
- "갤러리에 없는 URL 으로 `replaceMainImage` 호출 시 BAD_REQUEST 거절 + 상태 불변" 테스트
- "신규 Property 에 자식 추가 후 저장/재조회 시 자식의 `property_id` 가 부모 id 와 동일" JPA round-trip 테스트
- "`displayOrder` 가 다른 이미지 2개 저장 후 재조회 시 ASC 순서" 테스트
- "`Converter.convertToDatabaseColumn(null)` 호출 시 INTERNAL_ERROR" 테스트
- "`RoomTypeModel.create(propertyId = 0)` 거절" 테스트
- "`@Transactional` self-invocation 검출" 통합 테스트
- "현재 시각 의존 로직의 `Clock` 고정 단위 테스트"
- "**다중 sort key** 적용 — 1차 키 동률일 때 2차 키로 정렬" 운영/InMemory 동치 테스트
- "**Converter 직렬화 측 실패** — `writeValueAsString` 가 던지는 케이스에서 `INTERNAL_ERROR` + cause 보존" 테스트

---

### 2️⃣1️⃣ 출력 포맷

```markdown
## verify-code 결과: {기능명}

### 컨텍스트
- 변경 파일: ...
- 신규 외부 라이브러리 / 호출: 있음/없음

### Null 일관성
- 위반: 0건 / N건 (위치 + 내용)

### 자식 Entity 식별자 동기화
- ...

### 외부 라이브러리 누출 (시그니처)
- ...

### 컬렉션 / 페이지 결정성
- ...

### 캐시 / 단일 진실 원천
- ...

### 입력 검증 / 불변식
- ...

### 멱등성 / 부수효과 일관성
- ...

### 사일런트 디폴트
- ...

### 동시성 / 스레드 안전성
- ...

### 자원 관리
- ...

### 시간 / Clock / Timezone
- ...

### 예외 처리 / 에러 전략
- ...

### 매직 상수 / 가독성
- ...

### 코드 중복 (DRY)
- ...

### 가시성 / 캡슐화
- ...

### 트랜잭션 / 영속성 함정
- ...

### 성능 함정 (조기 경보)
- ...

### 보안
- ...

### 도메인 어휘
- ...

### 회귀 방지 권고
- 1) ...
- 2) ...

### 냉정 모드 체크리스트 (§0-B) — 통과 어셔런스
- CE-1 (catch scope 정합): ✅ / ❌ — try 블록이 모든 throw 경로 감쌈
- CE-2 (분포 시나리오 추론): ✅ / ❌ — REPEATABLE_READ 이전/이후 분포 모두 검증
- CE-3 (어설션 강도): ✅ / ❌ — DisplayName 동사·정책 1:1 어설션 + customMessage 일관성
- CE-4 (3축 정합): ✅ / ❌ — DB / 도메인 메모리 / Facade 응답 모두 같은 사고 일관 처리
- CE-5 (3 회 더 의심): ✅ / ❌ — 어설션 사각지대 / catch 못 잡는 throw / KDoc 거짓말 재확인
- CE-6 (PR 박제 ↔ 코드 정합): ✅ / ❌ — pr.md / experiments-results.md 의 "미해결 위험" / "트레이드오프" 가 코드 본문과 일치

### 게이트 결정
- ✅ PASS — verify-architecture 진행 가능 (CE-1 ~ CE-6 모두 ✅)
- ❌ FAIL — 다음 항목 보완 필요 (우선순위 P0/P1/P2):
  - P0) ...
  - P1) ...
  - P2) ...
```

---

### 2️⃣2️⃣ 점검 우선순위 (P0/P1/P2)

분량이 많기 때문에 모든 항목을 동등하게 다루지 않는다. 변경 단위와 무관하게 우선 검토:

- **P0 (반드시 차단)**: §1 Null 일관성 / §2 자식 Entity 식별자 / §3 외부 라이브러리 누출 / §6 입력 검증 / §16 트랜잭션 / §18 보안
- **P1 (강한 권고)**: §5 캐시 SSOT / §7 멱등성 / §9 동시성 / §11 시간 / §12 예외 (특히 메시지에 외부 식별자 노출) / §15 가시성 / §16-A 외부 입력 매핑 (silent ignore 포함) / §17 성능 (Read-then-Write SELECT 중복 / `@Table(indexes=...)` PK 중복) / §19-B 문서-동작 정합 (KDoc 흐름 ↔ 구현, DisplayName ↔ 어설션, 미완성 문장)
- **P2 (권고)**: §4 결정성 (tie-breaker) / §8 사일런트 디폴트 / §10 자원 / §13 매직 상수 / §14 DRY / §19 어휘

P0 위반 1건도 FAIL. P1 / P2 는 누적 정도와 영향 범위로 판단.

**P2 보류 결정의 회귀 가드** — 회귀 모드(§0-A) 에서 P2 로 분류해 보류한 항목 중 다음 패턴은 **외부 리뷰가 P1 이상으로 잡는다**. P2 처리 전에 한 번 더 의심한다:

1. **예외 메시지의 외부 식별자 (LoginId / email / userId / token)** — 보안 / 응답 일관성 P1. 메시지 일반화 + 식별자는 로그로.
2. **Read-then-Write 의 SELECT 중복** (`existsBy → delete`, `findById → save` 등) — 운영 부하 P1. 단일 쿼리로 통합.
3. **외부 입력의 silent ignore** (`PageQuery.sort` 무시 등) — 호출자 오용 가림 P1. 거절 또는 화이트리스트.
4. **운영-테스트 더블 정책 비대칭** — 둘이 다르게 동작하면 회귀 사각지대 P1, 같은 결함도 양쪽에서 동시 fix.
5. **JPA 컬럼 중복 매핑** (`@Embedded VO @Column(name="X")` + 외부 entity 의 `@Column(name="X")`) — 단위 테스트는 통과하지만 풀 컨텍스트 / `@DataJpaTest` 시점에 폭발 P0. 단위 테스트만으로는 발견 불가하므로 `@Column(name=...)` 횡단 grep 으로 entity 별 충돌 사전 감지.
6. **부수효과 이전 cheap input guard 누락** — 외부 자원 mutation (`reserveOne()` / `releaseOne()` / `save()`) 이전에 `> 0` / `not blank` 같은 비싸지 않은 검증을 끝내지 않으면 부분 변경 시점이 발생 P1. Strong Exception Safety 의 service-level 강화.
7. **외부 주입 컬렉션 ↔ Aggregate 식별자 가드 부재** (`cancel(reservation, inventories)` 등) — 호출자 실수로 다른 자원을 mutate 하는 정합성 오류 P1. mutate 이전 가드 + 단위 테스트로 회귀 방지.
8. **`set(A) == set(B)` 단독 비교의 중복 사각지대** — list 의 1:1 매칭이 본질이면 `size + set` 페어 P1.
9. **다일자 (다중 row) 비관적 락 의 `ORDER BY` 미명시** — 락 획득 순서 비결정 → 데드락 P1. SQL `ORDER BY date ASC` + Facade 진입점 `.sorted()` 양쪽 명시.
10. **JPA 예외 (`OptimisticLockingFailureException` / `DataIntegrityViolationException`) 가 클라이언트 응답으로 노출** — 식별자 노출 + 도메인 의미 부재 P1. Facade try/catch 로 `CoreException(CONFLICT, ..., cause = e)` 변환.
11. **`@Lock(PESSIMISTIC_WRITE)` / `saveAndFlush` 가 `@Transactional` 밖에서 호출** — 락 즉시 해제 / 호출 시점 throw 의미 깨짐 P1. Facade `@Transactional` 안에서만 호출 + Repository 인터페이스 KDoc 박제.
12. **Facade try/catch 의 catch scope 가 *모든 throw 경로* 를 감싸지 않음** (§0-B CE-1 / §12 신규 룰) — `model.someMethod()` 호출이 try 밖이고 *자체 도메인 검증 throw* 가 catch 안 잡혀 메시지 일관성 깨짐 P1. 같은 도메인 사고가 REPEATABLE_READ 분포에 따라 *2 흐름* (JPA 예외 / 도메인 throw) 으로 갈려도 *동일 메시지* 응답이 강제. try 블록을 *모든 throw 위치* 까지 확장 + `CoreException catch 의 errorType 별 메시지 정규화*. **회귀 가드는 동시성 E2E 의 customMessage 일관성 어설션** (§19-B CE-3).
13. **동시성 E2E 어설션이 *상태 정합 (성공 카운트 / DB 값)* 만 보고 *메시지 일관성* 미검증** (§0-B CE-3) — CE-1 / 12번의 회귀 가드 부재 P1. `failures.map { customMessage }.toSet().size <= 1` 추가.

위 13가지를 P2 로 보류하려면 **명시적 사유** ("4주차 동시성 영역", "별도 인프라 라운드" 같은 *일반화 가능한 이유*) 가 PR 본문에 박혀야 한다. "본 PR scope 외" 같은 모호한 사유로 미루면 다음 라운드에 외부 리뷰로 다시 잡힘.

---

### 2️⃣3️⃣ 톤 & 원칙

- **기본은 검증자 모드 + 냉정 모드 (§0-B)** — 코드를 직접 수정하지 않는다. 위반을 적시하고 어떻게 고칠지는 개발자가 결정한다. 냉정 모드는 *항상 활성* — 표면 grep 으로 끝내지 않고 race window / 분포 시나리오 / 어설션 강도 / 다중 throw 경로까지 추론한다.
- **표면 점검 금지** — "어설션이 통과한다 = 결함 없음" 의 단언 X. *어설션이 검증하지 않는 사각지대* 가 본질적 위험. 동시성 / 트랜잭션 / 예외 흐름 영역에서는 §0-B CE-1 ~ CE-6 6 항목을 *명시적으로* 통과시킨다.
- **회귀 모드(§0-A) 진입 시에만 수정자 모드** — 사용자가 명시한 경우에 한해 fix 까지 직접 적용하고 자체 회귀 루프를 돈다. defect-zero 도달 후 *반드시* 멈추고 작업 트리 상태를 보고한다. 커밋·푸시는 자동 진행 금지.
- 100% 순수성을 강요하지 않는다. 이탈은 **이유와 함께** 명시되어야 PASS.
- FAIL 시 **회귀 방지 테스트도 함께** 권고한다 — verify-tests 게이트가 받는다.
- "지적이 많아 보일 때" 는 우선순위로 압축한다 — P0 부터 해소되면 P1/P2 는 후속 PR.
- **사용자가 "더 냉정하게 / 꼼꼼하게 / 더 깊게" 를 명시하면** Round 0 (§0-A) 부터 PR 전체 scope 재검토 + §0-B 6 체크리스트 *명시적 라벨링* 으로 답한다. 이전 라운드의 PASS 결론을 *복붙* 하지 않는다 — 같은 영역도 *분포 / scope / catch boundary* 차원에서 다시 본다.
- 한국어 응답을 기본으로 한다.
- 본 스킬이 FAIL 이면 verify-architecture 로 넘어가지 않는다 — 경계가 멀쩡해도 본문에 사일런트 사고가 있으면 의미 없다.
