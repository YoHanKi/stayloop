package com.stayloop.domain.common.value

import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType

/**
 * 도메인이 영속성 레이어와 무관하게 페이지 요청을 표현하는 VO.
 * Spring Data 의 `Pageable` 을 도메인이 알지 않도록 — 변환은 Facade / RepositoryImpl 책임.
 *
 * **`page` 기반 (0부터 시작)** — `offset` 직접 노출 시 limit 의 배수가 아닐 때
 * Spring Data `PageRequest.of(offset/limit, ...)` 변환이 어긋나는 사고 방지 (Copilot #6 가드).
 *
 * **`size` 상한 = `MAX_PAGE_SIZE`** — 무제한 페이지 요청으로 인한 DB 부하 / 메모리 폭주 / `page * size`
 * Int overflow 차단 (verify-code §17/§18 가드). 외부 입력이 곧장 SQL `LIMIT` 으로 흐르는 길에 있는 VO 이므로
 * 도메인 레벨에서 한계를 둔다.
 *
 * @property page  0 이상의 페이지 인덱스
 * @property size  1 이상 `MAX_PAGE_SIZE` 이하의 페이지 크기
 * @property sort  정렬 키 + 방향 목록 (도메인 어휘. 빈 리스트면 기본 정렬)
 */
data class PageQuery(
    val page: Int,
    val size: Int,
    val sort: List<SortKey> = emptyList(),
) {
    init {
        if (page < 0) {
            throw CoreException(ErrorType.BAD_REQUEST, "page 는 0 이상이어야 합니다.")
        }
        if (size <= 0) {
            throw CoreException(ErrorType.BAD_REQUEST, "size 는 1 이상이어야 합니다.")
        }
        if (size > MAX_PAGE_SIZE) {
            throw CoreException(ErrorType.BAD_REQUEST, "size 는 $MAX_PAGE_SIZE 이하여야 합니다.")
        }
    }

    /**
     * 0 기반 시작 인덱스. RepositoryImpl 가 메모리/SQL OFFSET 으로 변환할 때 사용.
     */
    val offset: Int get() = page * size

    /**
     * 페이지 크기 한계. RepositoryImpl 가 LIMIT 으로 변환할 때 사용.
     */
    val limit: Int get() = size

    companion object {
        /**
         * 한 페이지에 허용되는 최대 행 수. 외부 입력이 그대로 SQL LIMIT 으로 흐르므로
         * 도메인 레벨 상한이 필요 — 100 은 list/grid UX 한 화면 분량을 충분히 덮는 합리적 기본값.
         */
        const val MAX_PAGE_SIZE: Int = 100

        /**
         * 외부 입력 정규화 팩토리. `page` 음수는 0, `size` 는 \[1, MAX_PAGE_SIZE] 범위로 클램프.
         * 정책을 page/size 양 측 모두에 적용해 비대칭을 없앤다 (verify-code §8 가드).
         */
        fun of(page: Int, size: Int, sort: List<SortKey> = emptyList()): PageQuery =
            PageQuery(
                page = page.coerceAtLeast(0),
                size = size.coerceIn(1, MAX_PAGE_SIZE),
                sort = sort,
            )
    }
}

data class SortKey(val property: String, val direction: SortDirection = SortDirection.ASC)

enum class SortDirection { ASC, DESC }
