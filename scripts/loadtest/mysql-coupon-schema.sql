-- givemeticon_loadtest 전용 스키마. 운영 givemeticon 스키마의 데이터는 건드리지 않는다.
CREATE TABLE IF NOT EXISTS coupon_stock (
    id INT NOT NULL AUTO_INCREMENT,
    total INT NOT NULL,
    remain INT NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS coupon (
    id INT NOT NULL AUTO_INCREMENT,
    user_id INT NOT NULL,
    stock_id INT NOT NULL,
    name VARCHAR(255) NOT NULL,
    coupon_type VARCHAR(50) NOT NULL,
    coupon_number VARCHAR(64) NOT NULL,
    price INT NOT NULL,
    is_used TINYINT(1) NOT NULL DEFAULT 0,
    created_date DATE NOT NULL,
    expired_date DATE NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_coupon_user_stock (user_id, stock_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS coupon_issue_request (
    id BIGINT NOT NULL AUTO_INCREMENT,
    stock_id INT NOT NULL,
    user_id INT NOT NULL,
    coupon_name VARCHAR(255) NOT NULL,
    coupon_type VARCHAR(50) NOT NULL,
    price INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    coupon_id INT NULL,
    reason VARCHAR(255) NULL,
    created_date DATETIME(6) NOT NULL,
    updated_date DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_coupon_issue_request_user_stock (user_id, stock_id),
    KEY idx_coupon_issue_request_status_updated (status, updated_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
