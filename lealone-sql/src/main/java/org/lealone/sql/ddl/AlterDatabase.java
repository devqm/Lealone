/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package org.lealone.sql.ddl;

import org.lealone.common.util.CaseInsensitiveMap;
import org.lealone.db.Database;
import org.lealone.db.LealoneDatabase;
import org.lealone.db.RunMode;
import org.lealone.db.session.ServerSession;
import org.lealone.sql.SQLStatement;

/**
 * This class represents the statement
 * ALTER DATABASE
 */
public class AlterDatabase extends DatabaseStatement {

    private final Database db;

    public AlterDatabase(ServerSession session, Database db, RunMode runMode,
            CaseInsensitiveMap<String> parameters) {
        super(session, db.getName());
        this.db = db;
        this.parameters = parameters;
        validateParameters();
    }

    @Override
    public int getType() {
        return SQLStatement.ALTER_DATABASE;
    }

    @Override
    public int update() {
        LealoneDatabase.checkAdminRight(session, "alter database");
        if (parameters == null || parameters.isEmpty())
            return 0;
        if (LealoneDatabase.getInstance().tryExclusiveDatabaseLock(session) == null)
            return -1;
        db.updateDbSettings(session, parameters);
        return 0;
    }
}
