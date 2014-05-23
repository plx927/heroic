package com.spotify.heroic.backend.list;

import java.util.ArrayList;
import java.util.List;

import lombok.extern.slf4j.Slf4j;

import com.codahale.metrics.Timer;
import com.spotify.heroic.aggregator.AggregatorGroup;
import com.spotify.heroic.async.Callback;
import com.spotify.heroic.async.CallbackGroup;
import com.spotify.heroic.async.ConcurrentCallback;
import com.spotify.heroic.backend.BackendManager.QueryMetricsResult;
import com.spotify.heroic.backend.MetricBackend;
import com.spotify.heroic.backend.model.FindRowGroups;
import com.spotify.heroic.cache.AggregationCache;
import com.spotify.heroic.model.DateRange;
import com.spotify.heroic.model.TimeSerie;
import com.spotify.heroic.model.TimeSerieSlice;

@Slf4j
public class QueryGroup {
    private final List<MetricBackend> backends;
    private final Timer timer;
    private final AggregationCache cache;

    public QueryGroup(List<MetricBackend> backends, Timer timer, AggregationCache cache) {
        this.backends = backends;
        this.timer = timer;
        this.cache = cache;
    }

    public Callback<QueryMetricsResult> execute(FindRowGroups criteria,
            final AggregatorGroup aggregator, boolean noCache) {

        final List<Callback<FindRowGroups.Result>> queries = new ArrayList<Callback<FindRowGroups.Result>>();

        for (final MetricBackend backend : backends) {
            try {
                queries.add(backend.findRowGroups(criteria));
            } catch (final Exception e) {
                log.error("Failed to query backend", e);
            }
        }

        final Callback<QueryMetricsResult> callback = new ConcurrentCallback<QueryMetricsResult>();

        final DateRange range = criteria.getRange();
        final TimeSerie timeSerie = new TimeSerie(criteria.getKey(), criteria.getFilter());
        final TimeSerieSlice slice = new TimeSerieSlice(timeSerie, range);

        final CallbackGroup<FindRowGroups.Result> group = new CallbackGroup<FindRowGroups.Result>(
                queries, new FindRowGroupsHandle(cache, timer, slice, callback, aggregator, noCache));

        final Timer.Context context = timer.time();

        return callback.register(group).register(new Callback.Finishable() {
            @Override
            public void finish() throws Exception {
                context.stop();
            }
        });
    }
}
