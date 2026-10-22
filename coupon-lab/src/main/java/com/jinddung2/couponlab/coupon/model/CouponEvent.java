package com.jinddung2.couponlab.coupon.model;

public class CouponEvent {

    private Long id;
    private final String name;
    private final int totalQuantity;

    public CouponEvent(String name, int totalQuantity) {
        this.name = name;
        this.totalQuantity = totalQuantity;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public int getTotalQuantity() {
        return totalQuantity;
    }
}
