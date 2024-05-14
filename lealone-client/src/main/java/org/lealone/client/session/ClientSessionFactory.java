/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package org.lealone.client.session;

import java.util.Random;

import org.lealone.common.exceptions.DbException;
import org.lealone.common.util.CaseInsensitiveMap;
import org.lealone.common.util.StringUtils;
import org.lealone.db.ConnectionInfo;
import org.lealone.db.ConnectionSetting;
import org.lealone.db.api.ErrorCode;
import org.lealone.db.async.AsyncCallback;
import org.lealone.db.async.Future;
import org.lealone.db.session.Session;
import org.lealone.db.session.SessionFactory;
import org.lealone.net.AsyncConnection;
import org.lealone.net.NetClient;
import org.lealone.net.NetFactory;
import org.lealone.net.NetFactoryManager;
import org.lealone.net.NetNode;
import org.lealone.net.TcpClientConnection;
import org.lealone.server.protocol.AckPacketHandler;
import org.lealone.server.protocol.session.SessionInit;
import org.lealone.server.protocol.session.SessionInitAck;

public class ClientSessionFactory implements SessionFactory {

    private static final ClientSessionFactory instance = new ClientSessionFactory();

    public static ClientSessionFactory getInstance() {
        return instance;
    }

    private ClientSessionFactory() {
    }

    @Override
    public Future<Session> createSession(ConnectionInfo ci, boolean allowRedirect) {
        if (!ci.isRemote()) {
            throw DbException.getInternalError();
        }
        AsyncCallback<Session> ac = AsyncCallback.create(ci.isSingleThreadCallback());
        createSession(ci, allowRedirect, ac);
        return ac;
    }

    private static void createSession(ConnectionInfo ci, boolean allowRedirect,
            AsyncCallback<Session> ac) {
        String[] servers = StringUtils.arraySplit(ci.getServers(), ',');
        Random random = new Random(System.currentTimeMillis());
        AutoReconnectSession parent = new AutoReconnectSession(ci);
        createSession(parent, ci, servers, allowRedirect, random, ac);
    }

    // servers是接入节点，可以有多个，会随机选择一个进行连接，这个被选中的接入节点可能不是所要连接的数居库所在的节点，
    // 这时接入节点会返回数据库的真实所在节点，最后再根据数据库的运行模式打开合适的连接即可，
    // 复制模式需要打开所有节点，其他运行模式只需要打开一个。
    // 如果第一次从servers中随机选择的一个连接失败了，会尝试其他的，当所有尝试都失败了才会抛出异常。
    private static void createSession(AutoReconnectSession parent, ConnectionInfo ci, String[] servers,
            boolean allowRedirect, Random random, AsyncCallback<Session> topAc) {
        int randomIndex = random.nextInt(servers.length);
        String server = servers[randomIndex];
        AsyncCallback<ClientSession> ac = AsyncCallback.create(ci.isSingleThreadCallback());
        ac.onComplete(ar -> {
            if (ar.isSucceeded()) {
                ClientSession clientSession = ar.getResult();
                // 看看是否需要根据运行模式从当前接入节点转到数据库所在的节点
                if (allowRedirect) {
                    redirectIfNeeded(parent, clientSession, ci, topAc);
                } else {
                    parent.setSession(clientSession);
                    topAc.setAsyncResult(parent);
                }
            } else {
                // 如果已经是最后一个了那就可以直接抛异常了，否则再选其他的
                if (servers.length == 1) {
                    Throwable e = DbException.getCause(ar.getCause());
                    e = DbException.get(ErrorCode.CONNECTION_BROKEN_1, e, server);
                    topAc.setAsyncResult(e);
                } else {
                    int len = servers.length;
                    String[] newServers = new String[len - 1];
                    for (int i = 0, j = 0; j < len; j++) {
                        if (j != randomIndex)
                            newServers[i++] = servers[j];
                    }
                    createSession(parent, ci, newServers, allowRedirect, random, topAc);
                }
            }
        });
        createClientSession(parent, ci, server, ac);
    }

    private static void createClientSession(AutoReconnectSession parent, ConnectionInfo ci,
            String server, AsyncCallback<ClientSession> ac) {
        NetClient netClient;
        CaseInsensitiveMap<String> config = new CaseInsensitiveMap<>(ci.getProperties());
        if (ci.getNetFactoryName() != null)
            config.put(ConnectionSetting.NET_FACTORY_NAME.name(), ci.getNetFactoryName());
        if (ci.getNetworkTimeout() > 0)
            config.put(ConnectionSetting.NETWORK_TIMEOUT.name(), String.valueOf(ci.getNetworkTimeout()));

        NetFactory factory = NetFactoryManager.getFactory(config, true);
        netClient = factory.getNetClient();
        NetNode node = NetNode.createTCP(server);
        // 多个客户端session会共用同一条TCP连接
        netClient.createConnection(config, node).onComplete(ar -> {
            if (ar.isSucceeded()) {
                AsyncConnection conn = ar.getResult();
                if (!(conn instanceof TcpClientConnection)) {
                    RuntimeException e = DbException
                            .getInternalError("not tcp client connection: " + conn.getClass().getName());
                    ac.setAsyncResult(e);
                    return;
                }

                TcpClientConnection tcpConnection = (TcpClientConnection) conn;
                // 每一个通过网络传输的协议包都会带上sessionId，
                // 这样就能在同一条TCP连接中区分不同的客户端session了
                int sessionId = tcpConnection.getNextId();
                ClientSession clientSession = new ClientSession(tcpConnection, ci, server, parent,
                        sessionId);
                clientSession.setSingleThreadCallback(ci.isSingleThreadCallback());
                tcpConnection.addSession(sessionId, clientSession);

                SessionInit packet = new SessionInit(ci);
                AckPacketHandler<ClientSession, SessionInitAck> ackPacketHandler = ack -> {
                    clientSession.setProtocolVersion(ack.clientVersion);
                    clientSession.setAutoCommit(ack.autoCommit);
                    clientSession.setTargetNodes(ack.targetNodes);
                    clientSession.setRunMode(ack.runMode);
                    clientSession.setInvalid(ack.invalid);
                    clientSession.setConsistencyLevel(ack.consistencyLevel);
                    return clientSession;
                };
                Future<ClientSession> f = clientSession.send(packet, ackPacketHandler);
                f.onComplete(ar2 -> {
                    ac.setAsyncResult(ar2);
                });
            } else {
                ac.setAsyncResult(ar.getCause());
            }
        });
    }

    private static void redirectIfNeeded(AutoReconnectSession parent, ClientSession clientSession,
            ConnectionInfo ci, AsyncCallback<Session> topAc) {
        if (clientSession.isInvalid()) {
            switch (clientSession.getRunMode()) {
            case CLIENT_SERVER:
            case SHARDING: {
                ConnectionInfo ci2 = ci.copy(clientSession.getTargetNodes());
                // 关闭当前session,因为连到的节点不是所要的
                clientSession.close();
                createSession(ci2, false, topAc);
                break;
            }
            default:
                topAc.setAsyncResult(DbException.getInternalError());
            }
        } else {
            parent.setSession(clientSession);
            topAc.setAsyncResult(parent);
        }
    }
}
