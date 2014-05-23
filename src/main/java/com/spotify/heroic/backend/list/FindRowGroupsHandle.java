package com.spotify.heroic.backend.list;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import com.codahale.metrics.Timer;
import com.spotify.heroic.aggregator.Aggregator;
import com.spotify.heroic.aggregator.AggregatorGroup;
import com.spotify.heroic.async.Callback;
import com.spotify.heroic.async.CallbackGroup;
import com.spotify.heroic.async.CancelReason;
import com.spotify.heroic.async.ConcurrentCallback;
import com.spotify.heroic.backend.BackendManager.QueryMetricsResult;
import com.spotify.heroic.backend.MetricBackend;
import com.spotify.heroic.backend.QueryException;
import com.spotify.heroic.backend.kairosdb.DataPointsRowKey;
import com.spotify.heroic.backend.model.FetchDataPoints;
import com.spotify.heroic.backend.model.FindRowGroups;
import com.spotify.heroic.cache.AggregationCache;
import com.spotify.heroic.model.TimeSerie;
import com.spotify.heroic.model.TimeSerieSlice;

@RequiredArgsConstructor
public final class FindRowGroupsHandle implements
        CallbackGroup.Handle<FindRowGroups.Result> {
    @RequiredArgsConstructor
    static final class PreparedGroup {
        @Getter
        private final MetricBackend backend;
        @Getter
        private final List<DataPointsRowKey> rows;
    }

    private final AggregationCache cache;
    private final Timer timer;
    private final TimeSerieSlice querySlice;
    private final Callback<QueryMetricsResult> callback;
    private final AggregatorGroup aggregator;
    private final boolean noCache;

    @Override
    public void done(Collection<FindRowGroups.Result> results,
            Collection<Throwable> errors, Collection<CancelReason> cancelled)
            throws Exception {
        final Map<TimeSerieSlice, List<PreparedGroup>> groups = prepareGroups(results);

        if (cache == null || noCache) {
            callback.reduce(executeQueries(groups), timer, new JoinQueryMetricsResult());
            return;
        }

        callback.reduce(executeCacheQueries(groups), timer, new JoinQueryMetricsResult());
    }

    private List<Callback<QueryMetricsResult>> executeCacheQueries(
            Map<TimeSerieSlice, List<PreparedGroup>> groups) {
        final List<Callback<QueryMetricsResult>> callbacks = new ArrayList<Callback<QueryMetricsResult>>();

        for (Map.Entry<TimeSerieSlice, List<PreparedGroup>> entry : groups.entrySet()) {
            final Callback<QueryMetricsResult> callback = new ConcurrentCallback<QueryMetricsResult>();
            final TimeSerieSlice slice = entry.getKey();
            final List<PreparedGroup> preparedGroups = entry.getValue();

            callbacks.add(callback);

            final TimeSerie timeSerie = slice.getTimeSerie();

            cache.get(slice, aggregator).register(new CacheGetHandle("group.cache-query", timer, callback, timeSerie.getTags(), cache) {
                @Override
                public Callback<QueryMetricsResult> cacheMiss(TimeSerieSlice slice) throws Exception {
                    return executeSingle(slice, preparedGroups);
                }
            });
        }

        return callbacks;
    }

    private final Map<TimeSerieSlice, List<PreparedGroup>> prepareGroups(
            Collection<FindRowGroups.Result> results) throws QueryException {

        final Map<TimeSerieSlice, List<PreparedGroup>> mappedQueries = new HashMap<TimeSerieSlice, List<PreparedGroup>>();

        for (final FindRowGroups.Result result : results) {
            final MetricBackend backend = result.getBackend();

            for (final Map.Entry<Map<String, String>, List<DataPointsRowKey>> entry : result
                    .getRowGroups().entrySet()) {

                final Map<String, String> tags = entry.getKey();
                final List<DataPointsRowKey> rows = entry.getValue();

                final TimeSerieSlice slice = querySlice.modifyTags(tags);

                List<PreparedGroup> groups = mappedQueries
                        .get(slice);


                if (groups == null) {
                    groups = new ArrayList<PreparedGroup>();
                    mappedQueries.put(slice, groups);
                }

                groups.add(new PreparedGroup(backend, rows));
            }
        }

        return mappedQueries;
    }

    private Callback<QueryMetricsResult> executeSingle(TimeSerieSlice slice, List<PreparedGroup> preparedGroups) {
        final Aggregator.Session session = aggregator.session(slice.getRange());

        final Callback<QueryMetricsResult> partial = new ConcurrentCallback<QueryMetricsResult>();
        final Callback.StreamReducer<FetchDataPoints.Result, QueryMetricsResult> reducer;

        if (session == null) {
            reducer = new SimpleCallbackStream(slice);
        } else {
            reducer = new AggregatedCallbackStream(slice, session);
        }

        final List<Callback<FetchDataPoints.Result>> backendQueries = new ArrayList<Callback<FetchDataPoints.Result>>();

        for (final PreparedGroup prepared : preparedGroups) {
            final MetricBackend backend = prepared.getBackend();
            backendQueries.addAll(
                    backend.query(new FetchDataPoints(prepared.getRows(), slice.getRange())));
        }

        return partial.reduce(backendQueries, timer, reducer);
    }

    private List<Callback<QueryMetricsResult>> executeQueries(
            final Map<TimeSerieSlice, List<PreparedGroup>> groups)
            throws Exception {
        final List<Callback<QueryMetricsResult>> queries = new ArrayList<Callback<QueryMetricsResult>>();

        for (final Map.Entry<TimeSerieSlice, List<PreparedGroup>> entry : groups.entrySet()) {
            final TimeSerieSlice slice = entry.getKey();
            final List<PreparedGroup> preparedGroups = entry.getValue();
            queries.add(executeSingle(slice, preparedGroups));
        }

        return queries;
    }
}