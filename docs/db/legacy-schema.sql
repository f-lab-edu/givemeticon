-- givemeticon 레거시 테이블 DDL (MySQL 8.0, InnoDB, utf8mb4)
--
-- Flyway(src/main/resources/db/migration)는 아래 14개 테이블이 이미 있다고 가정한다.
-- (notification/coupon/item_favorite/item_favorite_meta 에 ALTER·INDEX 를 추가하는 마이그레이션이 있다.)
-- 빈 스키마에서는 앱을 띄우기 전에 이 파일을 먼저 한 번 실행한다.
--
--   mysql -u <user> -p givemeticon < docs/db/legacy-schema.sql
--
-- 그 다음 앱을 기동하면 spring.flyway.baseline-on-migrate=true 가 "비어 있지 않은데 이력이 없는" 스키마를
-- baseline(버전 1)으로 기록하고, V20260503~ 마이그레이션을 순서대로 적용한다.
--
-- 이 파일은 Flyway 마이그레이션이 "추가하기 전" 상태다. 아래는 일부러 넣지 않았다. 넣으면 마이그레이션이 실패한다.
--   - notification.event_id 및 uk_notification_event_id          (V20260503)
--   - coupon 의 uk_coupon_user_stock                             (V20260602)
--   - item_favorite 의 uk_item_favorite_user_item, CHECK         (V20261015)
--   - item_favorite_meta 의 uk_item_favorite_meta_item, CHECK    (V20261015_2)
-- 또 Flyway 가 만드는 테이블(coupon_issue_request, coupon_event, cash_point_earn_history 등)도 여기에 없다.
--
-- 컬럼은 src/main/resources/mapper/*.xml 과 domain 클래스에서 역산했다. 운영 DB 와 대조한 것이 아니므로
-- 기존 환경에는 적용하지 말고, 새로 만드는 스키마에만 사용한다.
-- 레거시와 같은 방식으로 FOREIGN KEY 는 두지 않았다 (가입 시 account_id NULL, OAuth 가입자의 cash_point_id 0 등
-- 현재 코드가 FK 와 맞지 않는 값을 저장한다).

CREATE TABLE IF NOT EXISTS category (
    id   INT          NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS brand (
    id           INT          NOT NULL AUTO_INCREMENT,
    category_id  INT          NOT NULL,
    name         VARCHAR(100) NOT NULL,
    created_date DATETIME     NOT NULL,
    updated_date DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_brand_name (name),                          -- BrandMapper.existsByName
    KEY idx_brand_category_created (category_id, created_date) -- BrandMapper.findAllByCategory
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS item (
    id           INT          NOT NULL AUTO_INCREMENT,
    brand_id     INT          NOT NULL,
    name         VARCHAR(255) NOT NULL,
    price        INT          NOT NULL,
    created_date DATETIME     NOT NULL,
    updated_date DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_item_brand (brand_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS cash_point (
    id           INT      NOT NULL AUTO_INCREMENT,
    cash_point   INT      NOT NULL DEFAULT 0,
    created_date DATETIME NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS account (
    id             INT          NOT NULL AUTO_INCREMENT,
    account_holder VARCHAR(50)  NOT NULL,
    account_number VARCHAR(50)  NOT NULL,
    bank_name      VARCHAR(50)  NOT NULL,
    birth          VARCHAR(20)  NOT NULL,
    created_date   DATETIME     NOT NULL,
    deleted_date   DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_account_number (account_number)              -- AccountMapper.existsByAccountNumber
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- OAuth 가입자는 phone/user_role 이 비어 있고, 일반 가입자는 provider 가 비어 있다.
CREATE TABLE IF NOT EXISTS `user` (
    id            INT          NOT NULL AUTO_INCREMENT,
    account_id    INT          NULL,
    cash_point_id INT          NOT NULL DEFAULT 0,
    email         VARCHAR(255) NOT NULL,
    password      VARCHAR(255) NOT NULL,
    phone         VARCHAR(20)  NULL,
    user_role     VARCHAR(20)  NULL,                           -- UserRole: USER, VIP
    is_active     TINYINT(1)   NOT NULL DEFAULT 1,
    provider      VARCHAR(20)  NULL,                           -- OAuthProvider: KAKAO, NAVER
    created_date  DATETIME     NOT NULL,
    updated_date  DATETIME     NOT NULL,
    deleted_date  DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_email (email),                          -- UserMapper.findByEmail / existsByEmail
    UNIQUE KEY uk_user_phone (phone)                           -- UserMapper.existsByPhone (NULL 은 중복 허용)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS sale (
    id              INT         NOT NULL AUTO_INCREMENT,
    item_id         INT         NOT NULL,
    seller_id       INT         NOT NULL,
    barcode         VARCHAR(64) NOT NULL,
    expiration_date DATE        NOT NULL,
    is_bought       TINYINT(1)  NOT NULL DEFAULT 0,
    is_bought_date  DATE        NULL,
    created_date    DATETIME    NOT NULL,
    updated_date    DATETIME    NOT NULL,
    deleted_date    DATETIME    NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sale_barcode (barcode),                      -- SaleMapper.existsByBarcode
    KEY idx_sale_item_bought_expiration (item_id, is_bought, expiration_date), -- findNotBoughtSalesByItemId
    KEY idx_sale_seller (seller_id)                            -- findMySalesBySellerId / findMyTradedSales
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS trade (
    id           INT           NOT NULL AUTO_INCREMENT,
    buyer_id     INT           NOT NULL,
    sale_id      INT           NOT NULL,
    trade_price  DECIMAL(12,0) NOT NULL,                       -- Trade.discountItemPrice 가 scale 0 으로 반올림
    is_used      TINYINT(1)    NOT NULL DEFAULT 0,
    is_used_date DATE          NULL,
    created_date DATE          NOT NULL,
    PRIMARY KEY (id),
    KEY idx_trade_sale (sale_id),                              -- TradeMapper.findBySaleId / sale 조인
    KEY idx_trade_buyer (buyer_id)                             -- TradeMapper.findMyBoughtGifticon
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS coupon_stock (
    id     INT NOT NULL AUTO_INCREMENT,
    total  INT NOT NULL,
    remain INT NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- uk_coupon_user_stock 은 V20260602 가 추가한다.
CREATE TABLE IF NOT EXISTS coupon (
    id            INT          NOT NULL AUTO_INCREMENT,
    user_id       INT          NOT NULL,
    stock_id      INT          NOT NULL,
    name          VARCHAR(255) NOT NULL,
    coupon_type   VARCHAR(50)  NOT NULL,                       -- CouponType: FREE_POINT
    coupon_number VARCHAR(64)  NOT NULL,
    price         INT          NOT NULL,
    is_used       TINYINT(1)   NOT NULL DEFAULT 0,
    created_date  DATE         NOT NULL,
    expired_date  DATE         NOT NULL,
    PRIMARY KEY (id),
    KEY idx_coupon_coupon_number (coupon_number)               -- CouponMapper.getCouponByCouponNumber
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- uk_item_favorite_user_item 과 CHECK 는 V20261015 가 추가한다.
CREATE TABLE IF NOT EXISTS item_favorite (
    id          INT        NOT NULL AUTO_INCREMENT,
    user_id     INT        NOT NULL,
    item_id     INT        NOT NULL,
    is_favorite TINYINT(1) NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    KEY idx_item_favorite_item_active (item_id, is_favorite)   -- ItemFavoriteMetaMapper.countActiveFavorites
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- uk_item_favorite_meta_item 과 CHECK 는 V20261015_2 가 추가한다.
CREATE TABLE IF NOT EXISTS item_favorite_meta (
    id         INT NOT NULL AUTO_INCREMENT,
    item_id    INT NOT NULL,
    like_count INT NOT NULL DEFAULT 0,
    view_count INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_item_favorite_meta_like (like_count, item_id),     -- ItemMapper.findPopularItemsOrderByLikeCount
    KEY idx_item_favorite_meta_view (view_count, item_id)      -- ItemMapper.findPopularItemsOrderByViewCount
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS item_view_log (
    id        INT      NOT NULL AUTO_INCREMENT,
    item_id   INT      NOT NULL,
    user_id   INT      NOT NULL,
    viewed_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    KEY idx_item_view_log_item (item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- event_id 와 uk_notification_event_id 는 V20260503 이 추가한다.
CREATE TABLE IF NOT EXISTS notification (
    id           INT          NOT NULL AUTO_INCREMENT,
    sale_id      INT          NOT NULL,
    seller_id    INT          NOT NULL,
    message      VARCHAR(500) NOT NULL,
    is_read      TINYINT(1)   NOT NULL DEFAULT 0,
    created_date DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_notification_seller (seller_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
