---
applyTo: "**/infrastructure/**/*.kt,**/*JpaRepository*.kt,**/*RepositoryImpl*.kt"
---

# Repository / JPA 리뷰 기준

## 패키지 구조 (3-class 분리)

- 도메인 Repository 인터페이스(`com.stayloop.domain.{aggregate}.{Aggregate}Repository`) + 인프라 `{Aggregate}JpaRepository`(`extends JpaRepository<{Aggregate}Model, Long>` **만** 상속) + 인프라 `{Aggregate}RepositoryImpl`(`@Component`, 도메인 Repository 구현 + JpaRepository 위임) 의 3-class 구조를 유지한다.
- `{Aggregate}JpaRepository` 가 도메인 Repository 인터페이스를 직접 상속하는 다중 상속 패턴(`UserJpaRepository : UserRepository, JpaRepository<...>`) 은 도메인이 Spring Data 를 의식하게 되므로 강하게 지적한다 — `RepositoryImpl` 로 위임 분리를 권한다.
- 도메인 `*Repository` 인터페이스에 `@Query`, `Specification`, `Pageable` 등 JPA/Spring Data 전용 타입이 새지 않는지 점검한다. 인프라 어노테이션은 `infrastructure/` 어댑터에 격리해야 한다.

## 성능

- N+1 가능성을 본다. `fetch join` / `@EntityGraph` 사용 여부, 페이징과 `fetch join` 동시 사용의 위험을 점검한다.
- 트랜잭션 밖에서 lazy 로딩이 발생할 가능성이 있는지 확인한다. 컨트롤러 응답 시점에 lazy 가 터지는 흐름은 강하게 지적한다.
- 대량 데이터 시나리오(전체 조회·정렬·검색) 에서 인덱스 활용 가능성을 본다.
- 단순 `findById` 가 충분한 자리에 `findAll().filter { ... }` 같은 in-memory 필터가 들어와 있으면 지적한다.

## 메서드 네이밍 쿼리

- Spring Data 메서드 네이밍 쿼리(`findByLoginId`, `existsByLoginId`)는 인프라 `JpaRepository` 에만 둔다. 도메인 Repository 시그니처가 의미상 매칭되는지 점검한다 — 매칭이 깨지면 `RepositoryImpl` 위임 컴파일이 깨진다.
- 복잡 조건은 `@Query` 또는 QueryDSL 로 명시한다. 메서드명이 길어지면 가독성·인덱스 의도가 흐려지므로 명시적 쿼리를 권한다.

## 어댑터 / 외부 포트

- 외부 시스템 어댑터(예: `BCryptPasswordEncoderAdapter`) 는 도메인 인터페이스(예: `PasswordEncoder`) 만 구현하고 외부 라이브러리 의존을 격리해야 한다. 어댑터 외부에서 외부 라이브러리 타입이 노출되면 지적한다.
