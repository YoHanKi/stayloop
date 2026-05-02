---
applyTo: "**/*Service*.kt,**/*Service*.java,**/application/**/*.kt"
---

# Application Service 리뷰 기준

- `@Transactional` 의 위치·전파·`readOnly`·롤백 조건을 점검한다. 조회 전용 유스케이스에 `readOnly = true` 가 빠져 있으면 지적한다.
- 트랜잭션 안에서 외부 HTTP 호출이 일어나면 강하게 지적한다(트랜잭션 길이 / 타임아웃 / 재시도 측면).
- 같은 비즈니스 동작에서 `authenticate` 같은 비싼 검증(BCrypt 매칭 등) 이 중복 호출되지 않는지 본다 — 도메인 메서드와 서비스 헬퍼가 같은 검증을 두 번 수행하면 BCrypt 비용이 두 배가 된다.
- 외부 의존성(HTTP·DB·메시지) 호출에는 타임아웃·재시도·서킷브레이커 / 멱등성·중복 처리 방지 전략이 있는지 점검한다.
- 도메인 규칙은 도메인 객체(`User.changePassword` 등) 에 두고 서비스에서는 흐름만 조립한다. 서비스가 도메인 규칙을 직접 코딩하면 지적한다.
- "사용자 없음" 과 "비밀번호 불일치" 를 구분하는 응답을 내보내면 계정 enumeration 위험으로 지적한다 — 둘 다 동일한 `UNAUTHORIZED` 와 동일한 메시지로 응답해야 한다.
- `userRepository.save(user)` 호출이 dirty checking 으로 충분한 상황에서 들어 있으면, 의도(테스트 fake 호환 등) 를 확인 질문으로 남긴다.
