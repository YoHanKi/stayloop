# 예약 트랜잭션 통합 — 임계 구간·재시도·금액 스냅샷·중복/취소 처리

week4 chunk 3. 관련 계획: `docs/plan/week4-a.md` §4·§8, `week4-b.md` §3 Q3·§4·§5-3·§6.

## 1. 맥락 / 문제

예약 생성은 재고 차감·쿠폰 사용·금액 확정·예약 저장을 한 단위로 묶어야 한다. 닫아야 할 불변식:
- 어느 단계라도 실패하면 전체 롤백(부분 반영 금지)
- 예약 금액 = 생성 시점 확정(이후 요금·쿠폰 정책 변경 무관)
- 데드락 victim·락 타임아웃이 사용자에게 그대로 새지 않음(한 번의 결과만)
- 같은 요청 두 번 → 결과 한 번

## 2. 선택지와 고민

**임계 구간 구성**(Q3): 락 밖에서 사전 조회(요금·쿠폰)와 검증을 끝내고, 임계 구간엔 차감·사용·저장만 둔다. 자원 점유 순서는 **재고(일자 오름차순) → 쿠폰**으로 통일하고 생성·취소가 같은 순서를 따라 데드락 표면을 줄인다(04-a §4).

**재시도 메커니즘**: 선언적 `@Transactional` 은 메서드 1회 = 트랜잭션 1회라 재시도 시 새 트랜잭션을 열기 어렵다(self-invocation 으로 프록시 우회). 후보 ① Spring Retry(@Retryable) — 의존 추가 ② 별도 @Transactional 빈 + 외부 루프 ③ `TransactionTemplate` 루프 — 매 `execute()` 가 새 트랜잭션. ③ 을 택했다(의존 없이 매 시도 새 트랜잭션, 자기호출 문제 없음, 코드 자급).

**재시도 대상**(04-a §8): 데드락 victim·락 대기 타임아웃(`PessimisticLockingFailureException` 계열)은 일시 충돌 → 짧은 지터 백오프 재시도. 조건부 UPDATE 0행(매진·소진)은 비즈니스 실패(`CoreException`) → 재시도 금지. 한계(3회) 초과 시 CONFLICT.

**금액 스냅샷**: 할인 전 합산액·할인액·최종액 3종 + 사용 쿠폰 식별자를 예약에 박는다. 할인 계산은 락 밖 견적(`ReservationService`), `Money.minus` 추가.

**중복 예약**(§6 선결, 사용자 결정): 예약 레벨 멱등 키는 이번 범위 제외. 쿠폰 보유 예약은 쿠폰 원자 사용이 중복 제출 시 1건만 반영. 예약 레벨 유니크는 같은 객실 2개 예약·취소 후 재예약과 충돌(MySQL 부분 유니크 미지원)해 채택하지 않는다.

**취소 시 쿠폰**(사용자 결정): 재고와 함께 쿠폰도 복원(USED→AVAILABLE, 멱등). 사용자가 취소로 쿠폰을 잃지 않게 한다.

## 3. 코드 예시

```kotlin
// 락 밖: property/roomType/rates/coupon 조회·검증
// 임계 구간 + 재시도
return retryOnTransientLock {
    txTemplate.execute {
        inventoryService.reserve(roomTypeId, dates)          // 재고(일자순)
        coupon?.let { couponService.use(it.id, now) }         // 쿠폰(조건부 상태전이)
        val reservation = reservationService.reserve(..., discount = coupon?.discount, couponId = coupon?.id)
        ReservationInfo.from(reservationRepository.save(reservation))
    }!!
}
// retryOnTransientLock: catch PessimisticLockingFailureException → 지터 백오프 재시도(최대 3), 초과 시 CONFLICT.
//                       CoreException(매진·소진)은 잡지 않음 → 즉시 전파(재시도 금지).
```

## 4. 실제 테스트 환경

- 정합성: 실 MySQL 8.0(Testcontainers) `ConcurrentReservationTest` — 운영 `ReservationFacade`/`CouponFacade` 빈을 그대로.
  - 같은 쿠폰 2 동시 예약 → 성공 1·재고 1회 차감·쿠폰 USED(중복 요청 한 건 반영).
  - 이미 사용한 쿠폰으로 예약 → CONFLICT, 재고 차감·예약 생성 전체 롤백(reserved 1 유지, 예약 1건).
- 단위/통합: `ReservationServiceTest`(금액 3종 스냅샷·할인), `ReservationFacadeTest`(쿠폰 적용·취소 시 쿠폰 복원·타인 쿠폰 403), `MoneyTest`(minus·음수 거절). POJO 테스트는 `NoOpTransactionManager` 로 `TransactionTemplate` 콜백을 실행한다.

## 5. 판단 기준

임계 구간 최소화(04-a §5), 실패 종류별 재시도(데드락 흡수 / 비즈니스 즉시 실패), 계층 책임 일관(영속성=사실, 도메인/앱=판단), 사용자 비노출(victim 롤백을 한 번의 결과로).

## 6. 결과

- 재고·쿠폰·금액·예약을 한 트랜잭션으로 묶고, 부분 실패 전체 롤백을 Testcontainers 로 증명.
- 일시 락 충돌은 재시도로 흡수(사용자엔 CONFLICT 한 번), 매진·소진은 재시도 없이 즉시 실패.
- 금액 3종 + couponId 스냅샷 고정. 취소 시 재고·쿠폰 복원.

## 7. 트레이드오프

- `TransactionTemplate` 루프는 reserve/cancel 을 선언적 `@Transactional` 에서 프로그래밍적 경계로 바꾼다(조회는 `@Transactional(readOnly)` 유지). 재시도를 위해 수용.
- 예약 레벨 멱등 키 부재 → 쿠폰 없는 예약의 진짜 중복 제출은 막지 않는다(우리 모델에선 1예약=1객실이라 같은 객실 2건은 합법). 요청 단위 멱등 키는 공백(트리거: 업무 키로 안 잡히는 중복 관찰 시).
- 취소 시 쿠폰 복원은 결제·환불이 없는 이번 라운드 전제. 결제 합류(week5~6) 시 Hold/Used·환불과 재설계.
- `ReservationService` 가 `coupon.value.DiscountValue` 를 참조(도메인 간 결합). 예약 가격이 쿠폰 할인을 쓰는 정당한 협력으로 수용.

## 8. 결론

**한 트랜잭션 임계 구간(재고→쿠폰→저장) + 실패 종류별 재시도(TransactionTemplate 루프) + 금액 3종·couponId 스냅샷.** 중복 예약은 쿠폰 원자 사용으로 1차 보장(예약 레벨 키 제외), 취소 시 쿠폰 복원. 영향 행 수 해석은 도메인 서비스, 락 충돌 재시도/CONFLICT 변환은 Facade.

되돌릴 트리거: 업무 키로 안 잡히는 중복이 관찰되면 요청 단위 멱등 키 도입. 결제 합류 시 USED 확정 시점 분리(Hold/TTL)·환불.

## 9. 후속 / 미해결

- PENDING→CONFIRMED 분리·결제·환불·위약금, 예약 Hold+TTL, 알림/이벤트 커밋 후 처리(outbox) — week5~6.
- 빠른 실패 타임아웃(`innodb_lock_wait_timeout`·`NOWAIT`)·재시도 횟수/백오프의 운영 튜닝 — 기준선만, 측정 후 조정.
- 재시도·핫스팟 처리량의 부하 실측(k6)은 필요 시 별도.
