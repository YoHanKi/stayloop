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
 * **`size` 상한 = `MAX_PAGE_SIZE`** — 무제한 페이지 요청으로 인한 DB 부하 / 메모리 폭주 차단
 * (verify-code §17/§18 가드). 외부 입력이 곧장 SQL `LIMIT` 으로 흐르는 길에 있는 VO 이므로
 * 도메인 레벨에서 한계를 둔다.
 *
 * **`page * size` Int overflow 차단** — `page > Int.MAX_VALUE / size` 면 BAD_REQUEST 로 거절.
 * `offset` 이 `Int` 로 노출되어 Spring Data `PageRequest.of(int, int)` 와 정합되므로
 * Long 으로 확장하기보다 입력 단계에서 거절하는 편이 운영-검증 비용이 작다.
 * KDoc 의 "차단" 약속이 실제 가드와 *일치* 해야 한다 (verify-code §12/§19-B — 문서-동작 정합).
 *
 * @property page  0 이상의 페이지 인덱스. `page <= Int.MAX_VALUE / size` 도 함께 만족해야 함
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
        // page * size 가 Int.MAX_VALUE 를 넘으면 offset 이 음수 / 잘못된 값으로 wrap-around.
        // size 가 1 이상이므로 0 div 위험 없음.
        if (page > Int.MAX_VALUE / size) {
            throw CoreException(
                ErrorType.BAD_REQUEST,
                "page * size 가 Int 한계를 초과합니다 (page=$page, size=$size).",
            )
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
         * `page` 는 추가로 `Int.MAX_VALUE / size` 까지 클램프해 `init` 의 overflow 가드를 만족시킨다 —
         * 정규화 팩토리가 overflow 케이스에서 throw 하면 호출자가 `init` 와 같은 부담을 지므로,
         * "정규화" 의 의도와 어긋난다 (verify-code §8 가드).
         */
        fun of(page: Int, size: Int, sort: List<SortKey> = emptyList()): PageQuery {
            val normalizedSize = size.coerceIn(1, MAX_PAGE_SIZE)
            val maxPage = Int.MAX_VALUE / normalizedSize
            val normalizedPage = page.coerceIn(0, maxPage)
            return PageQuery(page = normalizedPage, size = normalizedSize, sort = sort)
        }
    }
}

data class SortKey(val property: String, val direction: SortDirection = SortDirection.ASC)

enum class SortDirection { ASC, DESC }
