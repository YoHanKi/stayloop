package com.stayloop.support.concurrency

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * 핫키(인기 객실·선착순 쿠폰) admission control (04 review P1-4).
 *
 * 키별 in-flight 동시 실행 수를 [maxConcurrentPerKey] 로 제한해, 한 핫키의 폭주가 DB 커넥션 풀을 마르게 해
 * 무관한 요청까지 막는 연쇄 장애를 끊는다. 한도를 넘는 요청은 DB 에 닿기 전에 429(TOO_MANY_REQUESTS)로
 * 빠르게 실패시킨다.
 *
 * 인스턴스 로컬 세마포어다(분산 아님) — 단일 MySQL row lock 이 정합을 책임지므로 분산 락은 불필요하고, 본 가드는
 * 각 앱 인스턴스의 핫키 유입만 제한한다(04-a 미룬 빚의 1차 방어선). 한계: 키별 세마포어 맵이 본 적 있는 키만큼
 * 누적된다(만료 캐시는 후속).
 */
@Component
class HotKeyGuard(
    @Value("\${stayloop.admission.max-concurrent-per-key:64}") private val maxConcurrentPerKey: Int,
) {
    private val semaphores = ConcurrentHashMap<String, Semaphore>()

    /** [key] 의 동시 실행이 한도 미만이면 [action] 을 실행하고, 한도면 즉시 TOO_MANY_REQUESTS 로 거절한다. */
    fun <T> withPermit(key: String, action: () -> T): T {
        val semaphore = semaphores.computeIfAbsent(key) { Semaphore(maxConcurrentPerKey, true) }
        if (!semaphore.tryAcquire()) {
            throw CoreException(ErrorType.TOO_MANY_REQUESTS, "요청이 몰려 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.")
        }
        try {
            return action()
        } finally {
            semaphore.release()
        }
    }
}
