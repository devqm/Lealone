/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package org.lealone.test.plugins;

import org.junit.Test;
import org.lealone.db.PluginManager;
import org.lealone.storage.StorageEngine;
import org.lealone.storage.aose.AOStorageEngine;
import org.lealone.test.TestBase;

public class PluginManagerTest extends TestBase {
    @Test
    public void run() {
        StorageEngine se = PluginManager.getPlugin(StorageEngine.class, AOStorageEngine.NAME);
        assertTrue(se instanceof AOStorageEngine);

        // 默认是用StorageEngine.class为key，所以用AOStorageEngine.class时找不到
        se = PluginManager.getPlugin(AOStorageEngine.class, AOStorageEngine.NAME);
        assertNull(se);

        AOStorageEngine ase = new AOStorageEngine();
        ase.setName("myaose");
        PluginManager.register(ase); // 用AOStorageEngine.class为key注册

        se = PluginManager.getPlugin(AOStorageEngine.class, "myaose");
        assertNotNull(se);
        se = PluginManager.getPlugin(StorageEngine.class, "myaose");
        assertNull(se);

        PluginManager.deregister(ase);
        se = PluginManager.getPlugin(AOStorageEngine.class, "myaose");
        assertNull(se);
    }
}
