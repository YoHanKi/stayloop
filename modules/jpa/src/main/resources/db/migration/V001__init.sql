-- week5 PR0 0-1 — JPA `ddl-auto: create` 가 만들던 schema 를 Flyway 마이그레이션으로 박제.
-- 본 V001 은 `apps/stay-api/src/test/.../migration/SchemaExportRunner` 가 덤프한 결과를 정리한 것으로,
-- Hibernate dialect (MySQL 8) 가 entity 들의 @Entity / @Table / @Index / @UniqueConstraint 어노테이션을
-- 해석해 생성한 DDL 그대로다. 본 파일 이후의 schema 변경은 V010+ 마이그레이션으로만 적용한다.

CREATE TABLE coupon_issues (
    id BIGINT NOT NULL AUTO_INCREMENT,
    template_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    status ENUM('AVAILABLE', 'EXPIRED', 'USED') NOT NULL,
    issued_at DATETIME(6) NOT NULL,
    used_at DATETIME(6),
    used_reservation_id BIGINT,
    version BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_coupon_issues_used_reservation_id UNIQUE (used_reservation_id)
) ENGINE = InnoDB;

CREATE TABLE coupon_templates (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(50) NOT NULL,
    coupon_name VARCHAR(100) NOT NULL,
    discount_type ENUM('FIXED', 'RATE') NOT NULL,
    discount_raw_value BIGINT NOT NULL,
    min_order_amount BIGINT,
    expired_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_coupon_templates_code UNIQUE (code)
) ENGINE = InnoDB;

CREATE TABLE daily_room_inventories (
    room_type_id BIGINT NOT NULL,
    date DATE NOT NULL,
    total_rooms INTEGER NOT NULL,
    reserved_rooms INTEGER NOT NULL,
    PRIMARY KEY (date, room_type_id)
) ENGINE = InnoDB;

CREATE TABLE daily_room_rates (
    room_type_id BIGINT NOT NULL,
    date DATE NOT NULL,
    price_per_night BIGINT NOT NULL,
    PRIMARY KEY (date, room_type_id)
) ENGINE = InnoDB;

CREATE TABLE properties (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    category ENUM('GUESTHOUSE', 'HOTEL', 'MOTEL', 'PENSION', 'RESORT') NOT NULL,
    description TEXT,
    city VARCHAR(20) NOT NULL,
    address VARCHAR(255) NOT NULL,
    latitude DECIMAL(9, 6),
    longitude DECIMAL(9, 6),
    amenities JSON NOT NULL,
    policy JSON NOT NULL,
    main_image_url VARCHAR(500),
    star_rating INTEGER,
    rating DECIMAL(3, 2) NOT NULL,
    wish_count INTEGER NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6),
    PRIMARY KEY (id),
    INDEX idx_properties_city (city),
    INDEX idx_properties_rating (rating),
    INDEX idx_properties_wish_count (wish_count)
) ENGINE = InnoDB;

CREATE TABLE property_images (
    id BIGINT NOT NULL AUTO_INCREMENT,
    property_id BIGINT NOT NULL,
    image_url VARCHAR(500) NOT NULL,
    alt_text VARCHAR(255),
    display_order INTEGER NOT NULL,
    is_main BIT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6),
    PRIMARY KEY (id),
    INDEX idx_property_images (property_id, display_order),
    CONSTRAINT fk_property_images_property_id FOREIGN KEY (property_id) REFERENCES properties (id)
) ENGINE = InnoDB;

CREATE TABLE reservations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_login_id VARCHAR(20) NOT NULL,
    property_id BIGINT NOT NULL,
    room_type_id BIGINT NOT NULL,
    check_in DATE NOT NULL,
    check_out DATE NOT NULL,
    guest_count INTEGER NOT NULL,
    max_guests INTEGER NOT NULL,
    guest_name VARCHAR(50) NOT NULL,
    guest_phone_number VARCHAR(20) NOT NULL,
    property_name VARCHAR(100) NOT NULL,
    property_address VARCHAR(255) NOT NULL,
    property_policy VARCHAR(1000),
    room_type_name VARCHAR(100) NOT NULL,
    coupon_id BIGINT,
    coupon_code VARCHAR(50),
    coupon_name VARCHAR(100),
    coupon_discount_type ENUM('FIXED', 'RATE'),
    price_before_discount BIGINT NOT NULL,
    discount_amount BIGINT NOT NULL,
    total_price BIGINT NOT NULL,
    status ENUM('CANCELLED', 'CHECKED_IN', 'CHECKED_OUT', 'CONFIRMED', 'NO_SHOW', 'PENDING') NOT NULL,
    cancelled_at DATETIME(6),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6),
    PRIMARY KEY (id)
) ENGINE = InnoDB;

CREATE TABLE room_types (
    id BIGINT NOT NULL AUTO_INCREMENT,
    property_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    base_guests INTEGER NOT NULL,
    max_guests INTEGER NOT NULL,
    bed_config JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6),
    PRIMARY KEY (id),
    INDEX idx_room_types_property_id (property_id)
) ENGINE = InnoDB;

CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    login_id VARCHAR(20) NOT NULL,
    password VARCHAR(100) NOT NULL,
    name VARCHAR(50) NOT NULL,
    birth_date DATE NOT NULL,
    email VARCHAR(100) NOT NULL,
    phone_number VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_users_login_id UNIQUE (login_id)
) ENGINE = InnoDB;

CREATE TABLE wishlists (
    user_id BIGINT NOT NULL,
    property_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (property_id, user_id)
) ENGINE = InnoDB;
