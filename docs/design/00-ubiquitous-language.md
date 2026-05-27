# 00 — Ubiquitous Language (유비쿼터스 언어)

> 본 도메인의 **단일 어휘 사전**. 코드 / 테스트 / API / DB 컬럼 / 문서가 동일 용어로 정렬되어야 한다. DDD 의 Ubiquitous Language 원칙 — 비즈니스 ↔ 개발 ↔ DB 간 어휘 충돌을 어휘 변경의 단일 출처로 흡수.
>
> **사용 방식**:
> - 모든 doc (01 ~ 05) 가 본 사전을 전제로 한다. 새 용어 도입 / 기존 용어 의미 변경은 *반드시* 본 문서를 먼저 갱신.
> - 동의어 금지. 같은 개념을 다른 단어로 부르면 어디서든 본 사전의 용어로 환원.
> - 외부 산업 표준 어휘 (OTA / PMS / BAR / NRR / ARI 등 — 본 시스템 외 영역) 는 `05-domain-landscape.md §1` 이 단일 출처.
>
> **연관 문서**: `01-requirements.md` (요구사항·AC) / `02-sequence-diagrams.md` (협력 흐름) / `03-class-diagram.md` (객체 구조) / `04-erd.md` (영속성) / `05-domain-landscape.md` (외부 산업 어휘)

---

## §1 도메인 핵심 어휘

| 한글 | 영문 (코드 표기) | 정의 / 비고 |
|---|---|---|
| 숙소 | `Property` | 호텔 / 펜션 / 모텔 / 리조트 단위. 위치·편의시설·정책·이미지 갤러리 보유 |
| 숙소 카테고리 | `PropertyCategory` | `HOTEL` / `MOTEL` / `PENSION` / `RESORT` / `GUESTHOUSE` enum |
| 숙소 정책 | `PropertyPolicy` | 체크인·체크아웃 시각 / 취소 정책 / 흡연 / 반려동물 VO |
| 취소 정책 | `CancellationPolicy` | `PropertyPolicy` 의 일부 — 적용 로직은 결제 도메인 도입 단계 |
| 사용자 평점 | `Rating` | 0.00 ~ 5.00 — 리뷰 도메인 도입 전까지 placeholder |
| 공식 별 등급 | `StarRating` | 1 ~ 5 정수 — 공식 호텔 등급. `Rating` 과 의미 분리. NULL = 무등급 (펜션 / 게스트하우스) |
| 숙소 이미지 | `PropertyImage` | Property Aggregate 의 자식 entity. `is_main = TRUE` 0~1개 |
| 객실 타입 | `RoomType` | 한 숙소가 판매하는 객실 카테고리 (예: "스탠다드 더블", "오션뷰 스위트") |
| 인원 정보 | `GuestCount` | `baseGuests` (기준 인원) / `maxGuests` (최대 인원) VO |
| 침대 구성 | `BedConfig` | 침대 타입별 수량 맵 (예: `{DOUBLE: 1, SINGLE: 2}`) |
| 침대 타입 | `BedType` | `SINGLE` / `DOUBLE` / `QUEEN` / `KING` / `TWIN` enum |
| 일자별 재고 | `DailyRoomInventory` | `(roomTypeId, date)` 단위. 판매 가능 수량 보유 |
| 일자별 요금 | `DailyRoomRate` | `(roomTypeId, date)` 단위. 1박 요금 보유 |
| 찜 | `Wishlist` | 유저가 숙소를 찜한 기록. **숙소 단위 (객실 단위 X)**. 멱등 토글 |
| 예약 | `Reservation` | 체크인 ~ 체크아웃 기간 동안 특정 객실 타입을 점유하기로 한 계약 |
| 예약 상태 | `ReservationStatus` | §3 상태 머신 어휘 참조 |
| 투숙 기간 | `StayPeriod` | `(checkIn, checkOut)` 쌍 VO. `checkOut > checkIn`, `nights() <= MAX_NIGHTS` invariant |
| 체크인 / 체크아웃 | `checkIn` / `checkOut` | 투숙 시작 / 종료 일자 (`LocalDate`, KST 기준) |
| 숙박 일수 | `nights()` | `checkOut - checkIn` 일수 |
| 예약 대상 일자 | `datesToReserve()` | `[checkIn, checkOut)` 의 일자 리스트 — **체크아웃 당일 X** (호텔 표준) |
| 게스트 정보 | `GuestInfo` | 예약자 본인 정보 (이름 / 전화). 회원과 별개일 수 있음 (예약 대표자) |
| 전화번호 | `PhoneNumber` | E.164 또는 국내 표기 VO |
| 예약 스냅샷 | `PropertySnapshot` / `RoomTypeSnapshot` | 예약 시점의 숙소·객실 정보 *고정 사본* — 영수증 성격. 원본 변경 시 영향 없음 |
| 금액 | `Money` | KRW 원 단위 정수 (`long`). `amount >= 0` invariant. `currency` 미보유 (글로벌 확장 시 도입) |
| 사용자 식별자 | `LoginId` | 회원 도메인의 비즈니스 식별자 (string VO). 본 시스템은 `LoginId` 만 다룬다 — `users.id` (BIGINT) 는 영속성 인공 키 |
| 자원 소유자 | resource owner | 예약 / 찜의 `userId` 와 요청자의 `LoginId` 가 일치하는 유저 |

---

## §2 아키텍처 / DDD 어휘

| 용어 | 정의 / 본 시스템에서의 적용 |
|---|---|
| **Aggregate** | 일관성 경계 단위. 본 시스템: 5 Aggregate (Property / RoomType / Daily-Stock / Wishlist / Reservation) |
| **Aggregate Root (AR)** | Aggregate 의 진입점. 외부는 AR 또는 동급 Repository 를 통해서만 내부 접근 |
| **Entity** | 식별자 보유, 상태 변경 가능, 동일성 = ID (예: `PropertyImage`) |
| **Value Object (VO)** | 불변, 동일성 = 값 (예: `Money`, `StayPeriod`, `GuestCount`) |
| **Domain Service** | 상태 없음. 도메인 객체들의 협력 조정 (예: `ReservationService`, `ReservationPriceCalculator`) — Repository 미의존, 인자로만 협력 |
| **Repository** | 도메인이 정의한 인터페이스. 구현은 인프라 (DIP) |
| **Snapshot** | 외부 Aggregate 의 *현재 시점* 값을 자신의 Aggregate 안에 박제한 VO. 원본 변경에 불변 (예: `PropertySnapshot`) |
| **Domain Event** | Aggregate 상태 변화 후 발행되는 사실 (예: `ReservationCreated`). 본 시스템은 hook 위치만 정의, Outbox 미도입 |
| **Bounded Context (BC)** | 도메인 / 어휘 경계 단위. 본 시스템 = 단일 BC. OTA / Channel Manager 는 별도 BC 로 분리 예정 |
| **Application Layer** | 트랜잭션 경계 / 인가 / Repository 조립. 도메인 객체를 끌어쓴다 |
| **Domain Layer** | 비즈니스 규칙 / invariant. Application 패키지를 모른다 |
| **Persistence Layer** | Repository 구현 — JPA / MySQL 어댑터 |
| **Saga** | 다중 단계 트랜잭션의 보상 패턴. 결제 도메인 도입 시 적용 |

---

## §3 상태 머신 어휘 (Reservation)

| 상태 | 의미 | 진입 트리거 | 본 시스템 구현 여부 |
|---|---|---|---|
| `PENDING` | 생성 직후. 결제 대기 | `Reservation.create()` | ✅ |
| `CONFIRMED` | 결제 완료 | 결제 도메인의 성공 이벤트 → `Reservation.confirm()` | 모델 정의만 |
| `CHECKED_IN` | 투숙 시작 | PMS 연동 또는 어드민 수동 → `Reservation.checkIn()` | 모델 정의만 |
| `CHECKED_OUT` | 투숙 종료 | 체크아웃 시각 + 24h 배치 → `Reservation.checkOut()` | 모델 정의만 |
| `CANCELLED` | 취소 | 사용자 명시 취소 / 결제 실패 보상 → `Reservation.cancel()` | ✅ |
| `NO_SHOW` | 체크인 일자 노쇼 | 체크인 일자 자정 + grace period 배치 | enum 만 선반영 |

> 전이 규칙 / 매트릭스 / 사전·사후 조건은 `03-class-diagram.md §7.1 / §7.2` 가 단일 출처.

---

## §4 시스템 / 정책 어휘

| 용어 | 정의 |
|---|---|
| `X-Loopers-LoginId` | 외부 게이트웨이가 인증 후 부여하는 사용자 식별 헤더. 본 시스템은 이 헤더만 신뢰 |
| `Idempotency-Key` | 클라이언트가 부여하는 멱등 키. 결제 도메인 도입 시 강제 |
| `/api/v1` | 대고객 API |
| `/api-admin/v1` | 어드민 API. 인가는 외부 콘솔·게이트웨이 책임 (본 시스템은 식별만) |
| **본 시스템** | 시스템 경계 어휘 — 본 6개 doc 이 정의하는 숙박 커머스 백엔드 단일 서비스. 외부 도메인 (회원 / 결제 PG / 알림 / 검색 인프라) 의 반대. *시점 의미 없음* |
| **현 단계** | 시점 어휘 — 본 6개 doc 작성 시점의 결정. 결제·동시성·리뷰 도입 시 본 어휘로 표시된 결정이 재평가된다. *시스템 경계 의미 없음* |
| **MVP** | 제품 단계 어휘 — 도메인 모델 + 기본 CRUD + Atomic UPDATE 기반 동시성. 결제·인프라 합류 *전* 단계의 출하 가능 단위. *시점 / 시스템 경계 의미 없음* |
| **재평가 트리거** | 본 단계의 결정을 재검토하는 운영 임계 (예: "행 ≥ 10k", "P95 > 200ms"). '나중에 결정' 어휘 대체 |
| **Out of Scope** | 현 단계에서 의도적으로 제외. 도입 트리거는 `01 §12` 또는 `05 §9` 참조 |
| **선반영 (enum / 컬럼)** | 후속 변경 시 마이그레이션 비용 회피용 가드 — 값만 정의, 트리거는 후속 단계 |

---

## §5 도입하지 않는 어휘 (혼동 회피)

| 용어 | 본 시스템에서의 의미 / 회피 사유 |
|---|---|
| ~~Soft delete~~ | 본 시스템은 마스터 데이터에 `status` 컬럼 운영. 별도 `deleted_at` 컬럼은 컴플라이언스 요건 발생 시 도입 |
| ~~"본 라운드"~~ | 학습 / 단계 표현. 세 어휘는 **서로 다른 의미** — 혼용 금지 |
| ~~"합류"~~ | 모듈 / 도메인이 추가되는 것의 의인화. "도입 / 연동 / 통합" 으로 통일 |
| ~~Soft delete 별도 컬럼 (`deleted_at`)~~ | 트랜잭션 데이터 (예: `reservations`) 는 `cancelled_at` 같은 *상태 시각 컬럼* 으로 충분 |
| ~~ServiceImpl~~ | 본 시스템은 Service 클래스 자체가 구현 — `Impl` 접미사 금지 |
| ~~외부 회사명 본문 인용~~ | 본문에서 야놀자 / 여기어때 / Booking / Airbnb 등 회사명 직접 인용 금지. `05 §10` 부록에서만 |
