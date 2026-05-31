---
name: create-pr
description:
  Stayloop 의 PR 생성을 일관된 컨벤션으로 보조하는 **수동 스킬**. 사용자가 "PR 만들어줘" 등으로
  명시적으로 요청할 때만 호출되며, 자동 게이트로 사용하지 않는다. 브랜치 명명, 변경 단위 정리,
  documents/feature/{topic}/pr.md 작성(첫 줄 PR title 포함), 커밋 메시지 prefix
  (`feat`/`fix`/`refactor`/`migration`/`docs`/`chore`/`skills`), push 및 PR URL 산출을 일관성
  있게 진행한다. 직접 코드를 수정하거나 새 기능을 만들지 않는다. 호출 전 `verify-architecture` /
  `verify-tests` 가 PASS 되어 있는지 확인한다.
user-invocable: true
---

PR 은 **변경된 코드의 외부 표현**이다. 이 스킬은 누락 없이 일관된 형식의 PR 을 만들도록 보조하며, 코드를 직접 작성하지 않는다.

---

### 0️⃣ 사전 게이트 확인

다음이 모두 통과되어 있지 않으면 PR 생성에 들어가지 않는다.

- `verify-architecture`: ✅ PASS
- `verify-tests`: ✅ PASS
- `git status`: 작업 트리 클린 (`pr.md` 작성 단계에서만 새 파일 추가 허용)

하나라도 미충족이면 사용자에게 사유를 묻고, 해당 게이트를 먼저 호출하도록 안내한다.

---

### 1️⃣ 브랜치 컨벤션

브랜치 이름은 변경의 성격을 prefix 로 드러낸다.

| Prefix | 용도 | 예시 |
|---|---|---|
| `feature/` | 신규 기능 | `feature/reservation` |
| `fix/` | 버그 수정 | `fix/password-policy` |
| `refactor/` | 동작 보존 리팩토링 | `refactor/user-vo` |
| `migration/` | 구조/스키마 마이그레이션 | `migration/user` |
| `document/` | 문서 단독 PR | `document/user-architecture` |
| `chore/` | 인프라·빌드·메타 | `chore/gradle-bump` |
| `skills/` | 프로젝트 전용 스킬 | `skills/verify-architecture` |

확인 항목:

- 현재 브랜치가 `main` 이면 즉시 새 브랜치로 분기 (main 직접 푸시 금지)
- 현재 브랜치가 위 prefix 중 하나에 해당하지 않으면 사용자에게 변경 의도를 물어 prefix 결정
- base 브랜치는 기본 `main`. 다른 long-lived 브랜치를 base 로 삼는 경우 사용자에게 명시 확인

---

### 2️⃣ 커밋 메시지 컨벤션

기존 git log 의 패턴을 따른다.

```
<prefix> : <한 줄 요약 (한국어, 마침표).>
```

| Prefix | 의미 |
|---|---|
| `feat` | 신규 기능 (외부 동작 변화) |
| `fix` | 버그 수정 |
| `refactor` | 동작 보존 변경 |
| `migration` | 구조 마이그레이션 (코드 이동/이름 변경 포함) |
| `docs` | 문서만 변경 |
| `chore` | 빌드·툴·메타 |
| `skills` | `.claude/skills/` 아래 변경 |

예: `migration : 유저 아키텍처 마이그레이션.`, `docs : 유저 아키텍처 마이그레이션 PR 문서 추가.`

여러 논리적 변경이 한 PR 에 섞여 있다면 커밋을 분리하도록 권장한다 (강제하지 않음).

**PR scope 규율 (실무 — small PR).** 한 PR 이 *서로 독립된 주제 여러 개* 를 담으면 (예: 4개 도메인의 동시성 전략을 한 번에) PR 을 *쪼개도록 권한다*. 리뷰어 1명이 한 호흡에 읽고 머지를 판단할 수 있는 크기가 좋은 PR 이다. "핵심 결정 1~2개로 압축이 안 되고 자꾸 늘어난다" = PR 을 나눠야 한다는 신호. pr-lint 의 결정-개수 경고가 이 신호를 기계로 보조한다.

---

### 3️⃣ pr.md 작성

위치: `documents/feature/{topic}/pr.md`. `{topic}` 은 브랜치명의 후행 부분과 일치시킨다 (예: `document/user-architecture` → `documents/feature/user-architecture/pr.md`).

**SSOT 는 `.github/pull_request_template.md`.** pr.md 의 섹션 구성·순서·주석 가이드는 그 템플릿을 그대로 따른다. 이 스킬은 템플릿을 *중복 정의하지 않고*, 각 칸을 *어떻게 채우는지* 의 원칙만 박는다. 템플릿이 바뀌면 pr.md 도 따라간다.

**기계적 게이트** — `.github/workflows/pr-lint.yml` 가 PR 본문을 검사한다. 권고가 아니라 강제:
- 제목이 `<prefix>: 요약` 형식 (scope 선택, 위반 시 fail).
- `## TL;DR` + `## 검증` 섹션 *존재 + 알맹이* (빈 깡통 fail).
- 본문 200줄 초과(증거 `<details>` 제외) / 핵심 결정 3개 초과 시 *경고* — PR 을 쪼개거나 정제하라는 신호. block 은 아님.

**파일 첫 줄은 PR title 한 줄.** GitHub PR 생성 시 그대로 제목으로 사용한다 (`gh pr create --title "$(head -n1 pr.md | sed 's/^# //')"`). 형식:

```
# <prefix>: <한 줄 요약 (한국어, 마침표 없이, 70자 이내)>
```

`<prefix>` 는 커밋 메시지 prefix 와 동일한 어휘(`feat` / `fix` / `refactor` / `migration` / `docs` / `chore` / `skills`). 변경의 *대표* 성격을 따른다.

**제목 — 실무 관용 (Google eng-practices / Conventional Commits):** 명령형 한 줄. "추가했다" 가 아니라 "추가". "Fix bug" / "Phase 1" 같은 *맥락 없는 제목 금지* — history 한 줄로 섰을 때 의미가 서야 한다.

> **PR 제목 ≠ 커밋 메시지 형식.** 의도적으로 다르다 — 커밋(§2)은 `<prefix> : 요약.` (콜론 앞 공백 + 마침표), PR 제목은 `<prefix>: 요약` (콜론 앞 공백 없음 + 마침표 없음). 커밋 메시지를 *그대로 복사해 제목으로 쓰지 말 것* (pr-lint 가 공백/마침표를 잡아낸다).

예:
- `# migration: 유저 도메인 아키텍처 마이그레이션`
- `# skills: PR 생성 스킬(create-pr) 추가`
- `# feat: 예약 도메인 회원-숙소 매칭 추가`

**섹션별 채우기 원칙** (템플릿 순서대로):

| 섹션 | 채우는 법 |
|---|---|
| **TL;DR** | 한두 줄. 목적 + 결과만. 4줄 넘으면 `배경·왜` / `변경 요약` 으로 내린다. |
| **배경·왜** | 실무 PR 의 1순위 — "왜" 가 "무엇" 보다 중요하다. 관련 week-quest / plan PR / 이슈 *링크*. **로컬·gitignore 문서(decision.md 등) 는 링크만 걸지 말고 결론 한 줄을 본문에 옮긴다** — 리뷰어는 그 파일을 못 본다. |
| **변경 요약** | 파일/타입 단위 1줄 또는 전→후 표. *효과* 위주 — diff 재진술 금지. |
| **핵심 설계·결정** | 치열했던 결정 *1~2개만*. 갈림길 한두 줄 → 대안 (A/B/C) → 선택 + 사유. 사소한 고민은 빼고 깊은 토론은 decision 문서로. 구조가 글보다 그림이 빠르면 mermaid 한 장. |
| **영향도·위험** | 배포·운영 관점 — 마이그레이션/롤백, 하위 호환성 파괴, 배포 주의(toggle/순서/캐시). 받아들인 트레이드오프 + "왜 괜찮은가" 한 줄. 해당 항목만 남긴다. |
| **검증·재현** | gradle 결과 + **리뷰어가 직접 확인하는 법**. 측정 증거(EXPLAIN / k6 / curl)는 *정제 수치* + `<details>` — 백엔드의 "스크린샷" 대체물. |
| **리뷰 포인트** | 시니어가 먼저 볼 1~3곳. 의심스러운 코드·관용 이탈 우선. |
| **후속** | 범위 밖이지만 인지하고 미룬 것. |
| **셀프 체크** | 게이트 PASS / WHY 위주 / N+1·쿼리·민감정보 로깅 / 빈 섹션 삭제. |

작성 원칙 (실무 관용 + 본 프로젝트 결):

- **WHY > WHAT**. diff 가 알려주는 것은 다시 적지 않는다.
- **자기완결**. PR 본문만 보고 이해돼야 한다. 로컬·gitignore 산출물에 결론을 미루지 않는다 (결론은 옮겨 적고 출처만 덧붙인다).
- **설득이지 기록이 아니다 — 분량 규율**. PR 은 머지를 위해 리뷰어를 *설득* 하는 글이지 학습 일지가 아니다.
  - `핵심 설계·결정` 은 *치열했던 1~2개* 만. 모든 고민을 나열하지 않는다 (6개 늘어놓지 말 것 — 나머지는 decision 문서로).
  - 측정 증거는 *정제 수치* 만. raw 로그/전체 쿼리 플랜을 본문에 붙이지 않는다 ("P95 200ms → 50ms" 한 줄 + 상세는 `<details>`).
- **간결·스캔 가능**. 내부 약어(박제 / phase code / D-N)를 *그대로* 노출하지 말고 리뷰어가 읽을 언어로 푼다.
- 보안/성능/동시성처럼 리뷰어가 놓치기 쉬운 함정은 별도 강조.
- **빈 섹션은 통째로 삭제**. 빈칸을 다 채울 의무는 없다.
- 마침표·문체는 한국어 평문체.

---

### 4️⃣ Push & PR URL 산출

```
git push -u origin <branch>
```

- 인증 실패: Windows 측 자격증명 매니저가 필요할 수 있으므로 PowerShell 환경에서 재시도.
- 푸시 성공 후, GitHub Compare URL 을 생성해 사용자에게 전달:

```
https://github.com/{owner}/{repo}/compare/{base}...{head}?expand=1
```

`gh` CLI 가 있으면:

```
gh pr create --base {base} --head {head} --title "<커밋 메시지 1줄>" --body-file documents/feature/{topic}/pr.md
```

`gh` 가 없으면 Compare URL 만 제공하고 사용자가 브라우저에서 제출하도록 안내. **본문은 pr.md 의 내용을 그대로 사용**하도록 한다 (이중 작성 금지).

---

### 5️⃣ 출력 포맷

```markdown
## create-pr 결과: {기능명}

### 사전 게이트
- verify-architecture: ✅ / ❌
- verify-tests:        ✅ / ❌

### 브랜치
- base: main
- head: {prefix}/{topic}
- 신규 푸시: ✅ / 기존 동기화

### 커밋
- {short-sha} {prefix} : {요약}

### 문서
- documents/feature/{topic}/pr.md ({lines}줄)

### PR
- Compare URL: https://github.com/{owner}/{repo}/compare/main...{head}?expand=1
- (gh 사용 시) PR URL: ...

### 다음 액션
- 사용자가 Compare URL 에서 제목 확인 후 "Create pull request" 클릭
- 리뷰어 지정 / 라벨 / 마일스톤 부착
```

---

### 6️⃣ 톤 & 원칙

- **PR 본문(pr.md)을 새로 짜주지 않는다.** 골격을 안내하고 비어 있는 항목을 지적한다.
- **푸시·PR 생성은 반드시 사용자에게 1회 확인 후 진행** (외부에 노출되는 작업).
- main 직접 푸시·force push 는 절대 시도하지 않는다.
- 한국어 응답 기본.
