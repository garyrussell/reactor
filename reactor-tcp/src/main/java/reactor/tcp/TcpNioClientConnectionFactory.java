/*
 * Copyright 2002-2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.tcp;

import reactor.fn.Supplier;
import reactor.tcp.codec.Codec;
import reactor.util.Assert;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * A client connection factory that creates {@link TcpNioConnection}s.
 *
 * @author Gary Russell
 */
public class TcpNioClientConnectionFactory<T> extends AbstractClientConnectionFactory<T> implements Runnable {

	private final Map<SocketChannel, TcpNioConnection<T>> channelMap = new ConcurrentHashMap<SocketChannel, TcpNioConnection<T>>();

	private final BlockingQueue<SocketChannel> newChannels = new LinkedBlockingQueue<SocketChannel>();

	private volatile TcpNioConnectionConfigurer tcpNioConnectionSupport = new DefaultTcpNioConnectionConfigurer();

	private volatile boolean usingDirectBuffers;

	/**
	 * Creates a TcpNioClientConnectionFactory for connections to the host and port.
	 *
	 * @param host the host
	 * @param port the port
	 */
	public TcpNioClientConnectionFactory(String host, int port, Supplier<Codec<T>> codecSupplier) {
		super(host, port, codecSupplier);
	}

	/**
	 * @throws Exception
	 * @throws IOException
	 * @throws SocketException
	 */
	@Override
	protected TcpConnectionSupport<T> obtainConnection() throws Exception {
		TcpConnectionSupport<T> theConnection = this.getTheConnection();
		if (theConnection != null && theConnection.isOpen()) {
			return theConnection;
		}
		if (logger.isDebugEnabled()) {
			logger.debug("Opening new socket channel connection to " + this.getHost() + ":" + this.getPort());
		}
		SocketChannel socketChannel = SocketChannel.open(new InetSocketAddress(this.getHost(), this.getPort()));
		applySocketAttributes(socketChannel.socket());
		TcpNioConnection<T> connection = this.tcpNioConnectionSupport.createNewConnection(
				socketChannel, false, this.isLookupHost(), this, this.getCodecSupplier());
		connection.setUsingDirectBuffers(this.usingDirectBuffers);
		initializeConnection(connection, socketChannel.socket());
		socketChannel.configureBlocking(false);
		if (this.getSoTimeout() > 0) {
			connection.setLastRead(System.currentTimeMillis());
		}
		this.channelMap.put(socketChannel, connection);
		this.addConnection(connection);
		newChannels.add(socketChannel);
		this.getIoSelector().wakeup();
		return connection;
	}

	/**
	 * When set to true, connections created by this factory attempt to use direct buffers where possible.
	 *
	 * @param usingDirectBuffers
	 * @see ByteBuffer
	 */
	public void setUsingDirectBuffers(boolean usingDirectBuffers) {
		this.usingDirectBuffers = usingDirectBuffers;
	}

	public void setTcpNioConnectionSupport(TcpNioConnectionConfigurer tcpNioSupport) {
		Assert.notNull(tcpNioSupport, "TcpNioSupport must not be null");
		this.tcpNioConnectionSupport = tcpNioSupport;
	}

	@Override
	public void close() {
		Selector selector;
		if ((selector = this.getIoSelector()) != null) {
			selector.wakeup();
		}
	}

	@Override
	public TcpNioClientConnectionFactory<T> start() {
		synchronized (this.lifecycleMonitor) {
			if (!this.isActive()) {
				super.start();
				this.setActive(true);
				this.getTaskExecutor().execute(this);
			}
		}

		return this;
	}

	public void run() {
		if (logger.isDebugEnabled()) {
			logger.debug("Read selector running for connections to " + this.getHost() + ":" + this.getPort());
		}
		try {
			Selector selector = this.getIoSelector();
			while (this.isActive()) {
				registerNewChannelsIfAny(selector);
				doSelect(selector);
			}
		} catch (Exception e) {
			logger.error("Exception in read selector thread", e);
			this.setActive(false);
		}
		if (logger.isDebugEnabled()) {
			logger.debug("Read selector exiting for connections to " + this.getHost() + ":" + this.getPort());
		}
	}

	private void registerNewChannelsIfAny(Selector selector) {
		SocketChannel newChannel;
		while ((newChannel = newChannels.poll()) != null) {
			try {
				newChannel.register(selector, SelectionKey.OP_READ, channelMap.get(newChannel));
			} catch (ClosedChannelException cce) {
				if (logger.isDebugEnabled()) {
					logger.debug("Channel closed before registering with selector for reading");
				}
			}
		}
	}

	/**
	 * @return the usingDirectBuffers
	 */
	protected boolean isUsingDirectBuffers() {
		return usingDirectBuffers;
	}

	/**
	 * @return the connections
	 */
	protected Map<SocketChannel, TcpNioConnection<T>> getConnections() {
		return channelMap;
	}

	/**
	 * @return the newChannels
	 */
	protected BlockingQueue<SocketChannel> getNewChannels() {
		return newChannels;
	}
}
