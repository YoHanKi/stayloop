---
applyTo: "**/build.gradle,**/build.gradle.kts,**/settings.gradle,**/settings.gradle.kts"
---

# Gradle 빌드 파일 리뷰 기준

- 의존성 추가는 목적·범위·버전 고정 여부를 본다. 명시적 버전 없이 `latest.release` 같은 동적 버전이 들어오면 지적한다.
- 새로 들어온 의존성은 라이선스·보안 취약점(CVE) 노출 가능성을 짚는다.
- `kotlin-allopen` / `kotlin-jpa` 플러그인 설정은 `@Entity` / `@MappedSuperclass` / `@Embeddable` 자동 open 동작에 영향을 준다. 어노테이션 목록이 임의로 빠지지 않았는지 본다.
- `tasks.withType<Test>` / ktlint / 정적 분석 태스크의 변경은 CI 영향 범위가 크다. 함께 변경된 워크플로 / pre-commit 설정이 있는지 확인한다.
- `dependencyManagement` 의 BOM 버전 변경은 추이 의존성을 통째로 흔든다. 영향 범위 검증을 요구한다.
- 모듈 추가(`include(":apps:...")` / `include(":modules:...")` ) 시 README.md 의 모듈 정책과 일치하는지 본다.
