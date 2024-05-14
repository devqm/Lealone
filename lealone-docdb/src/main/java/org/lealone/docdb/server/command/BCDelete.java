/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package org.lealone.docdb.server.command;

import java.util.ArrayList;

import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.io.ByteBufferBsonInput;
import org.lealone.db.session.ServerSession;
import org.lealone.db.table.Table;
import org.lealone.docdb.server.DocDBServerConnection;
import org.lealone.docdb.server.DocDBTask;
import org.lealone.sql.dml.Delete;
import org.lealone.sql.optimizer.TableFilter;

public class BCDelete extends BsonCommand {

    public static BsonDocument execute(ByteBufferBsonInput input, BsonDocument doc,
            DocDBServerConnection conn, DocDBTask task) {
        Table table = findTable(doc, "delete", conn);
        if (table != null) {
            delete(input, doc, conn, table, task);
            return null;
        } else {
            return createResponseDocument(0);
        }
    }

    private static void delete(ByteBufferBsonInput input, BsonDocument doc, DocDBServerConnection conn,
            Table table, DocDBTask task) {
        ServerSession session = task.session;
        Delete delete = new Delete(session);
        TableFilter tableFilter = new TableFilter(session, table, null, true, null);
        delete.setTableFilter(tableFilter);
        ArrayList<BsonDocument> deletes = readPayload(input, doc, conn, "deletes");
        for (BsonDocument d : deletes) {
            BsonDocument q = d.getDocument("q");
            if (q != null)
                delete.setCondition(toWhereCondition(q, tableFilter, session));
            BsonInt32 limit = d.getInt32("limit");
            if (limit != null)
                delete.setLimit(toValueExpression(limit));
        }
        delete.prepare();
        createAndSubmitYieldableUpdate(task, delete);
    }
}
