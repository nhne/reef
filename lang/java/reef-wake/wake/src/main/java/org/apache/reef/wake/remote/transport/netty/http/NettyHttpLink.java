/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.reef.wake.remote.transport.netty.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.*;
import org.apache.reef.wake.remote.Encoder;
import org.apache.reef.wake.remote.transport.Link;
import org.apache.reef.wake.remote.transport.LinkListener;

import java.net.SocketAddress;
import java.net.URI;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Link implementation with Netty Http.
 *
 * If you set a {@code LinkListener<T>}, it keeps message until writeAndFlush operation completes
 * and notifies whether the sent message transferred successfully through the listener.
 */
public class NettyHttpLink<T> implements Link<T> {

  public static final int INT_SIZE = Integer.SIZE / Byte.SIZE;

  private static final Logger LOG = Logger.getLogger(NettyHttpLink.class.getName());

  private final Channel channel;
  private final Encoder<? super T> encoder;
  private final LinkListener<? super T> listener;
  private final URI uri;

  /**
   * Constructs a link.
   *
   * @param channel the channel
   * @param encoder the encoder
   */
  public NettyHttpLink(final Channel channel, final Encoder<? super T> encoder) {
    this(channel, encoder, null, URI.create("http://127.0.0.1"));
  }

  /**
   * Constructs a link.
   *
   * @param channel  the channel
   * @param encoder  the encoder
   * @param listener the link listener
   * @param uri the URI
   */
  public NettyHttpLink(
          final Channel channel,
          final Encoder<? super T> encoder,
          final LinkListener<? super T> listener,
          final URI uri) {
    this.channel = channel;
    this.encoder = encoder;
    this.listener = listener;
    this.uri = uri;
  }

  /**
   * Writes the message to this link via HTTP.
   *
   * @param message the message
   */
  @Override
  public void write(final T message) {

    try {
      LOG.log(Level.FINE, "write {0} :: {1}", new Object[] {channel, message});
      FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, uri.getRawPath());
      request.headers().set(HttpHeaders.Names.HOST, uri.getHost());
      request.headers().set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.KEEP_ALIVE);
      request.headers().set(HttpHeaders.Names.ACCEPT_ENCODING, HttpHeaders.Values.GZIP);
      request.headers().set(HttpHeaders.Names.CONTENT_TYPE, "application/wake-transport");
      ByteBuf buf = Unpooled.copiedBuffer(encoder.encode(message));
      request.headers().set(HttpHeaders.Names.CONTENT_LENGTH, buf.readableBytes());
      request.content().clear().writeBytes(buf);
      final ChannelFuture future = channel.writeAndFlush(request);
      future.sync();
      if (listener !=  null) {
        future.addListener(new NettyHttpChannelFutureListener<>(message, listener));
      }
    } catch (InterruptedException ex) {
      LOG.log(Level.SEVERE, "Cannot send request to " + uri.getHost(), ex);
    }
  }

  /**
   * Gets a local address of the link.
   *
   * @return a local socket address
   */
  @Override
  public SocketAddress getLocalAddress() {
    return channel.localAddress();
  }

  /**
   * Gets a remote address of the link.
   *
   * @return a remote socket address
   */
  @Override
  public SocketAddress getRemoteAddress() {
    return channel.remoteAddress();
  }

  @Override
  public String toString() {
    return "NettyHttpLink: " + channel; // Channel has good .toString() implementation
  }
}

class NettyHttpChannelFutureListener<T> implements ChannelFutureListener {

  private final T message;
  private LinkListener<T> listener;

  NettyHttpChannelFutureListener(final T message, final LinkListener<T> listener) {
    this.message = message;
    this.listener = listener;
  }

  @Override
  public void operationComplete(final ChannelFuture channelFuture) throws Exception {
    if (channelFuture.isSuccess()) {
      listener.onSuccess(message);
    } else {
      listener.onException(channelFuture.cause(), channelFuture.channel().remoteAddress(), message);
    }
  }
}
