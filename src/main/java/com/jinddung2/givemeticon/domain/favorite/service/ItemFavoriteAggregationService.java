package com.jinddung2.givemeticon.domain.favorite.service;

import com.jinddung2.givemeticon.domain.favorite.domain.ItemFavoriteCount;
import com.jinddung2.givemeticon.domain.favorite.domain.ItemFavoriteAggregationState;
import org.springframework.beans.factory.annotation.Value;
import com.jinddung2.givemeticon.domain.favorite.mapper.ItemFavoriteMetaMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ItemFavoriteAggregationService {
    private static final int WRITE_BATCH_SIZE = 500;
    private final ItemFavoriteMetaMapper itemFavoriteMetaMapper;

    @Value("${favorite.aggregation.interval-millis:1800000}")
    private long intervalMillis;

    /** False means another owner is running or a recent snapshot is still fresh. */
    @Transactional(isolation = Isolation.REPEATABLE_READ,
            timeoutString = "${favorite.aggregation.timeout-seconds:300}")
    public boolean refreshLikeCounts() {
        ItemFavoriteAggregationState state;
        try {
            state = itemFavoriteMetaMapper.lockAggregation();
            if (state == null) {
                throw new IllegalStateException("favorite aggregation lock row is missing");
            }
        } catch (DataAccessException e) {
            // MySQL ER_LOCK_NOWAIT. Other failures must not be disguised as a skipped job.
            if (e.getMostSpecificCause() instanceof SQLException sql && sql.getErrorCode() == 3572) {
                return false;
            }
            throw e;
        }

        if (state.lastSuccessAt() != null
                && state.databaseNow().isBefore(state.lastSuccessAt().plus(Duration.ofMillis(intervalMillis)))) {
            return false;
        }

        // This plain SELECT establishes one MVCC snapshot without locking favorite writers.
        List<ItemFavoriteCount> counts = itemFavoriteMetaMapper.countActiveFavorites();
        for (int start = 0; start < counts.size(); start += WRITE_BATCH_SIZE) {
            itemFavoriteMetaMapper.replaceLikeCounts(
                    counts.subList(start, Math.min(start + WRITE_BATCH_SIZE, counts.size())));
        }
        if (itemFavoriteMetaMapper.recordAggregationSuccess(state.databaseNow(), counts.size()) != 1) {
            throw new IllegalStateException("favorite aggregation success state was not saved");
        }
        // The transaction proxy commits the complete snapshot before returning to the caller.
        return true;
    }
}
