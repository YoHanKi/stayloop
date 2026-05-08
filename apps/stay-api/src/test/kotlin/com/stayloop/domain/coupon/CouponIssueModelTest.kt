package com.stayloop.domain.coupon

import com.stayloop.domain.coupon.value.CouponIssueStatus
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class CouponIssueModelTest {

    private val ownerId = 1L
    private val otherId = 2L
    private val issuedAt = LocalDateTime.of(2026, 5, 1, 10, 0)
    private val now = LocalDateTime.of(2026, 5, 8, 14, 0)

    private fun newIssue(): CouponIssueModel =
        CouponIssueModel.issue(templateId = 1L, userId = ownerId, issuedAt = issuedAt)

    @DisplayName("발급 시 status = AVAILABLE, usedAt / usedReservationId 는 null.")
    @Test
    fun shouldStartAsAvailable() {
        val issue = newIssue()

        assertThat(issue.status).isEqualTo(CouponIssueStatus.AVAILABLE)
        assertThat(issue.usedAt).isNull()
        assertThat(issue.usedReservationId).isNull()
        assertThat(issue.issuedAt).isEqualTo(issuedAt)
        assertThat(issue.userId).isEqualTo(ownerId)
        assertThat(issue.templateId).isEqualTo(1L)
    }

    @DisplayName("templateId 가 0 이하면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenTemplateIdNonPositive() {
        assertThatThrownBy {
            CouponIssueModel.issue(templateId = 0L, userId = ownerId, issuedAt = issuedAt)
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("userId 가 0 이하면 BAD_REQUEST 로 거절된다.")
    @Test
    fun shouldReject_whenUserIdNonPositive() {
        assertThatThrownBy {
            CouponIssueModel.issue(templateId = 1L, userId = 0L, issuedAt = issuedAt)
        }.isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)
    }

    @DisplayName("use 정상 — status=USED, usedAt / usedReservationId 박제.")
    @Test
    fun shouldUse_whenAvailableAndOwner() {
        val issue = newIssue()

        issue.use(actor = ownerId, reservationId = 100L, now = now)

        assertThat(issue.status).isEqualTo(CouponIssueStatus.USED)
        assertThat(issue.usedAt).isEqualTo(now)
        assertThat(issue.usedReservationId).isEqualTo(100L)
    }

    @DisplayName("use 시 actor 가 본인 아니면 FORBIDDEN — 상태 변경 X (Strong Exception Safety).")
    @Test
    fun shouldReject_whenActorNotOwner() {
        val issue = newIssue()

        assertThatThrownBy { issue.use(actor = otherId, reservationId = 100L, now = now) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.FORBIDDEN)

        assertThat(issue.status).isEqualTo(CouponIssueStatus.AVAILABLE)
        assertThat(issue.usedAt).isNull()
        assertThat(issue.usedReservationId).isNull()
    }

    @DisplayName("use 시 reservationId 가 0 이하면 BAD_REQUEST — 상태 변경 X.")
    @Test
    fun shouldReject_whenReservationIdNonPositive() {
        val issue = newIssue()

        assertThatThrownBy { issue.use(actor = ownerId, reservationId = 0L, now = now) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST)

        assertThat(issue.status).isEqualTo(CouponIssueStatus.AVAILABLE)
        assertThat(issue.usedAt).isNull()
        assertThat(issue.usedReservationId).isNull()
    }

    @DisplayName("이미 USED 인 쿠폰을 다시 use 하면 CONFLICT — 부분 변경 X (Strong Exception Safety).")
    @Test
    fun shouldReject_whenAlreadyUsed() {
        val issue = newIssue()
        issue.use(actor = ownerId, reservationId = 100L, now = now)
        val firstUsedAt = issue.usedAt
        val firstReservationId = issue.usedReservationId

        assertThatThrownBy { issue.use(actor = ownerId, reservationId = 200L, now = now.plusHours(1)) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT)

        assertThat(issue.usedAt).isEqualTo(firstUsedAt)
        assertThat(issue.usedReservationId).isEqualTo(firstReservationId)
    }

    @DisplayName("EXPIRED 인 쿠폰을 use 하면 CONFLICT.")
    @Test
    fun shouldReject_whenExpired() {
        val issue = newIssue()
        issue.expire()

        assertThatThrownBy { issue.use(actor = ownerId, reservationId = 100L, now = now) }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT)
    }

    @DisplayName("expire 정상 — AVAILABLE → EXPIRED.")
    @Test
    fun shouldExpire_whenAvailable() {
        val issue = newIssue()

        issue.expire()

        assertThat(issue.status).isEqualTo(CouponIssueStatus.EXPIRED)
    }

    @DisplayName("이미 USED 인 쿠폰을 expire 하면 CONFLICT.")
    @Test
    fun shouldReject_expire_whenAlreadyUsed() {
        val issue = newIssue()
        issue.use(actor = ownerId, reservationId = 100L, now = now)

        assertThatThrownBy { issue.expire() }
            .isInstanceOf(CoreException::class.java)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT)
    }
}
