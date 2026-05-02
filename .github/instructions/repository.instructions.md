---
applyTo: "**/*Repository*.kt,**/*Repository*.java,**/infrastructure/**/*.kt"
---

# Repository / JPA 리뷰 기준

- N+1 가능성을 본다. `fetch join` / `@EntityGraph` 사용 여부, 페이징과 `fetch join` 동시 사용의 위험을 점검한다.
- 트랜잭션 밖에서 lazy 로딩이 발생할 가능성이 있는지 확인한다. 컨트롤러 응답 시점에 lazy 가 터지는 흐름은 강하게 지적한다.
- `JpaRepository` 와 도메인 `*Repository` 인터페이스를 같은 타입으로 다중 상속해 어댑터를 제거한 패턴(`UserJpaRepository : UserRepository, JpaRepository<User, Long>`) 이 깨지지 않는지 본다. 시그니처가 정확히 일치해야 동작한다.
- 도메인 `*Repository` 인터페이스에 `@Query`, `Specification`, `Pageable` 등 JPA 전용 타입이 새지 않는지 점검한다. 인프라 어노테이션은 `infrastructure/` 어댑터에 격리해야 한다.
- 대량 데이터 시나리오(전체 조회·정렬·검색) 에서 인덱스 활용 가능성을 본다.
- 단순 `findById` 가 충분한 자리에 `findAll().filter { ... }` 같은 in-memory 필터가 들어와 있으면 지적한다.
