package com.stayloop.domain.coupon

import com.stayloop.domain.common.value.Money
import com.stayloop.domain.coupon.value.Discount
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/**
 * Coupon 도메인 서비스. (`docs/plan/week4.md` ① Phase A-6, `docs/plan/week4/decision.md` D-3)
 *
 * **Repository 의존하지 않음** — `issue` / `template` 은 *호출자 (Facade) 가 미리 조회해 인자로 주입*.
 * 도메인 서비스는 영속성 책임을 지지 않으며, 트랜잭션 / 본인 자원 인가 / `@Transactional` 은 모두 Facade 책임
 * (`ReservationService` 패턴 답습 — 도메인 서비스 = 순수 함수).
 *
 * **`apply()` 는 `issue.use()` 를 호출하지 않는다.** Facade 가 *같은 트랜잭션 안에서*
 *   1. `apply(issue, template, before, now)` → `Discount` 받기
 *   2. `reservationRepository.save(reservation)` → `reservation.id` 부여
 *   3. `issue.use(actor, reservation.id, now)` + `couponIssueRepository.save(issue)`
 * 의 순서로 호출. **Discount 계산과 issue 상태 변경을 분리** — `apply` 가 *순수 함수* 로 머물러 단위 테스트가
 * 영속성 부재로도 가능하고, 미래 *Discount 결과를 사용 처리 없이 미리 보여주기* (예: 결제 화면 미리보기) 같은 진입점도
 * 같은 메서드로 흡수 가능.
 *
 * 본 서비스의 책임 (`apply`):
 * 1. **issue ↔ template 정합** — `issue.templateId == template.id` (잘못된 페어로 silent 다른 정책 적용 차단)
 * 2. **만료 가드** — `template.requireUsable(now)` (BAD_REQUEST)
 * 3. **최소 결제 금액 가드** — `template.minOrderAmount?.requireApplicable(beforeDiscount)` (BAD_REQUEST)
 * 4. **정액/정률 계산 + 박제** — `template.discountValue.apply(before)` → `Discount(before, amount, final)`
 *
 * 의식적으로 *빼는* 가드:
 * - **`issue.status == AVAILABLE` 검증** — `issue.use()` 가 `canTransitTo(USED)` 로 자체 검증한다.
 *   본 서비스에서 중복 검증하면 *Strong Exception Safety 가드와 책임이 흐려짐* — *use 책임은 use 가, apply 책임은
 *   apply 가*. 동시성 (한 쿠폰을 두 기기에서) 은 ③ Phase B 의 `@Version` + DB UNIQUE 가 별도로 잡는다.
 * - **본인 소유 검증 (`actor == issue.userId`)** — Facade 가 사전 검증 (BAD_REQUEST 메시지 일반화) +
 *   `issue.use()` 가 마지막 방어선 (FORBIDDEN). 도메인 서비스는 이 영역의 책임이 없다.
 */
@Service
class CouponIssueService {

    /**
     * 쿠폰 적용 — Discount 계산 결과 박제 반환. issue 의 상태는 변경하지 않는다 (호출자가 별도 호출).
     *
     * @param issue 사용자가 발급받은 쿠폰 인스턴스 (`CouponFacade.reserve` 가 사전 조회 + 본인 소유 검증)
     * @param template 해당 정책 (`CouponFacade` 가 `issue.templateId` 로 조회)
     * @param beforeDiscount 할인 전 결제 금액 (일자별 요금 합산 결과)
     * @param now 현재 시각 (만료 판정 + 테스트 시각 주입)
     * @return 박제용 `Discount` (before / amount / final)
     */
    fun apply(
        issue: CouponIssueModel,
        template: CouponTemplateModel,
        beforeDiscount: Money,
        now: LocalDateTime,
    ): Discount {
        if (issue.templateId != template.id) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "쿠폰 인스턴스와 템플릿이 일치하지 않습니다.",
            )
        }
        template.requireUsable(now)
        template.minOrderAmount?.requireApplicable(beforeDiscount)

        val amount = template.discountValue.apply(beforeDiscount)
        return Discount(
            beforeDiscount = beforeDiscount,
            amount = amount,
            finalPrice = beforeDiscount - amount,
        )
    }
}
