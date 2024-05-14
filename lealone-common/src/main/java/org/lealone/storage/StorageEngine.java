/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package org.lealone.storage;

import org.lealone.db.Constants;
import org.lealone.db.DataHandler;
import org.lealone.db.PluggableEngine;
import org.lealone.db.PluginManager;
import org.lealone.storage.lob.LobStorage;
import org.lealone.storage.page.PageOperationHandlerFactory;

public interface StorageEngine extends PluggableEngine {

    public static StorageEngine getDefaultStorageEngine() {
        return PluginManager.getPlugin(StorageEngine.class, Constants.DEFAULT_STORAGE_ENGINE_NAME);
    }

    StorageBuilder getStorageBuilder();

    LobStorage getLobStorage(DataHandler dataHandler, Storage storage);

    void setPageOperationHandlerFactory(PageOperationHandlerFactory pohFactory);

    PageOperationHandlerFactory getPageOperationHandlerFactory();

}
