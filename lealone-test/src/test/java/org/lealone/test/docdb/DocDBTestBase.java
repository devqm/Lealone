/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package org.lealone.test.docdb;

import org.bson.Document;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.lealone.test.UnitTestBase;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;

public class DocDBTestBase extends UnitTestBase {

    protected static MongoClient mongoClient;
    protected static MongoDatabase database;
    protected MongoCollection<Document> collection;
    protected String collectionName;

    protected DocDBTestBase() {
        this.collectionName = getClass().getSimpleName();
    }

    public DocDBTestBase(String collectionName) {
        this.collectionName = collectionName;
    }

    @BeforeClass
    public static void beforeClass() {
        mongoClient = getMongoClient();
        database = mongoClient.getDatabase("docdb");
        // System.out.println(database.runCommand(Document.parse("{\"buildInfo\": 1}")));
        // database.createCollection(collectionName);
    }

    @AfterClass
    public static void afterClass() {
        mongoClient.close();
    }

    @Before
    public void before() {
        collection = database.getCollection(collectionName);
    }

    @Override
    public void runTest() {
        beforeClass();
        before();
        try {
            test();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            afterClass();
        }
    }

    @Override
    protected void test() throws Exception {
        // do nothing
    }

    public static MongoClient getMongoClient() {
        int port = 9610;
        // port = 27017;
        String connectionString = "mongodb://root:root@127.0.0.1:" + port
                + "/?serverSelectionTimeoutMS=200000";
        connectionString = "mongodb://127.0.0.1:" + port + "/?serverSelectionTimeoutMS=200000";
        return MongoClients.create(connectionString);
    }
}
