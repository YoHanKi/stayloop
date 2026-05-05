---
name: verify-tests
description: |
  Stayloop 기능 구현 직후 테스트의 충분성과 정합성을 점검하는 게이트.
  단위/통합/E2E 테스트 피라미드 충족 여부, 테스트 가능 구조, 테스트 더블의 역할 적합성,
  명세 표현(@DisplayName, given/when/then), 실패·경계 시나리오 누락, 그리고
  ./gradlew test / ktlintCheck 통과 여부를 확인한다.
  새로운 코드를 생성하거나 리팩토링하지 않으며, 테스트의 누락·과잉·잘못된 더블 사용을
  드러내고 보완 선택지를 제시하는 데 집중한다. CLAUDE.md 정책에 따라 기능 구현 직후 반드시 호출된다.
user-invocable: true
---

Stayloop 의 모든 기능 구현(신규/수정 무관)은 이 스킬의 체크리스트를 통과해야 종료된 것으로 간주한다.
이 스킬은 **검증자의 관점**으로 동작하며, 테스트 코드를 새로 짜주는 것이 아니라 **누락·과잉·오용을 식별**한다.

---

### 0️⃣ 컨텍스트 수집

검증을 시작하기 전 다음을 명시적으로 확인한다. 추측하지 말고, 모르는 부분은 개발자에게 한 번에 묻는다.

- 이번에 구현된 기능의 단위는 무엇인가? (유스케이스 / 도메인 규칙 / 컨트롤러 단)
- 영향 받은 레이어는? (`domain` / `application` / `infrastructure` / `interfaces.api`)
- 외부 의존성(JPA / Redis / Kafka / 외부 API)이 추가/수정되었는가?
- 신규 도메인 규칙(불변식, 검증 규칙, 상태 전이)이 도입되었는가?

> 출력: 검증 대상 파일과 레이어를 한 줄로 요약한 뒤 본격적인 점검을 시작한다.

---

### 1️⃣ 테스트 피라미드 커버리지 점검

레이어별로 **반드시 있어야 할 테스트가 빠지지 않았는지**를 확인한다.
규모가 아니라 "이 변경에 대해 어떤 종류의 신뢰를 주는가"가 기준이다.

| 레이어 | 필요 테스트 | 위치 / 도구 |
|---|---|---|
| `domain` | 단위 테스트 — 값 객체 검증, 불변식, 상태 전이 | 순수 JUnit5 + AssertJ, Spring 미사용 |
| `application` | 통합 테스트 — 유스케이스 흐름, 의존성은 Fake/Mock | `@SpringBootTest` 또는 컨스트럭터 직접 주입 |
| `interfaces.api` | E2E 테스트 — HTTP 요청/응답, 검증 실패 케이스 | `@SpringBootTest` + `MockMvc` |
| `infrastructure` | 통합 테스트 — 실제 DB/Redis 동작 (필요 시) | Testcontainers + `@DataJpaTest` 등 |

빠진 레이어가 있다면 **왜 빠졌는지 정당화**를 요구한다 (예: "도메인 규칙이 없는 단순 위임 컨트롤러"). 정당화가 없으면 누락으로 표시한다.

---

### 2️⃣ 테스트 가능한 구조인지 검토

CLAUDE.md / week1.md 의 "테스트 가능한 구조" 기준에 따른다. 다음을 확인한다.

- 외부 의존성이 **인터페이스 + 생성자 주입**으로 분리되어 있는가? (`new` 직접 호출 금지)
- 한 함수가 **단일 책임**인가? 테스트 실패 시 원인이 명확히 좁혀지는가?
- 도메인 규칙이 도메인 객체로 위임되어 있는가? (`user.changePassword(...)`, `inventory.decreaseOne()`)
- private/static 로직이 검증 사각지대를 만들고 있지 않은가?

구조 자체가 테스트 불가능하면, 테스트 보완보다 **구조 개선이 선행되어야 한다**고 명시한다.

---

### 3️⃣ 테스트 더블 사용 적합성

`Stub / Mock / Spy / Fake / Dummy` 가 역할에 맞게 사용되었는지 점검한다.

- **상태 검증 vs 행위 검증의 혼동 여부**
  - 단순 응답이 필요한 위치에 `verify(...)` 가 남용되어 있는가? → Stub 으로 충분한 케이스
  - 부수효과 호출 검증이 빠져 있는가? → Mock(`verify`) 누락
- **Mock 의 과잉 사용**
  - 도메인 객체(엔티티, 값 객체)를 mock 하고 있지 않은가? → 도메인 객체는 진짜 객체로 사용
  - 한 테스트에서 mock 이 4개 이상이면 **유스케이스 분해 부족** 신호로 본다
- **Fake 활용 여부**
  - 반복 사용되는 InMemory 저장소가 매 테스트마다 즉석 mock 으로 대체되어 있다면 Fake 로 추출 권장
- **Spy 사용 시 진짜 객체 호출 의존이 합리적인가**

도구(`mock()`, `spy()`)와 역할(Stub/Mock/Spy/Fake/Dummy)을 분리해 보고한다.

---

### 4️⃣ 테스트 명세성 점검

테스트 코드는 **요구사항의 명세**다. 읽었을 때 의도가 드러나야 한다.

- `@DisplayName` 이 한국어로 명확히 의도를 서술하는가? 기존 컨벤션과 일치하는가?
  (예시: `"ErrorType 기반의 예외 생성 시, 별도의 메시지가 주어지지 않으면 ErrorType의 메시지를 사용한다."`)
- 테스트 본문이 `// arrange / act / assert` 또는 `given/when/then` 으로 구분되어 있는가?
- 함수명이 결과 + 조건을 표현하는가? (`messageShouldBeErrorTypeMessage_whenCustomMessageIsNull` 형식)
- 한 테스트는 **하나의 동작**만 검증하는가? assert 가 흩어져 있지 않은가?

위 4가지 중 하나라도 어긋나면 보완 대상으로 표시한다.

---

### 4️⃣-A 테스트명 ↔ 실제 동작 정합 (Name-Behavior Parity)

`@DisplayName` / 함수명은 테스트의 **계약**이다. 자연어로 약속한 동작과 어설션이 검증하는 동작이 *같은 것* 이어야 한다. 어긋나면 회귀 가드가 *침묵* 하고, 명세를 신뢰한 후임이 잘못된 가정으로 변경한다 (verify-code §19-B 와 한 쌍).

| 확인 | 위반 예 |
|---|---|
| **DisplayName 이 "정렬" / "순서" / "DESC" / "ASC" / "최신순" / "오래된 순" 을 약속하는데 어설션이 `containsExactlyInAnyOrder` / `hasSize` / `containsAll` 로 순서 미검증** | "wishedAt DESC 순으로 반환" 인데 `containsExactlyInAnyOrder(first.id, second.id)` — 정렬이 깨져도 통과. fixedClock 으로 동률이라 어쩔 수 없다는 *주석* 이 있어도, 그러면 DisplayName 을 "조회 성공" 으로 완화하거나 seed 시각을 분리해 진짜 순서를 검증해야 함 — 둘 중 하나로 정합 |
| **DisplayName 이 cardinality 를 약속 ("N건 반환" / "빈 리스트" / "단건 / 중복 없음") 인데 어설션이 명시 검증 없음** | "두 건만 반환" 인데 `assertThat(result).isNotEmpty()` 로 끝 — 3건/0건이어도 통과 |
| **DisplayName 이 ErrorType / 인가 정책 ("BAD_REQUEST" / "FORBIDDEN" / "본인 자원만") 을 약속하는데 어설션이 `instanceof CoreException` 만 검증** | ErrorType 정책 회귀(FORBIDDEN → NOT_FOUND 등) 가 silent. `extracting("errorType").isEqualTo(ErrorType.X)` 까지 봐야 정책 가드 |
| **DisplayName 이 부수효과 ("저장된다" / "삭제된다" / "1 증가" / "1 감소") 를 약속하는데 어설션이 반환값만 검증** | "wishCount 가 1 증가" 인데 `info.wishCount == 1` 만 보고 `properties.findById(id).wishCount` 미확인 → 캐시 SSOT 깨져도 통과 |
| **DisplayName 이 "멱등" / "두 번 호출해도" 를 약속하는데 실제 호출이 1회** | 멱등 회귀가 안 잡힘 |
| **DisplayName 이 상태 전이 ("취소된다" / "확정된다") 를 약속하는데 상태 어설션 없음** | 반환값만 보고 entity 상태 미검증 |
| **DisplayName 이 부정형 ("거절한다" / "변경되지 않는다" / "없으면 noop") 인데 *변경되지 않은 측의 상태* 를 어설션 안 함** | "예외 시 부분 변경 없음" 인데 예외만 검증하고 상태 불변 미검증 — Strong Exception Safety 회귀 사각지대 |
| **`assertThatThrownBy { ... }` 블록이 두 개 이상인데 각 블록의 어설션 강도가 다름** | 첫 블록은 `errorType` 까지, 두 번째는 `instanceof` 만 — 같은 실패 카테고리인데 회귀 가드 비대칭 |
| **DisplayName 에 *복수 케이스* 가 묶여 있는데 (`@ValueSource` / `@CsvSource`) 일부 케이스만 들어있음** | "공백 / 100자 초과 거절" 인데 `@ValueSource(strings = [" "])` 만 — 100자 케이스 누락이 안 보임 |
| **함수명(camelCase) 과 DisplayName(한국어) 이 서로 다른 동작을 묘사** | `shouldRejectAnotherUserAccess` + `"FORBIDDEN 으로 거절한다"` 는 정합. 그러나 함수명은 "Reject" 인데 DisplayName 은 "noop 으로 통과" 면 의도 불명 |

**가드**:
- DisplayName 의 *모든* 동사·정책·cardinality 가 어설션으로 1:1 검증되는지 본다 — 자연어 단어 → 어설션 매핑이 가능해야 한다.
- 정렬·순서를 약속하면 `containsExactly(...)` / `extracting(...).containsExactly(...)` / `isSortedAccordingTo(comparator)` 사용. seed 가 동률이면 동률이 *안 나오게* (offset Clock / Repository 직접 호출로 다른 timestamp seed) 분리하거나, DisplayName 을 "조회 성공" 으로 완화 — 둘 중 하나.
- ErrorType 명이 DisplayName 에 들어가면 `extracting("errorType").isEqualTo(ErrorType.X)` 까지 봐야 한다 (verify-code §19-B 와 동일 기준).
- 부수효과 (저장 / 삭제 / 증가 / 감소 / 상태 전이) 를 약속하면 *반환값* + *영속 상태* 둘 다 어설션. 캐시 컬럼 (`wishCount`, `mainImageUrl`) 도 별도 어설션 — SSOT 회귀 사각지대.
- "변경되지 않는다" / "noop" / "거절" 같은 부정형 약속은 *변경되지 않은 측의 상태* 를 어설션. `assertThat(properties.findById(id).wishCount).isEqualTo(0)` 처럼.
- 복수 케이스는 `@ParameterizedTest` 의 `@ValueSource` / `@CsvSource` 가 *DisplayName 에 적힌 모든 케이스* 를 덮는지 본다. `@ParameterizedTest(name = "...")` 의 인자 표시도 활용.

**점검 명령** (회귀 라운드 / Round N):
```
Grep "containsExactlyInAnyOrder|isNotEmpty|hasSize\(\)|containsAll" path=apps/.../test glob="*Test.kt"
→ 같은 메서드의 @DisplayName 에 "정렬|순서|DESC|ASC|최신순|N건|개수" 단어가 있는지 교차 검증

Grep "assertThatThrownBy" path=apps/.../test glob="*Test.kt"
→ 같은 메서드의 @DisplayName 에 ErrorType 명이 있는지, 어설션이 errorType 까지 보는지 확인

Grep "@DisplayName.*\"" path=apps/.../test glob="*Test.kt"
→ DisplayName 의 동사·정책·cardinality 단어를 추출 후 본문 어설션과 1:1 매핑 가능한지 점검
```

---

### 5️⃣ 실패·경계 시나리오 누락 점검

성공 경로만 있는 테스트는 신뢰를 주지 않는다. 다음 카테고리를 **각 기능마다** 점검한다.

- **포맷/검증 실패**: 잘못된 이메일, 휴대폰, 비밀번호, 생년월일
- **중복/충돌**: 이미 가입된 로그인 ID, 동일 비밀번호로의 변경 등
- **인증 실패**: 잘못된 비밀번호, 존재하지 않는 사용자
- **경계값**: 비밀번호 길이 7/8/16/17, 생년월일 포함 여부, 빈 문자열, 공백
- **마스킹 규칙**: 마지막 글자 / 가운데 자리 / 한 글자 이름 등
- **상태 전이 실패**: 허용되지 않은 상태 변경
- **외부 의존 실패**: DB 충돌, Redis 미응답 (해당 기능에 한정)

각 카테고리에서 누락된 케이스를 **한 줄씩 나열**해 개발자가 즉시 보완할 수 있도록 한다.

---

### 6️⃣ Gradle 검증 강제 실행

다음을 **이 스킬 안에서 직접 실행**해 결과를 보고한다. 실패 시 게이트는 통과하지 않는다.

```bash
./gradlew ktlintCheck
./gradlew test
```

- ktlint 실패 → 무엇이 / 어디서 어긋났는지 단순 요약
- 테스트 실패 → 실패한 테스트 이름과 가능한 한 짧은 원인 추정
- jacoco 가 활성화되어 있으므로, 도메인 모듈의 커버리지가 비정상적으로 낮으면 함께 지적

> 참고: 통합/E2E 테스트가 `@SpringBootTest` 로 무거우면, 단위 테스트만 따로 빠르게 돌리는 옵션을 안내한다. 기본 정책은 전체 테스트 실행이다.

---

### 7️⃣ 출력 포맷

```markdown
## verify-tests 결과: {기능명}

### 컨텍스트
- 변경 레이어: ...
- 외부 의존성 변경: 있음/없음

### 테스트 피라미드
- [ ] 단위 (domain) — 누락/충분
- [ ] 통합 (application) — 누락/충분
- [ ] E2E (interfaces.api) — 누락/충분
- [ ] 인프라 (infrastructure, 필요 시) — 누락/충분/해당없음

### 구조 가능성
- ...

### 테스트 더블
- ...

### 명세성
- ...

### 누락된 실패 시나리오
- ...

### Gradle 검증
- ktlintCheck: PASS / FAIL (요약)
- test:        PASS / FAIL (실패 테스트 목록)

### 게이트 결정
- ✅ PASS — 머지/다음 단계 진행 가능
- ❌ FAIL — 다음 항목 보완 필요: 1) ... 2) ...
```

---

### 8️⃣ 톤 & 원칙

- **테스트를 새로 짜주지 않는다.** 누락된 케이스를 적시하고, 어떻게 보완할지는 개발자가 결정한다.
- 통과시키는 것 자체가 목적이 아니다. **이 테스트 스위트가 이 기능을 신뢰할 만하게 만들었는가**가 질문이다.
- 100% 커버리지를 강요하지 않는다. 단순 위임/DTO 변환 등은 이유와 함께 제외 가능하다.
- 게이트가 FAIL 일 경우, 보완 우선순위를 1·2·3 으로 분명히 제시한다.
- 한국어 응답을 기본으로 한다 (프로젝트 컨벤션과 일치).