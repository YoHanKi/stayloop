package com.stayloop.support.test

import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus

/**
 * POJO 테스트용 무동작 트랜잭션 매니저 — [org.springframework.transaction.support.TransactionTemplate] 의
 * 콜백을 트랜잭션 없이 그대로 실행한다(실제 커밋·롤백 없음). 롤백 의미가 필요한 시나리오는 Testcontainers
 * 통합 테스트에서 검증한다.
 */
class NoOpTransactionManager : PlatformTransactionManager {
    override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = SimpleTransactionStatus()

    override fun commit(status: TransactionStatus) = Unit

    override fun rollback(status: TransactionStatus) = Unit
}
