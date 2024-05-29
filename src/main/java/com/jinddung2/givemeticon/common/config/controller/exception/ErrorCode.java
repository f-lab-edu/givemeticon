package com.jinddung2.givemeticon.common.config.controller.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {
    // AUTH
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED,"로그인이 필요한 기능입니다."),
    UNAUTHORIZED(HttpStatus.FORBIDDEN,"해당 권한이 없습니다."),
    NOT_FOUND_EMAIL(HttpStatus.NOT_FOUND, "이메일이 존재하지 않습니다."),
    INVALID_CERTIFICATED_NUMBER(HttpStatus.BAD_REQUEST,"인증 번호가 다릅니다."),
    KAKAO_TOKEN_EMPTY(HttpStatus.BAD_REQUEST, "카카오 토큰이 없습니다."),
    NAVER_TOKEN_EMPTY(HttpStatus.BAD_REQUEST, "네이버 토큰이 없습니다."),

    // USER
    DUPLICATED_EMAIL(HttpStatus.CONFLICT, "이미 존재하는 이메일입니다."),
    DUPLICATED_PHONE(HttpStatus.CONFLICT, "이미 존재하는 휴대폰 번호입니다."),
    NOT_FOUND_USER(HttpStatus.NOT_FOUND, "존재하지 않는 회원입니다."),
    INCORRECT_PASSWORD(HttpStatus.BAD_REQUEST, "패스워드가 일치하지 않습니다."),

    // BRAND
    DUPLICATED_BRAND_NAME(HttpStatus.CONFLICT, "이미 존재하는 브랜드입니다."),
    NOT_FOUND_BRAND(HttpStatus.NOT_FOUND, "존재하지 않는 브랜드입니다."),
    PAGE_NUMBER_HAS_EMPTY_BRAND(HttpStatus.NOT_FOUND, "해당 페이지에 해당하는 브랜드가 없습니다."),

    // CATEGORY
    NOT_FOUND_CATEGORY(HttpStatus.NOT_FOUND, "존재하지 않는 카테고리입니다."),
    EMPTY_CATEGORY_LIST(HttpStatus.NOT_FOUND, "카테고리 목록을 찾을 수 없습니다."),

    // ACCOUNT
    DUPLICATED_ACCOUNT_NUMBER(HttpStatus.CONFLICT, "이미 등록된 계좌번호 입니다."),

    // Item, Sale
    NOT_FOUND_ITEM(HttpStatus.NOT_FOUND, "존재하지 않는 아이템입니다."),
    NOT_FOUND_SALE(HttpStatus.NOT_FOUND, "존재하지 않는 판매 상품입니다."),
    NOT_REGISTER_ACCOUNT(HttpStatus.FORBIDDEN, "판매자 등록이 되어 있지 않습니다."),
    SALE_EXPIRED_DATE(HttpStatus.BAD_REQUEST, "상품 유효기간이 이미 지났습니다."),
    DUPLICATED_BARCODE_NUMBER(HttpStatus.CONFLICT, "이미 등록된 바코드 입니다."),

    // Trade
    INVALID_DISCOUNT_RATE(HttpStatus.BAD_REQUEST, "할인율은 0 이상 1 이하여야 합니다."),
    ALREADY_BOUGHT_SALE(HttpStatus.CONTINUE, "이미 구매된 상품 입니다."),
    NOT_FOUND_TRADE(HttpStatus.NOT_FOUND, "존재하지 않은 거래번호 입니다."),
    INVALID_BUY_OWNER(HttpStatus.FORBIDDEN, "구매하신 사용자가 아닙니다."),

    // ItemFavorite
    ALREADY_PUSH_ITEMFAVORITE(HttpStatus.CONFLICT, "이미 좋아요를 눌렀습니다."),
    NOT_PUSH_ITEMFAVORITE(HttpStatus.BAD_REQUEST, "좋아요 한 적이 없는 상품이 입니다."),

    // Coupon
    NOT_FOUND_COUPON_STOCK(HttpStatus.NOT_FOUND, "쿠폰 재고를 찾을 수 없습니다."),
    NOT_ENOUGH_COUPON_STOCK(HttpStatus.CONFLICT, "쿠폰이 소진되었습니다."),
    NOT_FOUND_COUPON(HttpStatus.NOT_FOUND, "쿠폰을 찾을 수 없습니다."),
    COUPON_USER_MISMATCH(HttpStatus.FORBIDDEN, "쿠폰에 등록된 유저가 아닙니다."),
    COUPON_EXPIRED_DATE(HttpStatus.GONE, "쿠폰 유효기간이 이미 지났습니다."),
    ALREADY_REDEEMED_COUPON(HttpStatus.CONFLICT, "이미 사용한 쿠폰입니다."),

    // CashPoint
    NOT_FOUND_CASH_POINT(HttpStatus.NOT_FOUND, "포인트 데이터를 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }
}

