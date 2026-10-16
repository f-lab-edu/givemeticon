package com.jinddung2.givemeticon.domain.favorite.service;

import com.jinddung2.givemeticon.domain.favorite.domain.ItemFavoriteCount;
import com.jinddung2.givemeticon.domain.favorite.mapper.ItemFavoriteMetaMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ItemFavoriteAggregationService {
    private static final int WRITE_BATCH_SIZE = 500;
    private final ItemFavoriteMetaMapper itemFavoriteMetaMapper;

    /** False means another transaction owns the job. Failures propagate and roll back all chunks. */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public boolean refreshLikeCounts() {
        try {
            if (itemFavoriteMetaMapper.lockAggregation() == null) {
                throw new IllegalStateException("favorite aggregation lock row is missing");
            }
        } catch (DataAccessException e) {
            // MySQL ER_LOCK_NOWAIT. Other failures must not be disguised as a skipped job.
            if (e.getMostSpecificCause() instanceof SQLException sql && sql.getErrorCode() == 3572) {
                return false;
            }
            throw e;
        }

        // This plain SELECT establishes one MVCC snapshot without locking favorite writers.
        List<ItemFavoriteCount> counts = itemFavoriteMetaMapper.countActiveFavorites();
        for (int start = 0; start < counts.size(); start += WRITE_BATCH_SIZE) {
            itemFavoriteMetaMapper.replaceLikeCounts(
                    counts.subList(start, Math.min(start + WRITE_BATCH_SIZE, counts.size())));
        }
        // The transaction proxy commits the complete snapshot before returning to the caller.
        return true;
    }
}
