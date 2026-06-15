# 쿠폰 도메인 — 사용/발급 경로 분리와 동시성 제어

week4 chunk 2. 관련 계획: `docs/plan/week4-a.md` §6.3·§8, `week4-b.md` §3 Q2·§5-2.

## 1. 맥락 / 문제

쿠폰 자원이 없던 상태에서 신규 도입한다. 닫아야 할 불변식:
- 한 쿠폰은 최대 1회 사용(중복 할인 금지)
- 선착순 발급 수 ≤ 한정 수량(초과 발급 금지)
- 같은 요청을 두 번 보내도 결과는 한 번과 같다(중복 발급 금지)

## 2. 선택지와 고민

핵심은 "쿠폰을 한 덩어리로 볼 것인가"다. 04-a §6.3 결론: **사용과 발급의 경합 특성이 정반대**다.
- 사용(예약 시 적용): 본인 소유 쿠폰 1장 → 경합은 같은 사용자의 동시 2요청에 한정(저경합).
- 선착순 발급(공유 한정 수량): 다수가 한 카운터를 다툼 → 재고 차감과 동형의 핫스팟.

그래서 같은 전략으로 묶지 않고 두 Aggregate 로 분리한다.
- `CouponTemplateModel`(캠페인·할인·한정 수량·발급 수) — 발급 핫스팟.
- `IssuedCouponModel`(사용자 소유·상태·할인 스냅샷) — 사용 저경합.

**사용 1회성** 후보: ① 낙관적 락(@Version) ② 조건부 상태 전이(`UPDATE ... SET status=USED WHERE status=AVAILABLE`). 04-a §8 은 낙관 락이 핫스팟에서 탈락이라 했고, chunk 1 에서도 조건부 원자 갱신을 채택했다. 사용은 저경합이라 둘 다 가능하지만, 조건부 상태 전이가 chunk 1 과 일관되고 버전 컬럼·재시도 루프가 불필요해 단순하다 → ②.

**중복 발급**(동일인) 후보: 선검사(existsBy) vs UNIQUE 제약. 선검사는 동시 2요청에 race 가 남는다 → `(coupon_template_id, user_login_id)` UNIQUE 가 진짜 가드.

**userId 저장**: LoginId 값 저장(Reservation 식) vs Long FK(Wishlist 식). 쿠폰 사용은 chunk 3 에서 예약(LoginId)과 합류하므로 LoginId 값을 저장해 변환 조회를 없앤다(선택, §2 트레이드오프).

## 3. 코드 예시

발급(핫스팟) — 조건부 원자 증가, 영향 행 수는 도메인 서비스가 해석:

```kotlin
// CouponTemplateRepositoryImpl (QueryDSL)
queryFactory.update(template)
    .set(template.issuedCount, template.issuedCount.add(1))
    .where(template.id.eq(templateId), template.issuedCount.add(1).loe(template.totalQuantity))
    .execute().toInt() // 0=소진
// CouponService.issue: claimed != 1 → CONFLICT, 이후 IssuedCoupon insert(UNIQUE 가 중복 발급 차단)
```

사용(저경합) — 조건부 상태 전이:

```kotlin
queryFactory.update(issued)
    .set(issued.status, CouponStatus.USED).set(issued.usedAt, usedAt)
    .where(issued.id.eq(issuedCouponId), issued.status.eq(CouponStatus.AVAILABLE))
    .execute().toInt() // 0=이미 사용 → 도메인 서비스가 CONFLICT
```

동일인 중복 발급: `(coupon_template_id, user_login_id)` UNIQUE → 위반(영속성 예외)은 Facade 가 CONFLICT 로 변환(증가시킨 발급 수는 트랜잭션 롤백).

## 4. 실제 테스트 환경

- 정합성: 실 MySQL 8.0(Testcontainers) `ConcurrentCouponTest` — 운영 `CouponFacade`/`CouponService`/QueryDSL 어댑터를 그대로 조립.
  - 발급: 한정 수량 10 에 60 사용자 동시 발급 → 성공 10·발급 수 10.
  - 중복 발급: 1 사용자 30 스레드 동시 발급 → 성공 1·발급 수 1.
  - 사용: 1 발급 쿠폰 30 스레드 동시 사용 → 성공 1·status USED.
- 단위/통합: `DiscountValueTest`(할인 계산·가드), `CouponService/FacadeTest`(소진·중복·NOT_FOUND), InMemory 더블은 UNIQUE 위반을 `DataIntegrityViolationException` 으로 흉내내 운영과 동치.

## 5. 판단 기준

경합 특성에 맞는 최소 제어(저경합엔 상태 전이, 핫스팟엔 조건부 카운터), 계층 책임(영속성=사실, 도메인=판단), chunk 1 과의 일관.

## 6. 결과

- 더블부킹/초과 발급/중복 발급/중복 사용 모두 동시성 테스트로 부재 확인.
- 발급 핫스팟은 재고와 동형 조건부 원자 UPDATE, 사용은 조건부 상태 전이로 닫힘. 낙관적 락·비관적 락 미사용.

## 7. 트레이드오프

- LoginId 값 저장은 users 와의 FK 무결성을 포기한다(존재하지 않는 사용자에게도 발급 가능). 인증 레이어가 LoginId 를 보장하는 전제에서 수용.
- 발급 시 할인 스냅샷은 템플릿 변경으로부터 발급분을 보호하지만, 저장 중복(템플릿·발급분 각각 discount 컬럼)이 생긴다. 과거 발급분 금액 불변이 더 중요해 수용.
- 사용 1회성을 조건부 상태 전이로만 둔다(별도 사용 이력 테이블 없음). 사용 이력·환불은 후속(week5~6).

## 8. 결론

**사용/발급을 2 Aggregate 로 분리. 발급은 조건부 원자 UPDATE + (template,user) UNIQUE, 사용은 조건부 상태 전이.** 낙관/비관 락은 쓰지 않는다. 영향 행 수 해석은 `CouponService`(도메인), UNIQUE 위반의 사용자 응답 변환은 `CouponFacade`(앱). chunk 1·CLAUDE.md "쿼리 작성" 규칙과 일관.

되돌릴 트리거: 사용 이력/부분 환불이 필요해지면 IssuedCoupon 상태 머신 확장 + 사용 이력 테이블, 결제 시점 USED 확정 분리(Hold/Used).

## 9. 후속 / 미해결

- 쿠폰 '사용'의 예약 트랜잭션 합류(할인 금액 스냅샷·USED 확정 시점)는 chunk 3.
- 발급 정책(유효기간·만료 배치), 결제 시점 USED 확정 분리는 week5~6.
- 발급 핫스팟의 단일 카운터 직렬화 한계(per-key rate limit 등)는 04 미룬 빚.
