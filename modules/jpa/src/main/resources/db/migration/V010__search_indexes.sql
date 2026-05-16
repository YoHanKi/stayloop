-- week5 PR1 D-1 — 검색 worst case 4 인덱스 추가.
-- 동기 근거 (`docs/plan/week5-b.md §17` L3 EXPLAIN 박제):
--   - `daily_room_inventories.PK = (date, room_type_id)` 가 검색 패턴 (`room_type_id=? AND date BETWEEN ...`) 과
--     역순이라 5일 조회가 158_690 행 range scan. 17ms × ≈100 roomType call 이 single search 의 worst 부담.
--   - `daily_room_rates` 동일.
--   - `properties` 의 `(city, wish_count)` / `(city, rating)` 복합 인덱스가 PR1 D-6 의 sort 4종 활성화 + 도시 prefix
--     range scan 정합.
--
-- MySQL 8.0 descending index 사용 — wish_count DESC / rating DESC 정렬을 filesort 없이 prefix scan 으로 처리.
-- 5.7 까지는 ASC 만 가능하고 옵티마이저가 양방향 처리했지만, 8.0 의 *진짜 descending B-Tree* 가 filesort 를 제거.
--
-- 본 V010 머지 후 PR1 Phase M 의 IndexComparisonRunner 가 (a) 인덱스 없음 / (b) 단일 (city) 만 / (c) 역순 박제
-- 와 (D-1 채택) 의 게인 비교 측정. 반증 가드 (5 배 게인) 미통과 시 GR-3 절차.

CREATE INDEX idx_daily_room_inventories_room_type_date
    ON daily_room_inventories (room_type_id, date);

CREATE INDEX idx_daily_room_rates_room_type_date
    ON daily_room_rates (room_type_id, date);

CREATE INDEX idx_properties_city_wish_count
    ON properties (city, wish_count DESC);

CREATE INDEX idx_properties_city_rating
    ON properties (city, rating DESC);
