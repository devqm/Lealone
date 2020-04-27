/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.sql.ddl;

import org.lealone.common.exceptions.DbException;
import org.lealone.db.Database;
import org.lealone.db.DbObjectType;
import org.lealone.db.api.ErrorCode;
import org.lealone.db.schema.Schema;
import org.lealone.db.schema.UserDataType;
import org.lealone.db.session.ServerSession;
import org.lealone.sql.SQLStatement;

/**
 * This class represents the statement
 * DROP DOMAIN
 * 
 * @author H2 Group
 * @author zhh
 */
public class DropUserDataType extends SchemaStatement {

    private String typeName;
    private boolean ifExists;

    public DropUserDataType(ServerSession session, Schema schema) {
        super(session, schema);
    }

    @Override
    public int getType() {
        return SQLStatement.DROP_DOMAIN;
    }

    public void setIfExists(boolean ifExists) {
        this.ifExists = ifExists;
    }

    @Override
    public int update() {
        session.getUser().checkAdmin();
        Database db = session.getDatabase();
        synchronized (getSchema().getLock(DbObjectType.USER_DATATYPE)) {
            UserDataType type = getSchema().findUserDataType(typeName);
            if (type == null) {
                if (!ifExists) {
                    throw DbException.get(ErrorCode.USER_DATA_TYPE_NOT_FOUND_1, typeName);
                }
            } else {
                db.removeSchemaObject(session, type);
            }
        }
        return 0;
    }

    public void setTypeName(String name) {
        this.typeName = name;
    }

}
