package com.jinddung2.givemeticon.common.config.controller.response;

public record ApiResponse<T>(
        String message,
        T data
) {
    public static <T> ApiResponse<?> success() {
        return new ApiResponse<>("SUCCESS", "no data");
    }

    public static <T> ApiResponse<T> success(T result) {
        return new ApiResponse<>("SUCCESS", result);
    }

    public static <T> ApiResponse<?> fail() {
        return new ApiResponse<>("FAIL", "no data");
    }

    public static <T> ApiResponse<T> fail(T result) {
        return new ApiResponse<>("FAIL", result);
    }
}
