package com.pushtorefresh.storio.sqlite.operations.get;

import android.database.Cursor;
import android.support.annotation.CheckResult;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.WorkerThread;

import com.pushtorefresh.storio.StorIOException;
import com.pushtorefresh.storio.operations.internal.MapSomethingToExecuteAsBlocking;
import com.pushtorefresh.storio.operations.internal.OnSubscribeExecuteAsBlocking;
import com.pushtorefresh.storio.sqlite.SQLiteTypeMapping;
import com.pushtorefresh.storio.sqlite.StorIOSQLite;
import com.pushtorefresh.storio.sqlite.queries.Query;
import com.pushtorefresh.storio.sqlite.queries.RawQuery;

import java.util.Collections;
import java.util.Set;

import rx.Observable;
import rx.schedulers.Schedulers;

import static com.pushtorefresh.storio.internal.Checks.checkNotNull;
import static com.pushtorefresh.storio.internal.Environment.throwExceptionIfRxJavaIsNotAvailable;

public class PreparedGetObject<T> extends PreparedGet<T> {

    @NonNull
    private final Class<T> type;

    @Nullable
    private final GetResolver<T> explicitGetResolver;

    PreparedGetObject(@NonNull StorIOSQLite storIOSQLite,
                             @NonNull Class<T> type,
                             @NonNull Query query,
                             @Nullable GetResolver<T> explicitGetResolver) {
        super(storIOSQLite, query);
        this.type = type;
        this.explicitGetResolver = explicitGetResolver;
    }

    PreparedGetObject(@NonNull StorIOSQLite storIOSQLite,
                             @NonNull Class<T> type,
                             @NonNull RawQuery rawQuery,
                             @Nullable GetResolver<T> explicitGetResolver) {
        super(storIOSQLite, rawQuery);
        this.type = type;
        this.explicitGetResolver = explicitGetResolver;
    }

    /**
     * Executes Get Operation immediately in current thread.
     * <p>
     * Notice: This is blocking I/O operation that should not be executed on the Main Thread,
     * it can cause ANR (Activity Not Responding dialog), block the UI and drop animations frames.
     * So please, call this method on some background thread. See {@link WorkerThread}.
     *
     * @return single instance of mapped result. Can be {@code null}, if no items are found.
     */
    @Nullable
    @SuppressWarnings({"ConstantConditions", "NullableProblems"})
    @WorkerThread
    public T executeAsBlocking() {
        try {
            final GetResolver<T> getResolver;

            if (explicitGetResolver != null) {
                getResolver = explicitGetResolver;
            } else {
                final SQLiteTypeMapping<T> typeMapping = storIOSQLite.internal().typeMapping(type);

                if (typeMapping == null) {
                    throw new IllegalStateException("This type does not have type mapping: " +
                            "type = " + type + "," +
                            "db was not touched by this operation, please add type mapping for this type");
                }

                getResolver = typeMapping.getResolver();
            }

            final Cursor cursor;

            if (query != null) {
                cursor = getResolver.performGet(storIOSQLite, query);
            } else if (rawQuery != null) {
                cursor = getResolver.performGet(storIOSQLite, rawQuery);
            } else {
                throw new IllegalStateException("Please specify query");
            }

            try {
                final int count = cursor.getCount();

                if (count == 0) {
                    return null;
                }

                cursor.moveToNext();

                return getResolver.mapFromCursor(cursor);
            } finally {
                cursor.close();
            }
        } catch (Exception exception) {
            throw new StorIOException(exception);
        }
    }

    /**
     * Creates "Hot" {@link Observable} which will be subscribed to changes of tables from query
     * and will emit result each time change occurs.
     * <p>
     * First result will be emitted immediately after subscription,
     * other emissions will occur only if changes of tables from query will occur during lifetime of
     * the {@link Observable}.
     * <dl>
     * <dt><b>Scheduler:</b></dt>
     * <dd>Operates on {@link Schedulers#io()}.</dd>
     * </dl>
     * <p>
     * Please don't forget to unsubscribe from this {@link Observable} because
     * it's "Hot" and endless.
     *
     * @return non-null {@link Observable} which will emit single object
     * (can be {@code null}, if no items are found)
     * with mapped results and will be subscribed to changes of tables from query
     */
    @NonNull
    @CheckResult
    @Override
    public Observable<T> createObservable() {
        throwExceptionIfRxJavaIsNotAvailable("createObservable()");

        final Set<String> tables;

        if (query != null) {
            tables = Collections.singleton(query.table());
        } else if (rawQuery != null) {
            tables = rawQuery.observesTables();
        } else {
            throw new IllegalStateException("Please specify query");
        }

        if (!tables.isEmpty()) {
            return storIOSQLite
                    .observeChangesInTables(tables) // each change triggers executeAsBlocking
                    .map(MapSomethingToExecuteAsBlocking.newInstance(this))
                    .startWith(Observable.create(OnSubscribeExecuteAsBlocking.newInstance(this))) // start stream with first query result
                    .subscribeOn(Schedulers.io());
        } else {
            return Observable
                    .create(OnSubscribeExecuteAsBlocking.newInstance(this))
                    .subscribeOn(Schedulers.io());
        }
    }

    /**
     * Builder for {@link PreparedGetObject} Operation.
     *
     * @param <T> type of objects.
     */
    public static final class Builder<T> {

        @NonNull
        private final StorIOSQLite storIOSQLite;

        @NonNull
        private final Class<T> type;

        Builder(@NonNull StorIOSQLite storIOSQLite, @NonNull Class<T> type) {
            this.storIOSQLite = storIOSQLite;
            this.type = type;
        }

        /**
         * Required: Specifies query which will be passed to {@link StorIOSQLite}
         * to get object.
         *
         * @param query non-null query.
         * @return builder.
         * @see Query
         */
        @NonNull
        public CompleteBuilder<T> withQuery(@NonNull Query query) {
            checkNotNull(query, "Please specify query");
            return new CompleteBuilder<T>(storIOSQLite, type, query);
        }

        /**
         * Required: Specifies {@link RawQuery} for Get Operation,
         * you can use it for "joins" and same constructions which are not allowed for {@link Query}.
         *
         * @param rawQuery query.
         * @return builder.
         * @see RawQuery
         */
        @NonNull
        public CompleteBuilder<T> withQuery(@NonNull RawQuery rawQuery) {
            checkNotNull(rawQuery, "Please specify rawQuery");
            return new CompleteBuilder<T>(storIOSQLite, type, rawQuery);
        }
    }

    /**
     * Compile-safe part of {@link Builder}.
     *
     * @param <T> type of objects.
     */
    public static final class CompleteBuilder<T> {

        @NonNull
        private final StorIOSQLite storIOSQLite;

        @NonNull
        private final Class<T> type;

        @Nullable
        Query query;

        @Nullable
        RawQuery rawQuery;

        @Nullable
        private GetResolver<T> getResolver;

        CompleteBuilder(@NonNull StorIOSQLite storIOSQLite, @NonNull Class<T> type, @NonNull Query query) {
            this.storIOSQLite = storIOSQLite;
            this.type = type;
            this.query = query;
            rawQuery = null;
        }

        CompleteBuilder(@NonNull StorIOSQLite storIOSQLite, @NonNull Class<T> type, @NonNull RawQuery rawQuery) {
            this.storIOSQLite = storIOSQLite;
            this.type = type;
            this.rawQuery = rawQuery;
            query = null;
        }

        /**
         * Optional: Specifies resolver for Get Operation which can be used
         * to provide custom behavior of Get Operation.
         * <p>
         * {@link SQLiteTypeMapping} can be used to set default GetResolver.
         * If GetResolver is not set via {@link SQLiteTypeMapping}
         * or explicitly — exception will be thrown.
         *
         * @param getResolver nullable resolver for Get Operation.
         * @return builder.
         */
        @NonNull
        public CompleteBuilder<T> withGetResolver(@Nullable GetResolver<T> getResolver) {
            this.getResolver = getResolver;
            return this;
        }

        /**
         * Builds new instance of {@link PreparedGetObject}.
         *
         * @return new instance of {@link PreparedGetObject}.
         */
        @NonNull
        public PreparedGetObject<T> prepare() {
            if (query != null) {
                return new PreparedGetObject<T>(
                        storIOSQLite,
                        type,
                        query,
                        getResolver
                );
            } else if (rawQuery != null) {
                return new PreparedGetObject<T>(
                        storIOSQLite,
                        type,
                        rawQuery,
                        getResolver
                );
            } else {
                throw new IllegalStateException("Please specify Query or RawQuery");
            }
        }
    }
}
