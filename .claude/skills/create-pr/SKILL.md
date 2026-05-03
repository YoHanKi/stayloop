---
name: create-pr
description: |
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

---

### 3️⃣ pr.md 작성

위치: `documents/feature/{topic}/pr.md`. `{topic}` 은 브랜치명의 후행 부분과 일치시킨다 (예: `document/user-architecture` → `documents/feature/user-architecture/pr.md`).

**파일 첫 줄은 PR title 한 줄.** GitHub PR 생성 시 그대로 제목으로 사용한다 (`gh pr create --title "$(head -n1 pr.md | sed 's/^# //')"`). 형식:

```
# <prefix>: <한 줄 요약 (한국어, 마침표 없이, 70자 이내)>
```

`<prefix>` 는 커밋 메시지 prefix 와 동일한 어휘(`feat` / `fix` / `refactor` / `migration` / `docs` / `chore` / `skills`). 변경의 *대표* 성격을 따른다.

예:
- `# migration: 유저 도메인 아키텍처 마이그레이션`
- `# skills: PR 생성 스킬(create-pr) 추가`
- `# feat: 예약 도메인 회원-숙소 매칭 추가`

본문 섹션은 그 다음 줄부터 시작한다 (existing `documents/feature/user/pr.md` 패턴 준수):

1. **TL;DR** — 변경의 본질을 4~6 줄 산문으로
2. **무엇을 바꿨나** — 이전 vs 이후 표 또는 요구사항 → 구현 표
3. **핵심 흐름 한눈에** — Mermaid sequence/flow 다이어그램 (필요 시)
4. **고민과 선택** — 의사결정 1개당: 대안 (A)/(B)/(C) 나열 → 선택 → 이유 → 부수 결정
5. **트레이드오프 정리** — 받아들인 비용을 짧게 bullet
6. **리뷰 포인트** — 리뷰어가 먼저 봐야 할 파일/규칙
7. **게이트 결과** — `verify-architecture` / `verify-tests` 의 PASS/FAIL

작성 원칙:

- **WHAT 보다 WHY**. diff 가 알려주는 것은 다시 적지 않는다.
- 대안을 비교한 흔적을 남긴다 — "이 코드는 (A) 가 아닌 이유" 가 PR 본문에 있다.
- 보안/성능/동시성처럼 리뷰어가 놓치기 쉬운 함정이 있으면 별도 항목으로 강조.
- 마침표·문체는 기존 pr.md 와 맞춘다 (한국어, 평문체).

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
