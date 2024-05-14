/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.sql.ddl;

import org.lealone.common.exceptions.DbException;
import org.lealone.db.DbObjectType;
import org.lealone.db.api.ErrorCode;
import org.lealone.db.auth.Right;
import org.lealone.db.index.Index;
import org.lealone.db.lock.DbObjectLock;
import org.lealone.db.schema.Schema;
import org.lealone.db.session.ServerSession;
import org.lealone.sql.SQLStatement;

/**
 * This class represents the statement
 * ALTER INDEX RENAME
 * 
 * @author H2 Group
 * @author zhh
 */
public class AlterIndexRename extends SchemaStatement {

    private Index oldIndex;
    private String newIndexName;

    public AlterIndexRename(ServerSession session, Schema schema) {
        super(session, schema);
    }

    @Override
    public int getType() {
        return SQLStatement.ALTER_INDEX_RENAME;
    }

    public void setOldIndex(Index index) {
        oldIndex = index;
    }

    public void setNewName(String name) {
        newIndexName = name;
    }

    @Override
    public int update() {
        session.getUser().checkRight(oldIndex.getTable(), Right.ALL);
        DbObjectLock lock = schema.tryExclusiveLock(DbObjectType.INDEX, session);
        if (lock == null)
            return -1;

        if (schema.findIndex(session, newIndexName) != null || newIndexName.equals(oldIndex.getName())) {
            throw DbException.get(ErrorCode.INDEX_ALREADY_EXISTS_1, newIndexName);
        }
        schema.rename(session, oldIndex, newIndexName, lock);
        return 0;
    }
}
