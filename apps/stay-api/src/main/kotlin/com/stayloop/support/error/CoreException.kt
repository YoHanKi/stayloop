package com.stayloop.support.error

/**
 * 도메인·인프라 모두에서 사용하는 단일 예외 타입.
 *
 * @property errorType    HTTP 상태/에러 코드/디폴트 메시지를 보유한 enum.
 * @property customMessage **클라이언트에 노출되는 메시지**. 외부 입력에 영향받는 상세
 *                          (Jackson 파싱 실패 메시지 등) 을 그대로 넣지 말 것 — 정보 노출 위험.
 *                          내부 디버깅 정보는 `cause` 로 보존하고 로그에서 확인.
 * @param cause           원인 예외 (생성자 매개변수 — `val/var` 없음). 부모 `Throwable.cause` 로 전달.
 */
class CoreException(
    val errorType: ErrorType,
    val customMessage: String? = null,
    cause: Throwable? = null,
) : RuntimeException(customMessage ?: errorType.message, cause)
