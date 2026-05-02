---
applyTo: "**/application*.yml,**/application*.yaml"
---

# Spring 설정 파일 리뷰 기준

- 환경 분리(`application-{profile}.yml`) 가 정확한지 본다. 로컬·개발·운영 값이 한 파일에 섞여 있으면 지적한다.
- 데이터베이스 URL·시크릿·외부 API 키가 평문으로 커밋되지 않았는지 점검한다. 발견 시 강하게 지적하고 환경 변수 / 시크릿 매니저 사용을 권한다.
- 타임아웃·커넥션 풀 사이즈·로깅 레벨 변경은 운영 영향이 크다. 변경 사유 / 영향 범위 / 롤백 방법이 PR 본문에 적혀 있는지 확인하고 없으면 요구한다.
- 로깅 레벨이 `DEBUG` / `TRACE` 로 운영 프로파일에 들어와 있으면 지적한다.
- `spring.jpa.show-sql=true` / `hibernate.show_sql` 가 운영 프로파일에 들어 있으면 성능·보안 양면에서 지적한다.
