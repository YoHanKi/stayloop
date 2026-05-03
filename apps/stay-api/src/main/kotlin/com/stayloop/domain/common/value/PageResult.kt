package com.stayloop.domain.common.value

/**
 * 페이지 응답 VO. Spring Data `Page` 와 격리.
 *
 * @property content 현재 페이지의 항목들
 * @property total   조건에 매칭되는 전체 행 수 (페이지 크기와 무관)
 */
data class PageResult<T>(
    val content: List<T>,
    val total: Long,
)
