package com.stayloop.application.coupon.admin

import com.stayloop.application.coupon.CouponIssueInfo
import com.stayloop.application.coupon.CouponTemplateInfo
import com.stayloop.domain.common.value.PageQuery
import com.stayloop.domain.coupon.CouponIssueRepository
import com.stayloop.domain.coupon.CouponTemplateModel
import com.stayloop.domain.coupon.CouponTemplateRepository
import com.stayloop.domain.coupon.value.CouponName
import com.stayloop.domain.coupon.value.DiscountValue
import com.stayloop.domain.coupon.value.ExpirationPeriod
import com.stayloop.domain.coupon.value.MinOrderAmount
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
 * 어드민 Coupon Facade. (`docs/plan/week4.md` ① Phase C-1, `docs/plan/week4/decision.md` D-2)
 *
 * **인증** — 본 라운드 어드민 인증 미구현 (1주차 회원 / 2~3주차 facade 답습 정책). week4-quests 어드민 표 의
 * `ldap_required` 는 P1 로 박제 (`docs/plan/week4.md` ① Phase C 의식적 미구현).
 *
 * **트랜잭션 정책** — `register` / `update` / `delete` 는 *읽기-쓰기* (`@Transactional`),
 * `list` / `detail` / `listIssues` 는 `readOnly = true`.
 *
 * **delete 정책 — soft delete X, 발급 이력 가드 후 hard delete**
 * `CouponIssueRepository.existsByTemplateId(templateId)` 가 true 면 `CONFLICT` 거절.
 * 이미 발급된 쿠폰은 사용자 자원 — 정책 삭제로 사용자 쿠폰이 *조용히 무효화* 되면 사용자가 *왜 사라졌는지* 모른다.
 * 운영 측 *발급 이력 0 인 정책만 삭제* 정책으로 단순화 (verify-code §6 — 사일런트 사고 차단).
 *
 * **listIssues 의 사용자 식별자 노출** — 응답에 `userId: BIGINT` 가 박제되어 있으나 *어드민 라우트* 라
 * 식별자 노출 자체는 정책상 허용. *대고객 응답에는 노출 금지* (verify-code §12) 와 구분되는 영역.
 * `CouponIssueInfo` 자체는 BIGINT 를 직접 노출하지 않으므로 어드민 응답이 별도 변환 없이 재사용 가능.
 */
@Service
class CouponAdminFacade(
    private val couponTemplateRepository: CouponTemplateRepository,
    private val couponIssueRepository: CouponIssueRepository,
    private val userRepository: UserRepository,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 쿠폰 템플릿 등록. `code` UNIQUE 충돌 시 CONFLICT 로 거절.
     */
    @Transactional
    fun register(command: RegisterCouponTemplateCommand): CouponTemplateInfo {
        if (couponTemplateRepository.findByCode(command.code) != null) {
            throw CoreException(ErrorType.CONFLICT, "이미 등록된 쿠폰 코드입니다.")
        }
        val template = CouponTemplateModel.create(
            code = command.code,
            name = CouponName(command.name),
            discountValue = DiscountValue(type = command.discountType, rawValue = command.discountValue),
            expirationPeriod = ExpirationPeriod(expiredAt = command.expiredAt),
            minOrderAmount = command.minOrderAmount?.let { MinOrderAmount(it) },
        )
        return CouponTemplateInfo.from(couponTemplateRepository.save(template))
    }

    /**
     * 쿠폰 템플릿 수정 (전체 필드 갱신).
     *
     * `code` 가 변경되었을 때 다른 row 와 UNIQUE 충돌 가능성 — `findByCode` 사전 검사 + 동일 row 면 통과.
     */
    @Transactional
    fun update(command: UpdateCouponTemplateCommand): CouponTemplateInfo {
        val template = couponTemplateRepository.findById(command.templateId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "존재하지 않는 쿠폰입니다.")
        val collision = couponTemplateRepository.findByCode(command.code)
        if (collision != null && collision.id != template.id) {
            throw CoreException(ErrorType.CONFLICT, "이미 등록된 쿠폰 코드입니다.")
        }
        template.update(
            code = command.code,
            name = CouponName(command.name),
            discountValue = DiscountValue(type = command.discountType, rawValue = command.discountValue),
            expirationPeriod = ExpirationPeriod(expiredAt = command.expiredAt),
            minOrderAmount = command.minOrderAmount?.let { MinOrderAmount(it) },
        )
        return CouponTemplateInfo.from(couponTemplateRepository.save(template))
    }

    /**
     * 쿠폰 템플릿 삭제. 발급 이력이 있으면 CONFLICT 로 거절 (soft delete 미도입).
     */
    @Transactional
    fun delete(templateId: Long) {
        val template = couponTemplateRepository.findById(templateId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "존재하지 않는 쿠폰입니다.")
        if (couponIssueRepository.existsByTemplateId(template.id)) {
            throw CoreException(
                ErrorType.CONFLICT,
                "발급 이력이 있는 쿠폰은 삭제할 수 없습니다.",
            )
        }
        couponTemplateRepository.deleteById(template.id)
    }

    /**
     * 쿠폰 템플릿 목록 — `id DESC` 고정 정렬.
     */
    @Transactional(readOnly = true)
    fun list(page: PageQuery): List<CouponTemplateInfo> =
        couponTemplateRepository.findAll(page).map(CouponTemplateInfo::from)

    /**
     * 쿠폰 템플릿 단건 상세.
     */
    @Transactional(readOnly = true)
    fun detail(templateId: Long): CouponTemplateInfo {
        val template = couponTemplateRepository.findById(templateId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "존재하지 않는 쿠폰입니다.")
        return CouponTemplateInfo.from(template)
    }

    /**
     * 특정 템플릿의 발급 이력 — `issuedAt DESC` 고정 정렬.
     *
     * **누락 사용자 정책** — 발급 행이 가리키는 `users.id` 가 `findAll` 결과에 없으면
     * (사용자 hard delete + 발급 이력 잔존 — 본 라운드는 사용자 hard delete 미지원이라 발생 가능성 낮음)
     * 응답의 `loginId` 는 `"<deleted>"` placeholder 로 표현 (어드민 화면이 *왜 sender 가 비었는지* 모르지 않도록).
     * `INTERNAL_ERROR` 까지는 가지 않음 — 어드민 운영 도구는 깨진 데이터도 *보여줘야* 원인 파악이 가능.
     */
    @Transactional(readOnly = true)
    fun listIssues(templateId: Long, page: PageQuery): List<CouponAdminIssueInfo> {
        val template = couponTemplateRepository.findById(templateId)
            ?: throw CoreException(ErrorType.NOT_FOUND, "존재하지 않는 쿠폰입니다.")
        val issues = couponIssueRepository.findByTemplateId(template.id, page)
        if (issues.isEmpty()) return emptyList()
        val now: LocalDateTime = LocalDateTime.now(clock)
        val userIdToLoginId = resolveUserLoginIds(issues.map { it.userId }.distinct())
        return issues.map { issue ->
            CouponAdminIssueInfo(
                issue = CouponIssueInfo.of(issue, template, now),
                userLoginId = userIdToLoginId[issue.userId]?.value ?: DELETED_USER_PLACEHOLDER,
            )
        }
    }

    /**
     * BIGINT user_id → LoginId 매핑. 누락 사용자 (= 사용자 hard delete + 발급 잔존) 는 결과에서 빠진다 —
     * 호출자가 [DELETED_USER_PLACEHOLDER] 로 표현.
     */
    private fun resolveUserLoginIds(userIds: List<Long>): Map<Long, LoginId> {
        if (userIds.isEmpty()) return emptyMap()
        val users = userRepository.findAllByIds(userIds)
        if (users.size < userIds.size) {
            val resolved = users.map { it.id }.toSet()
            val missing = userIds.filter { it !in resolved }
            log.warn("Coupon admin listIssues — 참조 사용자 누락. missingUserIds={}", missing)
        }
        return users.associate { it.id to it.loginId }
    }

    companion object {
        // 어드민 응답에서 sentinel 로 사용. 실 사용자 LoginId 와 충돌하지 않는 형태.
        private const val DELETED_USER_PLACEHOLDER: String = "<unknown>"
    }
}

/**
 * 어드민 — 발급 이력 응답. `CouponIssueInfo` (사용자 자원 박제) 위에 *어드민 메타* 만 추가.
 */
data class CouponAdminIssueInfo(
    val issue: CouponIssueInfo,
    val userLoginId: String,
)
