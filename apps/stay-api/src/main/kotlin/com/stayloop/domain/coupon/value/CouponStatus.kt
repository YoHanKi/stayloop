package com.stayloop.domain.coupon.value

/** 발급 쿠폰 상태. AVAILABLE → USED 단방향 전이(이번 라운드). 만료·취소는 후속(week5~6). */
enum class CouponStatus { AVAILABLE, USED }
