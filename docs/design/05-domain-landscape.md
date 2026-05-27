# 05 — 도메인 전반 / 산업 레퍼런스 / 확장 로드맵

> 본 문서는 숙박 커머스 도메인이 실제로 다뤄야 하는 기능 / 엔티티 / 패턴을 정리한다.
>
> 참조 표준 (외부 인용은 §10 부록):
> - OTA Connectivity (HTNG, OpenTravel Alliance) — `OTA_HotelAvailRQ/RS`, `OTA_HotelInvCountNotifRQ`
> - OWASP API Security Top 10, PCI-DSS v4.0
> - 국내외 OTA·PMS 운영 사례 (세부 출처 §10)
>
> **연관**: `00-ubiquitous-language.md` (본 시스템 도메인 어휘) — 본 문서의 §1 외부 산업 어휘와 분담
>
> **05 의 책임 범위**: 본 시스템 외부 영역 (OTA / PMS / Rate Plan / 결제 / 쿠폰 / 알림 / 리뷰 / 대실 / 다통화) 의 *산업 표준 어휘 + 도입 패턴 + 모델 가드 + 단계별 로드맵 + 후속 영역과의 호환성*. 01~04 에 흩어지지 않도록 **후속 확장의 단일 출처 (Single Source of Truth)** 역할.
>
> **문서 구조**:
> 1. **산업 표준 어휘** (§1) — OTA / Rate / Funnel / Yield
> 2. **후속 확장 엔티티** (§2) — Rate Plan / LOS / Hold / OTA / Coupon / Payment / Review / Outbox / Modification / Group
> 3. **동시성 / 결제 / 검색 / 운영** (§3 ~ §6) — 도입 패턴 비교
> 4. **한국 도메인 특이성** (§7) — 대실 / 본인인증 / 영수증 / PG
> 5. **모델 가드 + 로드맵** (§8 ~ §9) — 지금 둘 가드 / YAGNI / 단계별 도입
> 6. **참조 / 부록** (§10 + Appendix A) — 표준·규제 / 호환성 점검

---

## 1. 업계 표준 어휘 — 본 설계가 마주칠 영역의 이름들

### 1.1 OTA / PMS / Channel Manager

| 용어 | 풀어쓰기 | 역할 |
|---|---|---|
| **PMS** | Property Management System | 호텔 자체 운영 시스템. 객실 점유율 / 체크인·아웃 / 청소 상태. Opera, Mews, 야놀자 와이저 |
| **OTA** | Online Travel Agency | 외부 판매 채널. Booking, Expedia, 야놀자, 여기어때, Airbnb |
| **Channel Manager** | — | PMS ↔ 다중 OTA 의 ARI 양방향 동기 허브. 야놀자 클라우드, SiteMinder, Cloudbeds |
| **CRS** | Central Reservation System | 호텔 체인 중앙 예약 시스템. Marriott / Hilton 등 |
| **GDS** | Global Distribution System | 항공·호텔 통합 분배망. Amadeus, Sabre. 후순위 |

> **시사점**: 호텔 1곳이 동시에 여러 OTA 에 노출되며, 한 채널의 예약은 다른 채널의 재고를 줄여야 한다. **OTA 더블부킹** 의 주된 원인은 채널 간 동기화 지연이지, 단일 시스템의 동시성 문제가 아니다.

### 1.2 Rate / Inventory 어휘

| 용어 | 의미 |
|---|---|
| **BAR** (Best Available Rate) | 그날 그 객실 타입의 최저가. 다른 모든 요금의 기준점 |
| **NRR** (Non-Refundable Rate) | 환불 불가, 통상 BAR 대비 10~20% 저렴 |
| **Member Rate** | 회원 전용 요금 |
| **Package Rate** | 객실 + 부대(조식/스파/공항픽업) 묶음 |
| **LOS** (Length of Stay) | 투숙 일수 |
| **Min/Max LOS** | 특정 일자 도착 시 최소/최대 투숙 일수 제약 |
| **CTA** (Closed To Arrival) | 그날 도착(체크인) 불가 |
| **CTD** (Closed To Departure) | 그날 출발(체크아웃) 불가 |
| **Stop-sell** | 그날 판매 중단 (재고 0 과 다름 — 의도적 차단) |
| **Allotment** | OTA 에 할당된 재고 (계약 단위) |
| **Free-sell** | 호텔 전체 재고를 OTA 가 자유롭게 판매 |
| **Overbooking** | 의도된 초과 예약 (No-show 보전용. 항공·호텔이 적극 활용) |
| **ARI** | Availability + Rates + Inventory — 동기화 단위 |
| **OBP** (Open Booking Period) | 예약 가능 윈도우 (예: 365일 미래까지만) |

> **시사점**: 현 단계 ERD 는 `daily_room_inventories.{total_rooms, reserved_rooms}` 만 둔다. 후속 단계에서 `stop_sell` / `cta` / `ctd` / `min_los` 가 같은 키 위에 얹힌다 — `(room_type_id, date)` 키 설계가 흔들리지 않으면 모델 확장이 매끄럽다.

### 1.3 Yield Management / Dynamic Pricing

- **Yield**: 같은 객실을 "언제 / 어떤 채널에" 팔지의 최적화. 항공에서 시작
- **Smart Pricing** (Airbnb): 수요·계절·이벤트 기반 추천가
- **Price Recommendation** vs **Price Override**: 시스템 추천 ↔ 호스트 수용/거절
- **Rate Shopping**: 경쟁사 가격을 크롤링해서 자동 조정

> **시사점**: 현 단계 `daily_room_rates.price_per_night` 는 단일 컬럼. 후속 단계에서 "추천가 / 적용가 / 룰" 이 분리되면 별도 테이블로 떨어진다 — `daily_room_rates` 의 단순성을 지키면서 확장 가능 (§8 가드).

### 1.4 Funnel / 사용자 행동 어휘

| 단계 | KPI |
|---|---|
| Search Impression | 검색 결과 노출 수 |
| Property Detail View | 상세 진입 |
| Add to Wishlist | 찜 |
| Booking Form Start | 예약 폼 진입 |
| Booking Submit | 예약 요청 |
| Payment Success | 결제 성공 |
| Cancellation | 취소 |
| Check-in / Check-out | 실투숙 |
| Review | 후기 |

> **시사점**: 본 시스템은 `properties.wish_count` 만 노출. 본격적인 추천 정렬은 **클릭 / 체류 / 예약 전환** 까지 추적해야 한다. 이벤트 로깅 인프라를 함께 도입하는 것은 과도 — 분석용 이벤트 컬렉션은 별도 BC 라는 점만 인지.

---

## 2. 후속 확장 핵심 엔티티 — 모델 자리만 정렬

### 2.0 Property (숙소·호텔) 레벨 정보 — 현 단계에서 채워지지 않은 것

> **PROPERTIES 가 곧 "호텔 정보" 테이블**. ROOM_TYPES 가 객실 카테고리. 현 단계 ERD 의 `properties` 는 **검색·예약 동작의 최소치**만 담고 있고, 실무 호텔 데이터에서 일상적으로 다루는 항목 다수가 빠져 있다.

**현재 보유 (`04-erd.md §1`)**: `name`, `category`, `description`, `address`, `latitude` / `longitude`, `amenities`, `policy`, `main_image_url`, `rating`, `wish_count`.

**전형적으로 빠지는 항목 / 도입 시점**:

| 항목 | 영문 | 현 단계 처리 | 도입 시점 / 사유 |
|---|---|---|---|
| **이미지 갤러리** | `property_images` | **현 단계 추가** | 상세 페이지·검색 결과에 다수 이미지 필수. `main_image_url` 단일 컬럼만으로는 UI 가 빈약. §2.0.1 |
| **공식 별 등급** | `star_rating` (1~5, 정수) | **현 단계 추가** | 사용자 평점(`rating`)과 다른 의미. 검색 필터 (`5성 호텔만`) 로 빈번히 쓰임. §2.0.2 |
| **연락처** | `contact_phone`, `contact_email` | 어드민 기능 도입 | 어드민·CS 영역. 현 단계 어드민 Controller 미구현 |
| **사업자 정보** | `business_registration_number`, `business_name`, `representative_name` | 영수증·결제 도메인 도입 | 세금계산서 / 현금영수증 발급에 필수. 결제 도메인과 함께 |
| **부대시설** | `property_facilities` (별도 테이블) | 검색 인프라 도입 | `amenities` 는 태그 (`"WIFI"`, `"PARKING"`). 부대시설은 **단위 객체** (레스토랑·회의실·사우나) — 운영시간·예약·이미지 보유 |
| **주변 정보 / 교통** | `nearby_landmarks` JSON 또는 `property_landmarks` 테이블 | 검색 인프라 도입 | "강남역 도보 5분", "공항 셔틀 무료" 같은 텍스트 + 거리. 검색 랭킹 신호 |
| **체인 / 브랜드** | `brand_id` FK | 다중 운영자 진입 | "메리어트", "신라스테이" 등. 단일 운영자 가정 외 |
| **운영 시간** | `front_desk_hours`, `is_24h` | 정책 확장 | 모텔은 24시간이 일반, 펜션은 체크인 시간 제한 — `policy` JSON 확장으로 흡수 가능 |
| **사용 언어** | `languages_spoken` JSON | 글로벌 확장 | 외국인 고객 대응 |
| **테마 / 컨셉** | `themes` JSON (`COUPLE`, `FAMILY`, `BUSINESS`, `PET_FRIENDLY`) | 검색 인프라 도입 | 검색 필터 / 추천 신호. `amenities` 와 다른 축 |
| **공식 등급 평가일 / 인증** | `last_audited_at`, `certifications` | 미정 | 한국관광공사 등급, 친환경 인증 등 |

> 진단: "호텔 정보가 없다" 가 아니라 **"호텔 정보가 빈약하다"** 가 정확하다. 검색·예약의 동작 자체는 현재 컬럼으로 충분하지만, 사용자에게 노출되는 상세 페이지 품질은 위 항목들이 채워져야 한다. §2.0.1 / §2.0.2 만 현 단계에 추가하고 나머지는 각 도메인 도입 시 점진 추가.

#### 2.0.1 이미지 갤러리 — `property_images`

```sql
CREATE TABLE property_images (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    property_id  BIGINT NOT NULL,
    image_url    VARCHAR(500) NOT NULL,
    alt_text     VARCHAR(255) NULL,
    display_order INT NOT NULL DEFAULT 0,
    is_main      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_property_images FOREIGN KEY (property_id) REFERENCES properties(id) ON DELETE CASCADE,
    INDEX idx_property_images (property_id, display_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

- `is_main = TRUE` 행이 **0개 또는 1개** — 도메인 레벨 보장. 0개일 때는 `display_order` 0번을 대표로 사용.
- `properties.main_image_url` 컬럼은 **유지** — 검색 결과 응답에 join 비용 회피용 캐시. 갤러리 변경 시 동기 갱신.
- 이미지 자체는 CDN (S3/CloudFront 등). DB 는 URL 만.
- N+1 회피: 검색 결과는 `main_image_url` 만, 상세 페이지에서 갤러리 일괄 조회.

#### 2.0.2 공식 별 등급 — `properties.star_rating`

- 컬럼: `star_rating TINYINT NULL CHECK (star_rating BETWEEN 1 AND 5)` — `NULL` 은 무등급 (펜션·게스트하우스)
- **`rating` (사용자 평점) 과는 의미가 다름** — 명확히 분리
- 인덱스: `(city, star_rating)` 결합 — 도시 필터 + 등급 필터 빈번
- 검색 정렬에 `sort=star_rating_desc` 옵션 (P2 — 본 시스템은 컬럼만, 정렬은 미구현)

#### 2.0.3 빠진 항목들의 모델 보호 (가드)

| 후속 항목 | 현재 모델에서 깨질 위험 | 가드 |
|---|---|---|
| 이미지 갤러리 | `main_image_url` 동기화 누락 | 도메인 메서드 `Property.replaceMainImage(url)` 가 양쪽을 갱신 |
| 부대시설 (`property_facilities`) | `amenities` JSON 에 마구 섞일 위험 | `amenities` 는 **태그만**, 단위 시설은 별도 테이블이라는 설계 의도를 §1 어휘에 명시 |
| 체인 / 브랜드 | Property 가 단일 운영자 가정 | 운영자 별도 entity 미도입. `Property` 의 소유자 컬럼은 회원 도메인 결정에 의존 |
| 사업자 정보 | 영수증 스냅샷 누락 | 결제 도메인 도입 시 `Reservation.businessSnapshot` 추가 — 본 시스템은 보존 대상 없음 |

---

### 2.1 Rate Plan (요금 정책)

> 같은 객실 타입에 여러 요금 정책 (BAR / NRR / Member / Package).

```
Property
  └── RoomType
        └── RatePlan        (예: "스탠다드 BAR", "스탠다드 NRR -15%", "조식포함 패키지")
              └── DailyRoomRate  (rate_plan_id, date, price)
              └── RatePlanRestriction  (rate_plan_id, date, min_los, cta, ctd, stop_sell)
              └── CancellationPolicy  (1:1)
```

**현재 모델과의 충돌점**: 현 단계 `daily_room_rates` 의 PK 는 `(room_type_id, date)`. Rate Plan 도입 시 자연 키는 `(rate_plan_id, date)`.

**마이그레이션 시나리오 (`migration` prefix)**:
1. `rate_plans` 테이블 신설 (`id`, `room_type_id`, `name`, `policy_type`, `cancellation_policy_id`)
2. RoomType 1개당 "기본 Rate Plan" 1개 자동 생성 (백필)
3. `daily_room_rates.rate_plan_id` 추가, 기본 Rate Plan ID 로 백필
4. PK 를 `(rate_plan_id, date)` 로 전환
5. 검색 / 예약 흐름의 Rate 조회 시그니처 변경

> 본 시스템은 Rate Plan 을 도입하지 않는다. 다만 컬럼명을 `price_per_night` 으로 고정한 것은 향후 `rate_plan_id` 가 추가되어도 의미가 변하지 않는 명명이다 — `04-erd.md §6` 매핑과 정합.

### 2.2 LOS Restriction / CTA / CTD / Stop-sell

> "그날은 2박 이상부터", "그날 도착 안됨", "그날 판매 중단".

```sql
CREATE TABLE daily_rate_restrictions (
  rate_plan_id BIGINT NOT NULL,
  date         DATE   NOT NULL,
  min_los    TINYINT NULL,
  max_los    TINYINT NULL,
  cta        BOOLEAN NOT NULL DEFAULT FALSE,
  ctd        BOOLEAN NOT NULL DEFAULT FALSE,
  stop_sell  BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY (rate_plan_id, date)
);
```

**검색 영향**:
- `period.checkIn` 일자에 `cta = TRUE` → 후보 제외
- `period.checkOut` 일자에 `ctd = TRUE` → 후보 제외
- `period.nights() < min_los` → 후보 제외
- `stop_sell = TRUE` → 후보 제외

> 검색은 "available > 0" 만 본다. LOS / CTA / CTD / Stop-sell 도입 시 `Inventory.isReservable(period)` 가 검사를 흡수하도록 시그니처를 유지 — 호출자(Facade) 는 그대로 둔다.

### 2.3 Reservation Hold (TTL 점유)

> 사용자가 결제 페이지 진입 ~ 결제 완료까지 통상 **15분** 임시 점유.

**왜 필요한가?**
- 결제 단계에서 다른 사용자가 같은 재고를 가져가면 "결제는 성공했는데 재고는 없는" 최악의 상황 발생
- 데드라인 기반 점유 + 만료 시 자동 복원

**구현 패턴**:
- **DB**: `reservation_holds(id, room_type_id, date, login_id, expires_at)` + 백그라운드 expire job
- **Redis**: `SET hold:{room_type_id}:{date} {login_id} EX 900 NX`
- **Saga**: hold → payment → confirm (성공 시 hold 를 reservation 으로 승격)

**TTL 만으로는 불충분 — 명시적 Release 필수**:
- PG 결제창 이탈 / 망 취소 / 사용자 뒤로가기 / 클라이언트 타임아웃은 TTL (15분) 보다 훨씬 빠르게 발생
- TTL 만 의존하면 재고 회전율이 급락 — 인기 객실은 분 단위 손실
- **PG 콜백 (실패 / 취소) + 클라이언트 explicit cancel API** 로 즉시 release. TTL 은 마지막 안전망일 뿐
- Release 흐름은 보상 트랜잭션으로 모델링 (hold 차감 → 결제 실패 → hold 복원 + Outbox 이벤트 발행)

> 본 시스템의 `Reservation.PENDING` 은 "결제 대기" 의미인데 **시간 제한이 없다**. 결제 도메인 도입 시 `PENDING + holdExpiresAt` 또는 별도 `reservation_holds` 테이블로 분리한다. PENDING 진입과 동시에 일자별 재고를 차감하는 현재 흐름은 사실상 즉시 hold — 결제 도입 시 `expireIfNotConfirmedBy(holdExpiresAt)` 도메인 메서드 추가만으로 자연스럽게 잇는다.

### 2.4 OTA Channel / Channel Manager

```
properties
   ↓
channel_mappings (property_id, channel, external_property_id, contract_type, allotment_pct)
   ↓
ARI 동기 (incoming reservation, outgoing inventory update)
```

**연동 흐름**:
- **Outbound**: 우리 재고 변동 → OTA 들에게 inventory update push (HTNG `OTA_HotelInvCountNotifRQ`)
- **Inbound**: OTA 에서 들어온 예약 → 우리 시스템에서 reservation 생성 (`OTA_HotelResNotifRQ`)
- **양방향 잠금**: 같은 재고를 두 채널이 동시에 가져갈 때의 합의 알고리즘

> OTA 연동은 거의 항상 **별도 Bounded Context** 로 분리한다 — 외부 장애 격리, 프로토콜 상이, 데이터 볼륨이 본 코어 도메인과 다르기 때문. 본 시스템 모델에 미리 `channel_id` 컬럼을 두지 않고, **Reservation.source 의 다양화 가능성** 만 모델 의도에 남겨둔다. 분리된 BC 와의 통신은 비동기 이벤트(Kafka 등) 가 기본 (Eventual Consistency 수용).

### 2.5 Coupon / Promotion

```
coupons               (id, code, type, amount/percent, valid_from, valid_to, max_redemptions, max_per_user)
coupon_issues         (id, coupon_id, user_id, issued_at, used_at, used_reservation_id)
coupon_redemption_log (id, coupon_id, idempotency_key)
```

**핵심 위험**:
- **선착순 발급의 동시성**: Redis `INCR` + max 비교, DB row lock, 분산 락 — 인기 쿠폰은 트래픽 spike
- **사용 시 멱등성**: 같은 reservation 에 대한 중복 사용 방지 (UNIQUE 제약)
- **반환 흐름**: 예약 취소 시 쿠폰 복원 정책 (정책에 따라 복원 / 소멸)

> `ReservationPriceCalculator.totalPrice(rates)` 의 인자는 rate 만 유지한다. **`Coupon?` 인자 선반영 금지** — 쿠폰 도메인 도입 시 시그니처 변경 또는 별도 `Discount` 객체로 분리 (`03-class-diagram.md §7` 와 정합).

### 2.6 Payment

**필수 어휘**:

| 용어 | 의미 |
|---|---|
| **Auth** (Authorization) | 카드 한도 점유만, 실제 차감 X. 통상 7일 유효 |
| **Capture** | Auth 한 금액 실제 차감 |
| **Sale** | Auth + Capture 동시 (즉시 결제) |
| **Void** | Auth 취소 (Capture 전) |
| **Refund** | Capture 후 환불 (부분 환불 가능) |
| **3DS** | 3D-Secure 인증 (EU SCA, 한국 일부 카드 의무) |
| **Tokenization** | 카드 → PG 토큰. PCI-DSS 회피 |
| **Recurring** | 정기 결제용 빌링키 |

**숙박 도메인 특이성**:
- 호텔 전통: **Auth 만 + 체크인 시점 Capture** — No-show 시 위약금만 capture
- 한국 OTA 트렌드: 즉시 결제 (Sale) 가 일반적
- Pre-paid (즉시 결제) vs Pay-at-property (호텔 도착 후 결제) 양립

**Saga 패턴 (예약 + 결제)**:
```
1. createPendingReservation()      -- 재고 차감, status=PENDING (hold)
2. callPgAuth()                    -- 카드 점유
3. confirmReservation()            -- status → CONFIRMED, payment 연동
   ↓ 실패 시 (보상 트랜잭션)
   compensate: voidPgAuth() + releaseInventory() + status → CANCELLED
```

**부분 취소 / 부분 환불**:
- 연박 예약 (예: 3박) 중 일부 일자만 취소 — 일자 단위로 재고 복원 + 금액 재산정
- 그룹 예약 시 1인만 취소 — `BookingGroup` 단위로 분해
- 모델 영향: `Reservation` 의 `cancel()` 만으로 불충분 — `cancelPartial(dates: List<LocalDate>)` 또는 `Reservation` 분할 (split-and-replace) 필요. 도입 결정은 결제·환불 정책과 함께
- 환불 금액: 일자별 `DailyRoomRate` 단위 합산 + `CancellationPolicy` 적용 매트릭스

**정산 (Settlement)**:
- 결제 완료 ≠ 정산 완료. PG 사 정산 사이클(통상 D+2~D+7) + 숙소 정산 사이클(월 단위) 별도 라이프사이클
- 예약 상태 (`CHECKED_OUT`) 와 정산 트리거의 연결: `settlement_records (reservation_id, settled_amount, fee, net_to_property, settled_at)`
- 정산 후 환불은 PG 사의 별도 환불 API + 정산 보정 — 통상 본 시스템은 정산 도메인을 별도 BC 로 분리

> `Reservation.confirm()` 메서드는 본 시스템에 이미 정의됨 (PENDING → CONFIRMED). 결제 도메인 도입 시 외부 트리거(결제 성공 이벤트) 가 이 메서드를 호출한다. **모델 가드**: `confirm()` 본문에 결제 검증 로직을 넣지 않는다 — 도메인은 "결제됐다고 들었다" 만 처리한다.

### 2.7 Review / Rating

```
reviews (id, reservation_id UNIQUE, user_id, property_id,
         rating_overall, rating_cleanliness, rating_location, rating_value,
         content, created_at, status, helpful_count)
review_replies (id, review_id, replier_role, content, created_at)
```

**무결성 제약**:
- `reservation_id UNIQUE` → 한 예약당 1리뷰
- 도메인 검증: `reservation.status = CHECKED_OUT` 일 때만 작성 가능
- 어뷰징 방지: 같은 사용자가 같은 숙소에 짧은 시간 반복 작성 차단

**평점 집계**:
- 옵션 A: `properties.rating` 을 직접 갱신 (역정규화) — 빠른 조회, 갱신 비용
- 옵션 B: `view_property_rating` 으로 매번 계산 — 일관성, 조회 비용
- 통상 A + 비동기 재계산 (Outbox / 배치)

> `properties.rating` 은 placeholder 로 둔다. 리뷰 도메인 도입 시 **역정규화 + 비동기 재계산** 패턴으로 채운다 — Outbox 이벤트 트리거로 갱신.

### 2.8 Notification + Outbox

> 예약 확정 → SMS / Email / Push. **트랜잭션 안에서 외부 호출 금지** (롤백 시 알림이 살아남는 문제).

**Outbox 패턴**:
```
TX 시작
  INSERT INTO reservations (...)
  INSERT INTO outbox (event_type='RESERVATION_CONFIRMED', payload, ...)
TX 커밋
   ↓
Outbox poller (별도 프로세스)
   ↓
Kafka → consumer → SMS / Email 채널 발송
   ↓
outbox.published_at 업데이트
```

**왜 필요한가?**:
- DB 트랜잭션 ↔ 외부 호출의 **이중 쓰기 문제** 해결
- 외부 채널 장애 시 재시도가 자연스러움
- 이벤트 소싱 / CDC 의 출발점

> Outbox 패턴은 본 시스템에 적용하지 않는다 — 알림 채널 연동 시 도입. 도입 비용이 적지 않으나 **트랜잭션과 외부 호출의 분리** 자체가 도메인 안정성의 핵심.

### 2.9 Booking Modification (예약 변경)

> 일자 변경 / 인원 변경 / 객실 변경. 본 시스템은 cancel + 재예약으로 우회.

**복잡성**:
- 일자 변경: 기존 일자 복원 + 새 일자 차감 (단일 트랜잭션)
- 가격 차이 처리: 추가 결제 / 부분 환불 / 변경 수수료
- Rate Plan 변경 시 정책 재적용

> 본 시스템은 예약 **변경 미지원** — `cancel + 재예약` 우회로 충분하다. 변경 요구가 누적되면 별도 도메인 동작으로 도입.

### 2.10 Group Booking / Multi-room Booking

> 한 사람이 같은 객실 타입 여러 개 예약 / 다른 객실 타입 묶음 예약.

**모델 영향**:
- `Reservation` 1개 = 객실 1실 (현재) vs `Reservation` 1개 = 묶음 (확장)
- 통상 `BookingGroup (id) ─< Reservation` 의 1:N 으로 풀어냄

> 본 시스템은 **1예약 = 1객실** 가정으로 단순화한다. 그룹 예약은 별도 `BookingGroup` Aggregate 로 분리하면 현재 모델 영향 없음.

---

## 3. 동시성 — 더블부킹 방지 패턴 (동시성 단계 사전 검토)

도메인 모델 안정화 후 도입할 옵션 비교. WAS 환경에서는 인스턴스 1대라도 요청별 스레드 할당으로 race condition 이 발생하므로, 실제로는 **MVP 시점부터 최소한의 동시성 제어가 필요**하다 — `01-requirements.md §2` 와 정합.

### 3.1 Atomic UPDATE (권장 — 단일 RDB 환경의 1차 선택)
```sql
UPDATE daily_room_inventories
   SET reserved_rooms = reserved_rooms + 1
 WHERE room_type_id = ? AND date = ?
   AND reserved_rooms + 1 <= total_rooms;
-- affected rows = 0 이면 가용 0
```
- ✅ 행 락 자동, 음수 방지가 SQL 레벨에서 보장
- ✅ 별도 락 코드 / 재시도 코드 불필요
- ✅ DB CHECK 제약 (`reserved_rooms <= total_rooms`) 과 다중 방어
- ⚠ 다중 일자 묶음 처리 시 부분 실패 → 트랜잭션 롤백으로 일괄 되돌림 명확화 필수
- ⚠ 일자별 UPDATE 는 **일자 ASC 순서 보장** (데드락 회피)

### 3.2 DB UNIQUE 제약 (Strong Guard — 보조)
```sql
-- 한 사용자가 같은 일자 같은 객실에 중복 hold 못하게
CREATE UNIQUE INDEX uk_reservation_inventory_holds_active
  ON reservation_inventory_holds (room_type_id, date, status)
  WHERE status = 'HELD';
```
- ✅ 스키마 차원의 강제 — 락 코드보다 안전
- ✅ Atomic UPDATE 와 조합 시 더블부킹 사실상 0
- MySQL 의 partial index 미지원 시 application-level UNIQUE 으로 대체

### 3.3 비관적 락 (`SELECT ... FOR UPDATE`) — 데드락 주의
```kotlin
@Lock(LockModeType.PESSIMISTIC_WRITE)
fun findInventoriesForUpdate(roomTypeId: Long, dates: List<LocalDate>): List<DailyRoomInventory>
```
- ⚠ **다중 일자 락은 데드락 주범**. 5박 예약이 일자별로 다른 순서로 락 획득 시 즉시 데드락
- ⚠ 트랜잭션 길어지면 커넥션 풀 고갈 — 인기 객실 트래픽 spike 에 취약
- 도입 시 락 순서 (일자 ASC), 타임아웃, 재시도 정책 모두 명세 필수
- Atomic UPDATE 로 처리 가능한 경우 비관 락은 피한다

### 3.4 낙관적 락 (`@Version`)
- 충돌이 적은 워크로드에서 처리량 우수
- 본 도메인은 인기 객실의 동일 일자에 충돌이 잦아 재시도 비용이 크다 — Atomic UPDATE 가 더 적합
- 다른 도메인 (예: 사용자 프로필) 에서는 유효

### 3.5 Reservation Hold + TTL (Redis) — 결제 도입 시
```
SET hold:{room_type_id}:{date} {login_id} EX 900 NX
```
- ✅ 결제 흐름과 자연스럽게 결합 (15분 점유)
- ✅ 분산 환경에서 자연스러움
- ⚠ TTL 만 의존 금지 — 명시적 release 필수 (§2.3)
- ⚠ Redis ↔ DB 정합성: 결제 성공 시 Redis hold → DB 예약 승격의 멱등성 보장 필요

### 3.6 분산 락 (Redisson, Zookeeper)
- 다중 인스턴스에서 비관 락의 한계 보완
- 인프라 추가 비용 큼 — 통상 Atomic UPDATE + UNIQUE 제약으로 대체

### 3.7 비교 표

| 패턴 | 처리량 | 복잡도 | 더블부킹 차단 | 본 도메인 적합도 |
|---|---|---|---|---|
| **Atomic UPDATE** | 높음 | 낮음 | 강 | ★★★★★ (1차 선택) |
| **UNIQUE 제약** | 높음 | 낮음 | 강 | ★★★★★ (Atomic UPDATE 와 조합) |
| Hold + TTL | 중 | 중 | 강 | ★★★★ (결제 도입 시) |
| 낙관적 락 | 높음 | 중 | 중 (재시도) | ★★ (충돌 잦음) |
| 비관적 락 | 낮음 | 중 (락 순서 / 타임아웃) | 강 | ★★ (데드락·풀 고갈 위험) |
| 분산 락 | 중 | 높음 | 강 | ★ (인프라 추가, 대체재 존재) |

> **권고 경로**:
> 1. MVP 시점부터 **Atomic UPDATE + DB CHECK 제약**으로 시작 (단일 RDB / 단일 인스턴스)
> 2. 결제 도메인 도입 시 **Hold + TTL** 을 위에 얹는다 (명시적 release 흐름 포함)
> 3. 다중 인스턴스 / OTA 동기 단계에서 **Redis 기반 분산 hold** 또는 **이벤트 소싱** 검토

---

## 4. 결제 통합 패턴 (결제 도메인 도입 시 사전 검토)

### 4.1 Auth → Capture 분리
- 즉시 결제: Auth + Capture 동시 (`sale`)
- 호텔 표준: Auth 만, Capture 는 체크인 시점 — No-show 시 위약금만 capture

### 4.2 Idempotency Key
- PG 호출은 멱등 키 필수. `Idempotency-Key: <reservation_id>-<retry-count>`
- 우리 API 도 `POST /reservations` / `POST /reservations/{id}/cancel` 에 클라이언트 멱등키 강제 (결제 도메인 도입 시)
- 처리 결과 캐싱: `idempotency_records (key, response_hash, expires_at)`

### 4.3 환불 정책
- `CancellationPolicy.type` 값:
  - `FREE_UNTIL_DAYS_BEFORE` (체크인 N일 전까지 무료)
  - `NON_REFUNDABLE` (환불 불가)
  - `PARTIAL_REFUND` (단계별 환불 비율, 예: D-7 100%, D-3 50%, D-1 0%)
- 적용 시점: **취소 시각** 과 `checkIn` 의 차이 + 정책 매트릭스 → 환불액 산출
- **자정 기준 시간대 결정 명시 필수** — 호텔 현지 시간 vs 사용자 시간

### 4.4 No-show 처리
- 예약은 CONFIRMED 인데 체크인 일자에 입실 안한 경우
- 배치: 매일 자정 후 `check_in < today AND status = CONFIRMED` → `NO_SHOW` 전이
- 위약금 capture / 환불 0

### 4.5 부분 환불 / 분할 결제
- 그룹 예약 시 1인 취소 → 부분 환불
- 글로벌 OTA 사례: Long-stay 의 단계별 결제 (입실 시 + 매월)

> `ReservationStatus` 에 `NO_SHOW` 를 enum 으로 **선반영**한다 — 결제 도메인 도입 시 enum 마이그레이션 비용 0 (§8 가드).

---

## 5. 검색 / 추천 인프라

### 5.1 가용성 캐싱
- 검색 1회당 N 개 Property × M 일 inventory 조회 → 비용 큼
- 패턴: **검색용 read model** 별도 (Elasticsearch / Redis)
- 가용 일자별 hash 캐시: `availability:{room_type_id}:{yyyymm}` (월 단위 비트맵)
- TTL + 인벤토리 변경 이벤트로 invalidate

### 5.2 검색 정렬 / 추천
- `recommended` 정렬: 평점 + 찜 수 + CTR + 클릭 + 매출 가중합
- 개인화: 과거 예약 도시·가격대·편의시설 유사도 (LightFM, Neural CF)
- A/B 테스트 인프라: 가중치 변경 → 매출 영향 측정 (글로벌 OTA 의 핵심 경쟁력)

### 5.3 지리 검색
- 도시 코드 외에 **반경 검색**(중심 좌표 + km), **폴리곤 검색**(지도 영역)
- MySQL 8 의 `ST_Distance_Sphere` / SPATIAL INDEX 또는 Elasticsearch geo_point
- Geohash 기반 prefix 매칭으로 격자 검색

### 5.4 검색 확장 (Query Expansion)
- "강남" 검색 → "역삼", "삼성", "선릉" 자동 확장
- "비치 호텔" 검색 → 해변 근접 숙소 + amenity 매칭

> `properties.latitude / longitude` 컬럼은 미사용 placeholder 로 유지. 지리 검색 도입 시 인덱스(SPATIAL INDEX 또는 generated column 기반) 를 기능과 함께 추가한다 — 컬럼만 두는 비용은 NULL 허용으로 0 에 가깝다.

---

## 6. 운영 / 안정성 / 컴플라이언스

### 6.1 멱등성 / Idempotency Key
- API 게이트에서 `Idempotency-Key` 헤더 강제 (POST/DELETE)
- 처리 결과를 `idempotency_records (key, response_hash, expires_at)` 에 저장 → 같은 키 재요청 시 캐시된 응답
- 키 충돌 시 응답 비교 → 다르면 409

### 6.2 Rate Limit
- 검색 API: IP / 사용자별 분당 60회
- 예약 API: 사용자별 분당 5회 (스크립트 봇 방어)
- Redis token bucket / sliding window

### 6.3 Auditability
- 어드민 행동 로그 (누가 언제 어떤 객실 가격을 변경했나)
- `audit_logs (actor, action, resource, before, after, at)` 테이블
- 가격 변경 / 정책 변경 / 예약 강제 취소는 필수

### 6.4 Personal Data / 개인정보보호법 (KISA / GDPR)
- `reservations.guest_phone`, `users.phone` 같은 PII 컬럼은 KMS 키 기반 암호화
- 보유기간 정책 (예: 체크아웃 후 5년 — 전자상거래법)
- 삭제 요청(잊혀질 권리) 처리 — 영수증 보존 의무와의 균형, 통상 익명화

### 6.5 PCI-DSS
- 카드 정보를 우리 DB 에 저장 금지 → PG 토큰만
- 우리 시스템은 SAQ-A 스코프 유지 (저장·처리·전송 없음)
- 결제 페이지는 PG iframe / hosted page 권장

### 6.6 SLO / SLA
| 지표 | 목표 | 측정 |
|---|---|---|
| 검색 P95 | < 500ms | APM (Datadog/NewRelic) |
| 단건 조회 P95 | < 200ms | APM |
| 예약 생성 P99 | < 2s (PG 호출 포함) | APM |
| 가용률 | 99.9% (월 다운타임 ≤ 43분) | 헬스체크 + 외부 모니터 |
| MTTR | < 30분 | Incident 회고 |

### 6.7 관측 (Observability)
- Logs (구조화 JSON, 트레이스 ID 포함)
- Metrics (Prometheus + Grafana)
- Tracing (OpenTelemetry)
- 핵심 비즈니스 메트릭: 분당 예약 수, 결제 성공률, 검색 latency 분포

> 본 문서는 SLO 정의까지만 다룬다. 측정 도구 (APM / Prometheus / OpenTelemetry) 도입은 관측 인프라 도입 단계의 산출물.

---

## 7. 한국 숙박 도메인 특이성

### 7.1 대실 / 숙박 (모텔 / 비즈니스 호텔)
- **대실** (Day-use): 시간 단위 (3~5시간). 통상 점심 12시 ~ 저녁 8시 슬롯
- **숙박** (Overnight): 1박 단위
- 모델 영향: `Reservation` 의 단위가 "박" 만으로 충분치 않음 — `StayPeriod` 가 시간 정보까지 보유 필요
- 국내 OTA 의 모텔 카테고리 비중이 크기 때문에 실무에서 무시할 수 없는 영역

### 7.2 본인인증 / 청소년 출입 제한
- 모텔 등 일부 업태는 청소년 출입 제한 (청소년보호법)
- 회원가입 시 본인인증 (PASS / 통신사 / 카드사) 도입
- 예약 시점에 성인 인증 상태 확인

### 7.3 영수증 / 세금
- 부가세 (10%) 포함 표시가 일반적 — `daily_room_rates.price_per_night` 가 부가세 포함값인지 명시 필수
- 봉사료 (Service Charge) — 일부 호텔 별도 표기
- 현금영수증 / 세금계산서 발급 (B2B / 출장)

### 7.4 광고 / Featured 노출
- 검색 결과에 유료 노출 슬롯 (광고 마케팅)
- 현 단계 미고려. 향후 정렬 시 광고 슬롯이 인터리브되는 구조로 진화할 수 있음을 인지

### 7.5 한국 PG 특이성
- **KCP / Toss Payments / 이니시스**: 표준 PG. 일반 카드 결제
- **카카오페이 / 네이버페이 / 페이코**: 간편결제 — 별도 토큰 / 다른 흐름
- **3DS** 가 일부 카드에서 필수 (마스터카드/비자 SCA 정책)

> **대실은 도메인 패러다임을 흔드는 요구**다. 현재 `StayPeriod(checkIn: LocalDate, checkOut: LocalDate)` 는 시간 정보가 없다. 대실 도입은 단순 VO 확장으로 끝나지 않는다 — 마감 시간 / 연장 과금 / 청소 시간(Turn-around) 까지 time-slot 단위로 재고를 관리해야 한다. 본 시스템은 **현재 스코프에서 대실 수용 불가**, 도입 결정 시 별도 BC 또는 `DayUsePeriod` 모델 신설을 함께 검토한다.

---

## 8. 모델 보호 — 지금 결정해야 할 것 (가드)

> 후속 확장이 들어와도 현재 모델이 깨지지 않게 **지금** 둘 결정. "안 만든다" 와 "보호한다" 는 다르다.

### 8.1 지금 둘 가드 (저비용 선반영)

| 지점 | 가드 | 이유 |
|---|---|---|
| `ReservationStatus.NO_SHOW` | **enum 에 선반영**, 전이는 정의만 (`CONFIRMED → NO_SHOW`) | 결제 도메인 도입 시 자연스럽게 이어짐. 추가 비용 거의 0, enum 마이그레이션 회피 |
| `daily_room_rates.price_per_night` | **컬럼명 유지** ("per night" 의미 유지) | Rate Plan 도입 시 `rate_plan_id` 만 추가하면 됨 |
| Reservation 의 `roomTypeId` 를 스냅샷과 별도로 보유 | **유지** (`03-class-diagram.md §4`) | 어드민 통계·OTA 동기에 필수 |
| `properties.latitude/longitude` 컬럼 | **유지 (NULL 허용)**, 인덱스는 미생성 | 지리 검색 도입 시 인덱스만 추가 |
| `properties.policy` JSON 컬럼 | **VO 로 모델링**, 메서드는 추가하지 말 것 | 정책 적용 로직은 결제 도메인 도입 후 |

### 8.2 지금 만들지 말 것 (YAGNI)

| 지점 | 결정 | 이유 |
|---|---|---|
| `Money.currency` 필드 | **만들지 말 것** (KRW only) | 다국적은 글로벌 확장 시. 미리 두면 직렬화·DB 컬럼만 늘어남 |
| `ReservationPriceCalculator(rates, coupons?)` | **인자 미리 넣지 말 것** | 쿠폰 도메인 도입 시 시그니처 변경 — YAGNI |
| `Reservation.confirm()` 안의 결제 검증 | **넣지 말 것** | 결제는 외부 트리거. 도메인은 "들었다" 만 처리 |
| `properties.rating` 의 직접 계산 | **placeholder 유지** | 리뷰 도메인 도입 시 비동기 재계산으로 들어옴 |
| `StayPeriod` 의 시간 정보 | **추가하지 말 것** (일자만) | 대실 도입 시 별도 `DayUsePeriod` VO |
| `outbox` 테이블 | **만들지 말 것** | 알림 채널 연동 시 도입 |
| inventory 의 `version` 컬럼 | **만들지 말 것** | 동시성 단계에서 마이그레이션 |
| `channel_id` / OTA 컬럼 | **만들지 말 것** | OTA 연동은 별도 BC. 컬럼 추가는 저비용 |
| `idempotency_records` 테이블 | **만들지 말 것** | 결제 도메인 도입 시 도입 (Idempotency 가 그때 본격 의미) |
| Rate Plan 별도 테이블 | **만들지 말 것** | 단일 BAR 가정으로 단순함 유지 |

### 8.3 결정 위치 (어디에 가드를 적는가)

- **enum 추가** (NO_SHOW): `03-class-diagram.md §4` ReservationStatus
- **컬럼명·키 유지**: `04-erd.md §6` 매핑 + 본 문서 §8 정렬
- **메서드 시그니처 보호**: `03-class-diagram.md §6` "만들지 않는 도메인 서비스" + 본 문서 §8

---

## 9. 도입 우선순위 (단계별 로드맵)

| 단계 | 영역 | 핵심 산출물 | 선행 |
|---|---|---|---|
| **동시성** | 동시성 / 성능 | Atomic UPDATE 또는 비관적 락, DB CHECK + UNIQUE, 검색 batch 조회, 인덱스 튜닝 | — |
| **동시성** | 관측 (Observability) | 구조화 로그, APM, 핵심 비즈니스 메트릭 | — |
| **결제 도메인 도입** | 결제 + Hold | PG 토큰화, Auth/Capture 분리, Reservation Hold + TTL, 결제 Saga | 동시성 |
| **결제 도메인 도입** | Idempotency | 클라이언트 Idempotency-Key 강제, 처리 결과 캐싱 | 결제 |
| **정책·알림 채널 연동** | 쿠폰 + 정책 | Coupon, CancellationPolicy 적용, NO_SHOW 배치 | 결제 |
| **정책·알림 채널 연동** | Outbox + 알림 | Outbox 테이블, SMS / Email 비동기 발송 | 결제 |
| **리뷰·다중 요금** | 리뷰 + 평점 | Review, properties.rating 비동기 재계산 | 결제 (CHECKED_OUT 흐름) |
| **리뷰·다중 요금** | Rate Plan | BAR / NRR / Member 분리, LOS 제약 | 인덱스 튜닝 |
| **검색 인프라 도입** | 검색 인프라 | Elasticsearch / Redis 캐시, 추천 정렬, A/B 테스트 | Rate Plan |
| **별도 BC 신설** | OTA / Channel | Channel Manager 별도 BC. 본 시스템은 ARI 동기 API 만 노출 | Rate Plan |
| **확장 / 글로벌** | 대실 / 시간단위 | `DayUsePeriod` VO, 시간 단위 inventory | — |
| **확장 / 글로벌** | 다국적 / 다통화 | `Money.currency`, FX | — |

> **각 단계 Definition of Done**: `verify-architecture` PASS + `verify-tests` PASS + 카나리 / 프로덕션 단계별 롤아웃 (관측·배포 인프라 전제).

---

## 10. 참조 표준 / 외부 자료

### 10.1 표준 / 규제

| 출처 | 키 토픽 |
|---|---|
| HTNG (htng.org) | 호텔 표준 메시지 (`OTA_HotelAvailRQ/RS`, `OTA_HotelInvCountNotifRQ`) |
| OpenTravel Alliance | OTA XML 표준 |
| OWASP API Security Top 10 | API 보안 (인증 / 인가 / Rate limit / Mass assignment) |
| PCI-DSS v4.0 (pcisecuritystandards.org) | 카드 데이터 처리 표준 |
| 청소년보호법 / 전자상거래법 / 개인정보보호법 (한국) | 도메인 규제 |

### 10.2 운영 사례 참고 (외부 블로그 / 컨퍼런스 자료)

> 본 문서의 패턴 결정에 영향을 준 산업 사례. 본문에서는 익명화하여 인용한다.

| 영역 | 참고 가능 자료 |
|---|---|
| OTA Connectivity / ARI / PMS 통합 | 야놀자 클라우드 (cloud.yanolja.com) |
| MSA 전환, Kafka, 동시성 제어, MySQL 샤딩 | 여기어때 기술블로그 (techblog.gccompany.co.kr) |
| A/B 테스트, 검색 랭킹, 가용성 캐시 | Booking.com Engineering |
| Smart Pricing, Trust & Safety, 결제 분할 | Airbnb Engineering |
| OTA, Distribution, ARI | Expedia Group OneHub |

---

## Appendix A — 현재 모델과 후속 영역의 호환성 점검

> 본 문서가 정렬한 후속 확장이 들어올 때 **현재 모델이 어디서 깨지는가** 를 점검.

| 후속 영역 | 현재 모델의 위험 지점 | 회복 비용 | 가드 위치 |
|---|---|---|---|
| Rate Plan | `daily_room_rates` PK 변경 (`(room_type_id, date)` → `(rate_plan_id, date)`) | **높음** — 라이브 서비스에서 PK 전환은 무중단 마이그레이션 + 양방향 동기화 + 검색 / 예약 쿼리 전면 수정. 대안: 기본 Rate Plan 1개 자동 생성 + `rate_plan_id` 컬럼 nullable 추가 + 점진 전환 | §2.1 |
| Hold + TTL | `Reservation.PENDING` 시간 제한 부재 | 중 — 컬럼 추가 + expire job + 명시 release API | §2.3 |
| 결제 Saga | `Reservation.confirm()` 의 외부 의존 | 낮음 — 메서드 호출자 추가 (도메인 메서드 변경 없음) | §2.6, §8.2 |
| 쿠폰 | `ReservationPriceCalculator` 시그니처 | 낮음 — 메서드 시그니처 변경 1회 | §2.5 |
| 리뷰 | `properties.rating` 갱신 흐름 | 중 — Outbox + 비동기 재계산 도입 (인프라 신설 동반) | §2.7 |
| 알림 | 트랜잭션 ↔ 외부 호출 분리 | 중 — Outbox 도입 (Outbox poller / 메시지 브로커 인프라) | §2.8 |
| OTA | `Reservation.source` 분리 필요 | **높음** — 별도 BC 신설 + 메시지 브로커 + Eventual Consistency 수용 | §2.4 |
| 대실 | `StayPeriod` 의 시간 부재 | **높음** — 재고 모델을 time-slot 단위로 재설계. 별도 BC 신설이 더 안전 | §7.1, §8.2 |
| 다통화 | `Money` 의 currency 부재 | 중 — 컬럼 + 직렬화 + 모든 합산 흐름의 currency 일치성 검증 | §8.2 |
| NO_SHOW | `ReservationStatus` enum 부재 | **0** — 본 시스템은 enum 으로 선반영 | **§8.1** |

> 회복 비용 "낮음 / 중" 항목은 정상 변경 비용으로 감내 가능. **"높음" 으로 표시된 Rate Plan / OTA / 대실** 은 모델 자체의 큰 가정을 흔든다 — 도입 결정 시 별도 마이그레이션 RFC 와 점진 전환 계획이 필수.
>
> 모델이 후속 변경에 깨지지 않는 핵심 이유는 **§8.1 의 가드를 지금 두는 것** + **§8.2 의 YAGNI 를 지금 지키는 것**.
