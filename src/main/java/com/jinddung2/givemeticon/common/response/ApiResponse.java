package com.jinddung2.givemeticon.common.response;

public record ApiResponse<T>(
        String message,
        T data
) {
    public static <T> ApiResponse<T> success() {
        return new ApiResponse<>("SUCCESS", null);
    }

    public static <T> ApiResponse<T> success(T result) {
        return new ApiResponse<>("SUCCESS", result);
    }
}