package com.spotify.heroic.backend.model;

import java.util.List;
import java.util.Map;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

import com.spotify.heroic.backend.MetricBackend;
import com.spotify.heroic.backend.kairosdb.DataPointsRowKey;
import com.spotify.heroic.model.DateRange;

@ToString(of = { "key", "range", "filter" })
@RequiredArgsConstructor
public class FindRows {
    @Getter
    private final String key;
    @Getter
    private final DateRange range;
    @Getter
    private final Map<String, String> filter;

    @ToString(of = { "rows", "backend" })
    public static class Result {
        @Getter
        private final List<DataPointsRowKey> rows;

        @Getter
        private final MetricBackend backend;

        public Result(List<DataPointsRowKey> rows, MetricBackend backend) {
            this.rows = rows;
            this.backend = backend;
        }

        public boolean isEmpty() {
            return rows.isEmpty();
        }
    }

    public FindRows withRange(final DateRange range) {
        return new FindRows(key, range, filter);
    }
}