package com.stayloop.application.coupon

import com.stayloop.application.coupon.command.IssueCouponCommand
import com.stayloop.domain.common.value.PageQuery
import com.stayloop.domain.coupon.CouponIssueModel
import com.stayloop.domain.coupon.CouponIssueRepository
import com.stayloop.domain.coupon.CouponTemplateRepository
import com.stayloop.domain.user.UserRepository
import com.stayloop.domain.user.value.LoginId
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

/**
 * 대고객 Coupon Facade. (`docs/plan/week4.md` ① Phase B-2, `docs/plan/week4/decision.md` D-2)
 *
 * **트랜잭션 정책** — `issue` 는 *읽기-쓰기* (`@Transactional`), `getMyCoupons` 는 `readOnly = true`.
 * Reservation 합류 (② `feature/reservation-coupon`) 시 사용 처리는 `ReservationFacade.reserve` 가 같은 TX 안에서
 * `issue.use(reservationId, now)` 호출 — 본 Facade 는 *발급 + 조회* 만 담당.
 *
 * **본인 자원 인가** — `issue` 는 actor 본인이 본인 templateId 로 발급, `getMyCoupons` 는 헤더 LoginId 기반.
 * 본 라운드는 *목록 조회 path 자체가 `/users/me/coupons` (path 에 식별자 없음)* — 다른 사용자 자원 조회 자체가
 * 불가능한 라우트 형태로 차단 (Wishlist 의 `/users/{userId}/wishes` 와 다른 결정 — 라우트 형태가 자연 가드).
 *
 * **발급 멱등 정책** — *본 라운드 미정의*. 같은 사용자가 같은 템플릿에 두 번 호출하면 *두 인스턴스가 발급*.
 * 실 정책 (1인 1매 / N매 / 선착순 한정 등) 은 6주차+ 분산 락 합류 시점에 결정 (week4-quests 명시 X).
 * `decision.md` D-1 ③ 의 *공유 자원 + 공정성* 영역.
 *
 * **만료 lazy 표현** — `getMyCoupons` 응답에서 `CouponIssueInfo.of(issue, template, now)` 가 *영속화 없이*
 * 만료 표현. 모델 자체의 `EXPIRED` 영속화는 6주차+ 배치 트리거 합류 시점.
 */
@Service
class CouponFacade(
    private val couponTemplateRepository: CouponTemplateRepository,
    private val couponIssueRepository: CouponIssueRepository,
    private val userRepository: UserRepository,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 쿠폰 발급. 만료된 정책으로의 발급은 가드 (BAD_REQUEST). 발급된 인스턴스는 `AVAILABLE` 로 시작.
     *
     * 1. 템플릿 조회 — 없으면 NOT_FOUND
     * 2. 만료 가드 — `template.requireUsable(now)` (BAD_REQUEST)
     * 3. 사용자 BIGINT 변환 — `users.findByLoginId` 없으면 UNAUTHORIZED (인증 헤더는 통과했지만 영속 사용자 없음)
     * 4. `CouponIssueModel.issue(templateId, userId, now)` 생성 + save
     */
    @Transactional
    fun issue(command: IssueCouponCommand): CouponIssueInfo {
        val now = LocalDateTime.now(clock)
        val template = couponTemplateRepository.findById(command.templateId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "존재하지 않는 쿠폰입니다.")
        template.requireUsable(now)
        val user = userRepository.findByLoginId(command.actor)
            ?: throw CoreException(ErrorType.UNAUTHORIZED, "인증된 사용자 정보를 찾을 수 없습니다.")
        val issue = CouponIssueModel.issue(
            templateId = template.id,
            userId = user.id,
            issuedAt = now,
        )
        val saved = couponIssueRepository.save(issue)
        return CouponIssueInfo.of(saved, template, now)
    }

    /**
     * 본인 쿠폰 목록 조회. 정렬은 `issuedAt DESC` 고정 — `page.sort` 가 비어있지 않으면 BAD_REQUEST.
     *
     * **누락 Template 정책 (verify-code §8 / §19-B)** — `findByUserId` 결과의 `templateId` 가 `findByIds` 에 없으면
     * (어드민 hard delete + 발급 행 잔존의 데이터 정합 깨짐) `INTERNAL_ERROR` 로 *명시적으로 실패*.
     * `mapNotNull` 로 조용히 누락하면 운영에서 원인 파악이 어렵다. 누락된 templateId 는 서버 로그에만 남기고
     * 응답 메시지는 일반화 (verify-code §12 — 식별자 노출 금지).
     *
     * 본 시나리오는 어드민 delete 정책 (`feature/coupon` C-1 — 발급 이력이 있으면 삭제 거절) 으로 발생 가능성
     * *낮지만 0 아님* — 가드를 명시적으로 둔다.
     */
    @Transactional(readOnly = true)
    fun getMyCoupons(actor: LoginId, page: PageQuery): List<CouponIssueInfo> {
        if (page.sort.isNotEmpty()) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "쿠폰 목록은 발급일 내림차순으로 고정 정렬되며, 사용자 정의 정렬을 지원하지 않습니다.",
            )
        }
        val now = LocalDateTime.now(clock)
        val issues = couponIssueRepository.findByUserId(actor, page)
        if (issues.isEmpty()) return emptyList()
        val templateIds = issues.map { it.templateId }.distinct()
        val templates = couponTemplateRepository.findAllByIds(templateIds).associateBy { it.id }
        val missing = templateIds.filter { it !in templates }
        if (missing.isNotEmpty()) {
            log.warn("Coupon 조회 — 참조 Template 누락. missingTemplateIds={}", missing)
            throw CoreException(
                ErrorType.INTERNAL_ERROR,
                "쿠폰 목록 조회 중 일관성이 깨진 데이터를 발견했습니다.",
            )
        }
        return issues.map { issue: CouponIssueModel ->
            CouponIssueInfo.of(issue, templates.getValue(issue.templateId), now)
        }
    }
}
