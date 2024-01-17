package io.phasetwo.keycloak.jpacache;

import com.datastax.oss.driver.api.core.PagingIterable;
import com.datastax.oss.driver.api.core.paging.OffsetPager;

import java.util.stream.Stream;

public final class StreamExtensions {
    public static <T> Stream<T> paginated(PagingIterable<T> rs, Integer firstResult, Integer maxResult) {
        if (maxResult == null || maxResult == -1) {
            return rs.all().stream();
        }

        OffsetPager offsetPager = new OffsetPager(maxResult);
        OffsetPager.Page<T> page = offsetPager.getPage(rs, (firstResult / maxResult) + 1);

        return page.getElements().stream();
    }
}
