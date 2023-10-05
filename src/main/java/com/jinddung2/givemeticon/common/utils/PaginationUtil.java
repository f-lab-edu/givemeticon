package com.jinddung2.givemeticon.common.utils;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.Map;

public class PaginationUtil {

    public static Map<String, Object> makePagingParamMap(int id, int page, int pageSize) {
        Pageable pageable = PageRequest.of(page, pageSize);
        return Map.ofEntries(
                Map.entry("id", id),
                Map.entry("offset", pageable.getOffset()),
                Map.entry("pageSize", pageable.getPageSize())
        );
    }
}
