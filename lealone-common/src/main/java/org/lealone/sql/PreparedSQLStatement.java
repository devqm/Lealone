/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package org.lealone.sql;

import org.lealone.db.ManualCloseable;
import org.lealone.db.async.AsyncHandler;
import org.lealone.db.async.AsyncResult;
import org.lealone.db.result.Result;
import org.lealone.db.session.Session;

public interface PreparedSQLStatement extends SQLStatement, ManualCloseable {

    public final static int MIN_PRIORITY = 1;
    public final static int NORM_PRIORITY = 5;
    public final static int MAX_PRIORITY = 10;

    Session getSession();

    String getSQL();

    int getId();

    void setId(int id);

    int getPriority();

    void setPriority(int priority);

    void setObjectId(int objectId);

    void setExecutor(SQLStatementExecutor executor);

    boolean canReuse();

    void reuse();

    default boolean isCacheable() {
        return false;
    }

    default boolean isDDL() {
        return false;
    }

    @Deprecated
    default boolean isIfDDL() {
        return false;
    }

    default boolean isDatabaseStatement() {
        return false;
    }

    default boolean isTransactionStatement() {
        return false;
    }

    default boolean isForUpdate() {
        return false;
    }

    Result query(int maxRows);

    int update();

    Yieldable<Result> createYieldableQuery(int maxRows, boolean scrollable,
            AsyncHandler<AsyncResult<Result>> asyncHandler);

    Yieldable<Integer> createYieldableUpdate(AsyncHandler<AsyncResult<Integer>> asyncHandler);

    static interface Yieldable<T> {

        void run();

        void stop();

        boolean isStopped();

        T getResult();

        int getPriority();

        Session getSession();

        PreparedSQLStatement getStatement();

        void setExecutor(SQLStatementExecutor executor);

    }
}
