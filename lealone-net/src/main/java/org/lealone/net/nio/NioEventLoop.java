/*
 * Copyright Lealone Database Group.
 * Licensed under the Server Side Public License, v 1.
 * Initial Developer: zhh
 */
package org.lealone.net.nio;

import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.lealone.common.exceptions.DbException;
import org.lealone.common.logging.Logger;
import org.lealone.common.logging.LoggerFactory;
import org.lealone.common.util.MapUtils;
import org.lealone.common.util.SystemPropertyUtils;
import org.lealone.db.DataBuffer;
import org.lealone.net.AsyncConnection;
import org.lealone.net.NetBuffer;
import org.lealone.net.NetEventLoop;

class NioEventLoop implements NetEventLoop {

    private static final Logger logger = LoggerFactory.getLogger(NioEventLoop.class);

    private final Map<SocketChannel, ConcurrentLinkedQueue<NioBuffer>> channels = new ConcurrentHashMap<>();
    private final Map<SocketChannel, SelectionKey> keys = new ConcurrentHashMap<>();

    private final AtomicInteger writeQueueSize = new AtomicInteger();
    private final AtomicBoolean selecting = new AtomicBoolean(false);
    private Selector selector;
    private final long loopInterval;
    private int maxPacketCountPerLoop; // 每次循环最多读取多少个数据包
    private int maxPacketSize;
    private Object owner;

    private final boolean isLoggerEnabled;
    private final boolean isDebugEnabled;

    public NioEventLoop(Map<String, String> config, String loopIntervalKey, long defaultLoopInterval)
            throws IOException {
        loopInterval = MapUtils.getLong(config, loopIntervalKey, defaultLoopInterval);
        maxPacketCountPerLoop = MapUtils.getInt(config, "max_packet_count_per_loop", 20);
        maxPacketSize = MapUtils.getInt(config, "max_packet_size", 8 * 1024 * 1024);
        selector = Selector.open();

        isLoggerEnabled = SystemPropertyUtils.getBoolean("client_logger_enabled", true);
        isDebugEnabled = logger.isDebugEnabled() && isLoggerEnabled;
    }

    @Override
    public Object getOwner() {
        return owner;
    }

    @Override
    public void setOwner(Object owner) {
        this.owner = owner;
    }

    @Override
    public Selector getSelector() {
        return selector;
    }

    @Override
    public void select() throws IOException {
        select(loopInterval);
    }

    private volatile boolean haveWork;

    @Override
    public void select(long timeout) throws IOException {
        if (selecting.compareAndSet(false, true)) {
            if (haveWork) {
                haveWork = false;
            } else {
                selector.select(timeout);
            }
            selecting.set(false);
        }
    }

    @Override
    public void wakeup() {
        haveWork = true;
        if (selecting.compareAndSet(true, false)) {
            selector.wakeup();
        }
    }

    @Override
    public void register(AsyncConnection conn) {
        NioAttachment attachment = new NioAttachment();
        attachment.conn = conn;
        SocketChannel channel = conn.getWritableChannel().getSocketChannel();
        addSocketChannel(channel);
        try {
            channel.register(getSelector(), SelectionKey.OP_READ, attachment);
        } catch (ClosedChannelException e) {
            throw DbException.convert(e);
        }
    }

    @Override
    public void register(SocketChannel channel, int ops, Object att) throws ClosedChannelException {
        // 当nio-event-loop线程执行selector.select被阻塞时，代码内部依然会占用publicKeys锁，
        // 而另一个线程执行channel.register时，内部也会去要publicKeys锁，从而导致也被阻塞，
        // 所以下面这段代码的用处是:
        // 只要发现nio-event-loop线程正在进行select，那么就唤醒它，并释放publicKeys锁。
        while (true) {
            if (selecting.compareAndSet(false, true)) {
                channel.register(selector, SelectionKey.OP_CONNECT, att);
                selecting.set(false);
                selector.wakeup();
                break;
            } else {
                selector.wakeup();
            }
        }
    }

    @Override
    public void addSocketChannel(SocketChannel channel) {
        channels.putIfAbsent(channel, new ConcurrentLinkedQueue<>());
    }

    @Override
    public void addNetBuffer(SocketChannel channel, NetBuffer netBuffer) {
        NioBuffer nioBuffer = (NioBuffer) netBuffer;
        ConcurrentLinkedQueue<NioBuffer> queue = channels.get(channel);
        if (queue != null) {
            SelectionKey key = keyFor(channel);
            // 当队列不为空时，队首的NioBuffer可能没写完，此时不能写新的NioBuffer
            if (key != null && key.isValid() && Thread.currentThread() == owner && queue.isEmpty()) {
                if (write(key, channel, nioBuffer)) {
                    if ((key.interestOps() & SelectionKey.OP_WRITE) != 0) {
                        deregisterWrite(key);
                    }
                    return;
                }
            }
            writeQueueSize.incrementAndGet();
            queue.add(nioBuffer);
            wakeup();
        }
    }

    private SelectionKey keyFor(SocketChannel channel) {
        SelectionKey key = keys.get(channel);
        if (key == null) {
            key = channel.keyFor(getSelector());
            keys.put(channel, key);
        }
        return key;
    }

    private void registerWrite(SelectionKey key) {
        key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
    }

    private void deregisterWrite(SelectionKey key) {
        key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
    }

    private long totalReadBytes;
    private long totalWrittenBytes;
    private final EOFException endException = new EOFException();
    private String endExceptionMsg;

    @Override
    public void read(SelectionKey key) {
        SocketChannel channel = (SocketChannel) key.channel();
        NioAttachment attachment = (NioAttachment) key.attachment();
        AsyncConnection conn = attachment.conn;
        DataBuffer dataBuffer = attachment.dataBuffer;
        int packetCount = 1;
        try {
            while (true) {
                // 如果客户端关闭连接，服务器再次循环读数据检测到连接已经关闭就不再读取数据，避免抛出异常
                if (conn.isClosed())
                    return;
                // 每次循环重新取一次，一些实现会返回不同的Buffer
                ByteBuffer packetLengthByteBuffer = conn.getPacketLengthByteBuffer();
                if (packetLengthByteBuffer == null) { // http server自己读取数据
                    conn.handle(null);
                    return;
                }
                int packetLengthByteBufferCapacity = packetLengthByteBuffer.capacity();

                if (attachment.state == 0) {
                    boolean ok = read(attachment, channel, packetLengthByteBuffer,
                            packetLengthByteBufferCapacity);
                    if (ok) {
                        attachment.state = 1;
                    } else {
                        break;
                    }
                }
                if (attachment.state == 1) {
                    int packetLength = conn.getPacketLength();
                    if (packetLength > maxPacketSize)
                        throw new IOException("packet too large, maxPacketSize: " + maxPacketSize
                                + ", receive: " + packetLength);
                    if (dataBuffer == null) {
                        dataBuffer = DataBuffer.getOrCreate(packetLength);
                        // 返回的DatBuffer的Capacity可能大于packetLength，所以设置一下limit，不会多读
                        dataBuffer.limit(packetLength);
                    }
                    ByteBuffer buffer = dataBuffer.getBuffer();
                    boolean ok = read(attachment, channel, buffer, packetLength);
                    if (ok) {
                        packetLengthByteBuffer.clear();
                        attachment.state = 0;
                        attachment.dataBuffer = null;
                        NioBuffer nioBuffer = new NioBuffer(dataBuffer, true); // 支持快速回收
                        dataBuffer = null;
                        conn.handle(nioBuffer);
                        if (++packetCount > maxPacketCountPerLoop)
                            break;
                    } else {
                        packetLengthByteBuffer.flip(); // 下次可以重新计算packetLength
                        attachment.dataBuffer = dataBuffer;
                        break;
                    }
                }
            }
        } catch (Exception e) {
            if (endException == e) {
                if (isDebugEnabled)
                    logger.debug((conn.isServer() ? "Client " : "\r\nServer ") + endExceptionMsg);
            } else {
                if (isLoggerEnabled)
                    logger.warn("Failed to read", e);
            }
            conn.handleException(e);
            closeChannel(channel);
        }
    }

    private boolean read(NioAttachment attachment, SocketChannel channel, ByteBuffer buffer, int length)
            throws Exception {
        int readBytes = channel.read(buffer);
        if (readBytes > 0) {
            if (isDebugEnabled) {
                totalReadBytes += readBytes;
                logger.debug(("total read bytes: " + totalReadBytes));
            }
            attachment.endOfStreamCount = 0;
        } else {
            // 客户端非正常关闭时，可能会触发JDK的bug，导致run方法死循环，selector.select不会阻塞
            // netty框架在下面这个方法的代码中有自己的不同解决方案
            // io.netty.channel.nio.NioEventLoop.processSelectedKey
            if (readBytes < 0) {
                attachment.endOfStreamCount++;
                if (attachment.endOfStreamCount > 3) {
                    endExceptionMsg = "socket channel closed: " + channel.getRemoteAddress();
                    throw endException;
                }
            }
        }
        if (length == buffer.position()) {
            buffer.flip();
            return true;
        }
        return false;
    }

    @Override
    public void write() {
        if (writeQueueSize.get() > 0) {
            for (Entry<SocketChannel, ConcurrentLinkedQueue<NioBuffer>> entry : channels.entrySet()) {
                ConcurrentLinkedQueue<NioBuffer> queue = entry.getValue();
                if (!queue.isEmpty()) {
                    SocketChannel channel = entry.getKey();
                    SelectionKey key = keyFor(channel);
                    if (key != null && key.isValid()) {
                        write(key, channel, queue);
                    }
                }
            }
        }
    }

    @Override
    public void write(SelectionKey key) {
        SocketChannel channel = (SocketChannel) key.channel();
        ConcurrentLinkedQueue<NioBuffer> queue = channels.get(channel);
        if (!queue.isEmpty()) {
            write(key, channel, queue);
        }
    }

    private void write(SelectionKey key, SocketChannel channel, ConcurrentLinkedQueue<NioBuffer> queue) {
        for (NioBuffer nioBuffer : queue) {
            if (write(key, channel, nioBuffer)) {
                queue.remove(nioBuffer);
                writeQueueSize.decrementAndGet();
            } else {
                break; // 只要有一个没有写完，后面的就先不用写了
            }
        }
        // 还是要检测key是否是有效的，否则会抛CancelledKeyException
        if ((key.interestOps() & SelectionKey.OP_WRITE) != 0 && queue.isEmpty() && key.isValid()) {
            deregisterWrite(key);
        }
    }

    private boolean write(SelectionKey key, SocketChannel channel, NioBuffer nioBuffer) {
        ByteBuffer buffer = nioBuffer.getByteBuffer();
        int remaining = buffer.remaining();
        try {
            // 一定要用while循环来写，否则会丢数据！
            while (remaining > 0) {
                int writtenBytes = channel.write(buffer);
                if (writtenBytes <= 0) {
                    if (key.isValid() && (key.interestOps() & SelectionKey.OP_WRITE) == 0) {
                        registerWrite(key);
                    }
                    return false; // 还没有写完
                }
                remaining -= writtenBytes;
                if (isDebugEnabled) {
                    totalWrittenBytes += writtenBytes;
                    logger.debug(("total written bytes: " + totalWrittenBytes));
                }
            }
        } catch (IOException e) {
            closeChannel(channel);
        }
        nioBuffer.recycle();
        return true;
    }

    @Override
    public void closeChannel(SocketChannel channel) {
        if (channel == null || !channels.containsKey(channel)) {
            return;
        }
        for (SelectionKey key : selector.keys()) {
            if (key.channel() == channel && key.isValid()) {
                key.cancel();
                break;
            }
        }
        channels.remove(channel);
        keys.remove(channel);
        closeChannelSilently(channel);
    }

    @Override
    public void close() {
        try {
            Selector selector = this.selector;
            this.selector = null;
            selector.wakeup();
            selector.close();
        } catch (Exception e) {
        }
    }

    // 这个方法没有放在ServerAccepter类，避免退出客户端时触发
    // NioEventLoopClient-ShutdownHook-0 WARN Unable to register Log4j shutdown hook
    // because JVM is shutting down. Using SimpleLogger
    static void closeChannelSilently(SocketChannel channel) {
        if (channel != null) {
            Socket socket = channel.socket();
            if (socket != null) {
                try {
                    socket.close();
                } catch (Throwable e) {
                }
            }
            try {
                channel.close();
            } catch (Throwable e) {
            }
        }
    }
}
