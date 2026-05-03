package com.stayloop.domain.common.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType

/**
 * 도메인이 영속성 레이어와 무관하게 페이지 요청을 표현하는 VO.
 * Spring Data 의 `Pageable` 을 도메인이 알지 않도록 — 변환은 Facade / RepositoryImpl 책임.
 *
 * @property offset 0 이상의 시작 인덱스
 * @property limit  1 이상의 페이지 크기
 * @property sort   정렬 키 + 방향 목록 (도메인 어휘. 빈 리스트면 기본 정렬)
 */
data class PageQuery(
    val offset: Int,
    val limit: Int,
    val sort: List<SortKey> = emptyList(),
) {
    init {
        if (offset < 0) {
            throw CoreException(ErrorType.BAD_REQUEST, "offset 은 0 이상이어야 합니다.")
        }
        if (limit <= 0) {
            throw CoreException(ErrorType.BAD_REQUEST, "limit 은 1 이상이어야 합니다.")
        }
    }

    companion object {
        fun of(page: Int, size: Int, sort: List<SortKey> = emptyList()): PageQuery =
            PageQuery(offset = page.coerceAtLeast(0) * size, limit = size, sort = sort)
    }
}

data class SortKey(val property: String, val direction: SortDirection = SortDirection.ASC)

enum class SortDirection { ASC, DESC }
