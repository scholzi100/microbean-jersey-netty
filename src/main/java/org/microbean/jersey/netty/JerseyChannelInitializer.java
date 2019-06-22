/* -*- mode: Java; c-basic-offset: 2; indent-tabs-mode: nil; coding: utf-8-unix -*-
 *
 * Copyright © 2019 microBean™.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package org.microbean.jersey.netty;

import java.net.URI;

import javax.ws.rs.core.Application;

import io.netty.bootstrap.ServerBootstrap; // for javadoc only

import io.netty.buffer.ByteBufAllocator;

import io.netty.channel.ChannelHandler; // for javadoc only
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;

import io.netty.channel.socket.SocketChannel;

import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerExpectContinueHandler;
import io.netty.handler.codec.http.HttpServerUpgradeHandler;

import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2MultiplexCodecBuilder;
import io.netty.handler.codec.http2.Http2ServerUpgradeCodec;
import io.netty.handler.codec.http2.CleartextHttp2ServerUpgradeHandler;

import io.netty.handler.logging.LoggingHandler;

import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;

import io.netty.handler.stream.ChunkedWriteHandler;

import io.netty.util.AsciiString;
import io.netty.util.ReferenceCountUtil;

import org.glassfish.jersey.server.ApplicationHandler;

/**
 * A {@link ChannelInitializer} that sets up <a
 * href="https://jersey.github.io/">Jersey</a> integration.
 *
 * <p>An instance of this class should be all you need when setting up
 * your channel pipeline.  It handles HTTP 1.1 and HTTP/2
 * requests as well as TLS.</p>
 *
 * <p>To use, install it as the {@linkplain
 * ServerBootstrap#childHandler(ChannelHandler) child handler} of a
 * {@link ServerBootstrap}:</p>
 *
 * <blockquote><pre>{@link ServerBootstrap serverBootstrap}.{@link ServerBootstrap#childHandler(ChannelHandler) childHandler}(new {@link JerseyChannelInitializer}(baseUri,
 *    {@link SslContext sslContext},
 *    {@link HttpServerUpgradeHandler#maxContentLength() Long.MAX_VALUE},
 *    new {@link ApplicationHandler}({@link Application yourJaxRsApplication})));</pre></blockquote>
 *
 * @author <a href="https://about.me/lairdnelson"
 * target="_parent">Laird Nelson</a>
 *
 * @see ChannelInitializer
 *
 * @see #initChannel(SocketChannel)
 *
 * @see JerseyChannelInboundHandler
 */
public class JerseyChannelInitializer extends ChannelInitializer<SocketChannel> {


  /*
   * Instance fields.
   */


  /**
   * The base {@link URI} for the Jersey application.
   *
   * <p>This field is never {@code null}.</p>
   *
   * @see #JerseyChannelInitializer(URI, SslContext, long,
   * ApplicationHandler)
   */
  private final URI baseUri;

  /**
   * An {@link SslContext} that may be used to {@linkplain
   * #createSslHandler(SslContext, ByteBufAllocator) create an
   * <code>SslHandler</code>}.
   *
   * <p>This field may be {@code null}.</p>
   *
   * @see #JerseyChannelInitializer(URI, SslContext, long,
   * ApplicationHandler)
   *
   * @see SslContext
   *
   * @see #createSslHandler(SslContext, ByteBufAllocator)
   */
  private final SslContext sslContext;

  /**
   * In the case of HTTP to HTTP/2
   * upgrades, this field governs the maximum permitted incoming
   * entity length in bytes; if less than {@code 0} then {@link
   * Long#MAX_VALUE} will be used instead; if exactly {@code 0} then
   * if the HTTP message containing the upgrade header is something
   * like a {@code POST} it will be rejected with a {@code 413} error
   * code.
   *
   * @see #JerseyChannelInitializer(URI, SslContext, long,
   * ApplicationHandler)
   *
   * @see HttpServerUpgradeHandler#maxContentLength()
   */
  private final long maxIncomingContentLength;

  /**
   * The {@link ApplicationHandler} representing Jersey.
   *
   * <p>This field is never {@code null}.</p>
   *
   * @see #JerseyChannelInitializer(URI, SslContext, long,
   * ApplicationHandler)
   *
   * @see ApplicationHandler
   */
  private final ApplicationHandler applicationHandler;


  /*
   * Constructors.
   */


  /**
   * Creates a new {@link JerseyChannelInitializer}.
   *
   * @see #JerseyChannelInitializer(URI, SslContext, long,
   * ApplicationHandler)
   */
  public JerseyChannelInitializer() {
    this(null, null, Long.MAX_VALUE, (ApplicationHandler)null);
  }

  /**
   * Creates a new {@link JerseyChannelInitializer}.
   *
   * @param application the {@link Application} to serve; may be
   * {@code null} in which case a {@linkplain
   * Application#Application() new <code>Application</code>} will be
   * used instead
   *
   * @see #JerseyChannelInitializer(URI, SslContext, long,
   * ApplicationHandler)
   */
  public JerseyChannelInitializer(final Application application) {
    this(null, null, Long.MAX_VALUE, new ApplicationHandler(application == null ? new Application() : application));
  }

  /**
   * Creates a new {@link JerseyChannelInitializer}.
   *
   * @param applicationHandler the {@link ApplicationHandler} hosting
   * the {@link Application} to serve; may be {@code null} in which
   * case a {@linkplain ApplicationHandler#ApplicationHandler() new
   * <code>ApplicationHandler</code>} will be used instead
   *
   * @see #JerseyChannelInitializer(URI, SslContext, long,
   * ApplicationHandler)
   */
  public JerseyChannelInitializer(final ApplicationHandler applicationHandler) {
    this(null, null, Long.MAX_VALUE, applicationHandler);
  }

  /**
   * Creates a new {@link JerseyChannelInitializer}.
   *
   * @param baseUri the base {@link URI} of the Jersey application;
   * may be {@code null} in which case the return value resulting from
   * invoking {@link URI#create(String) URI.create("/")} will be used
   * instead
   *
   * @param application the {@link Application} to serve; may be
   * {@code null} in which case a {@linkplain
   * Application#Application() new <code>Application</code>} will be
   * used instead
   *
   * @see #JerseyChannelInitializer(URI, SslContext, long,
   * ApplicationHandler)
   */
  public JerseyChannelInitializer(final URI baseUri,
                                  final Application application) {
    this(baseUri, null, Long.MAX_VALUE, new ApplicationHandler(application == null ? new Application() : application));
  }

  /**
   * Creates a new {@link JerseyChannelInitializer}.
   *
   * @param baseUri the base {@link URI} of the Jersey application;
   * may be {@code null} in which case the return value resulting from
   * invoking {@link URI#create(String) URI.create("/")} will be used
   * instead
   *
   * @param applicationHandler the {@link ApplicationHandler} hosting
   * the {@link Application} to serve; may be {@code null} in which
   * case a {@linkplain ApplicationHandler#ApplicationHandler() new
   * <code>ApplicationHandler</code>} will be used instead
   *
   * @see #JerseyChannelInitializer(URI, SslContext, long,
   * ApplicationHandler)
   */
  public JerseyChannelInitializer(final URI baseUri,
                                  final ApplicationHandler applicationHandler) {
    this(baseUri, null, Long.MAX_VALUE, applicationHandler);
  }

  /**
   * Creates a new {@link JerseyChannelInitializer}.
   *
   * @param baseUri the base {@link URI} of the Jersey application;
   * may be {@code null} in which case the return value resulting from
   * invoking {@link URI#create(String) URI.create("/")} will be used
   * instead
   *
   * @param sslContext an {@link SslContext} that may be used to
   * {@linkplain #createSslHandler(SslContext, ByteBufAllocator)
   * create an <code>SslHandler</code>}; may be {@code null}
   *
   *
   * @param application the {@link Application} to serve; may be
   * {@code null} in which case a {@linkplain
   * Application#Application() new <code>Application</code>} will be
   * used instead
   *
   * @see #JerseyChannelInitializer(URI, SslContext, long,
   * ApplicationHandler)
   */
  public JerseyChannelInitializer(final URI baseUri,
                                  final SslContext sslContext,
                                  final Application application) {
    this(baseUri, sslContext, Long.MAX_VALUE, new ApplicationHandler(application == null ? new Application() : application));
  }

  /**
   * Creates a new {@link JerseyChannelInitializer}.
   *
   * @param baseUri the base {@link URI} of the Jersey application;
   * may be {@code null} in which case the return value resulting from
   * invoking {@link URI#create(String) URI.create("/")} will be used
   * instead
   *
   * @param sslContext an {@link SslContext} that may be used to
   * {@linkplain #createSslHandler(SslContext, ByteBufAllocator)
   * create an <code>SslHandler</code>}; may be {@code null}
   *
   * @param applicationHandler the {@link ApplicationHandler} hosting
   * the {@link Application} to serve; may be {@code null} in which
   * case a {@linkplain ApplicationHandler#ApplicationHandler() new
   * <code>ApplicationHandler</code>} will be used instead
   *
   * @see #JerseyChannelInitializer(URI, SslContext, long,
   * ApplicationHandler)
   */
  public JerseyChannelInitializer(final URI baseUri,
                                  final SslContext sslContext,
                                  final ApplicationHandler applicationHandler) {
    this(baseUri, sslContext, Long.MAX_VALUE, applicationHandler);
  }

  /**
   * Creates a new {@link JerseyChannelInitializer}.
   *
   * @param baseUri the base {@link URI} of the Jersey application;
   * may be {@code null} in which case the return value resulting from
   * invoking {@link URI#create(String) URI.create("/")} will be used
   * instead
   *
   * @param sslContext an {@link SslContext} that may be used to
   * {@linkplain #createSslHandler(SslContext, ByteBufAllocator)
   * create an <code>SslHandler</code>}; may be {@code null}
   *
   * @param maxIncomingContentLength in the case of HTTP to HTTP/2
   * upgrades, this parameter governs the maximum permitted incoming
   * entity length in bytes; if less than {@code 0} then {@link
   * Long#MAX_VALUE} will be used instead; if exactly {@code 0} then
   * if the HTTP message containing the upgrade header is something
   * like a {@code POST} it will be rejected with a {@code 413} error
   * code
   *
   * @param application the {@link Application} to serve; may be
   * {@code null} in which case a {@linkplain
   * Application#Application() new <code>Application</code>} will be
   * used instead
   *
   * @see #JerseyChannelInitializer(URI, SslContext, long,
   * ApplicationHandler)
   */
  public JerseyChannelInitializer(final URI baseUri,
                                  final SslContext sslContext,
                                  final long maxIncomingContentLength,
                                  final Application application) {
    this(baseUri, sslContext, maxIncomingContentLength, new ApplicationHandler(application == null ? new Application() : application));
  }

  /**
   * Creates a new {@link JerseyChannelInitializer}.
   *
   * @param baseUri the base {@link URI} of the Jersey application;
   * may be {@code null} in which case the return value resulting from
   * invoking {@link URI#create(String) URI.create("/")} will be used
   * instead
   *
   * @param sslContext an {@link SslContext} that may be used to
   * {@linkplain #createSslHandler(SslContext, ByteBufAllocator)
   * create an <code>SslHandler</code>}; may be {@code null}
   *
   * @param maxIncomingContentLength in the case of HTTP to HTTP/2
   * upgrades, this parameter governs the maximum permitted incoming
   * entity length in bytes; if less than {@code 0} then {@link
   * Long#MAX_VALUE} will be used instead; if exactly {@code 0} then
   * if the HTTP message containing the upgrade header is something
   * like a {@code POST} it will be rejected with a {@code 413} error
   * code
   *
   * @param applicationHandler the {@link ApplicationHandler} hosting
   * the {@link Application} to serve; may be {@code null} in which
   * case a {@linkplain ApplicationHandler#ApplicationHandler() new
   * <code>ApplicationHandler</code>} will be used instead
   */
  public JerseyChannelInitializer(final URI baseUri,
                                  final SslContext sslContext,
                                  final long maxIncomingContentLength,
                                  final ApplicationHandler applicationHandler) {
    super();
    this.baseUri = baseUri == null ? URI.create("/") : baseUri;
    this.sslContext = sslContext;
    // It's somewhat odd that Netty's MessageAggregator class (of
    // which HttpServerUpgradeHandler is a subclass) expresses a
    // maximum content length as an int, when Jersey and other
    // HTTP-centric frameworks express it as a long.  We will accept a
    // long and truncate it where necessary.
    if (maxIncomingContentLength < 0L) {
      this.maxIncomingContentLength = Long.MAX_VALUE;
    } else {
      this.maxIncomingContentLength = maxIncomingContentLength;
    }
    this.applicationHandler = applicationHandler == null ? new ApplicationHandler() : applicationHandler;
  }


  /*
   * Instance methods.
   */


  /**
   * Sets up Netty with Jersey application support.
   *
   * @param channel the {@link SocketChannel} representing a networking
   * connection to the outside world; may be {@code null} in which
   * case no action will be taken
   *
   * @see SocketChannel#pipeline()
   *
   * @see ChannelPipeline#addLast(String, ChannelHandler)
   *
   * @see #preInitChannel(SocketChannel)
   *
   * @see #postInitChannel(SocketChannel)
   */
  @Override
  public final void initChannel(final SocketChannel channel) {
    if (channel != null) {
      this.preInitChannel(channel);

      final ChannelPipeline channelPipeline = channel.pipeline();
      assert channelPipeline != null;

      final SslHandler sslHandler;
      if (this.sslContext == null) {
        sslHandler = null;
      } else {
        sslHandler = createSslHandler(this.sslContext, channel.alloc());
      }

      if (sslHandler == null) {

        final HttpServerCodec httpServerCodec = new HttpServerCodec();

        final JerseyChannelSubInitializer jerseyChannelSubInitializer = new JerseyChannelSubInitializer();

        // See https://github.com/netty/netty/issues/7079
        final int maxIncomingContentLength;
        if (this.maxIncomingContentLength >= Integer.MAX_VALUE) {
          maxIncomingContentLength = Integer.MAX_VALUE;
        } else {
          maxIncomingContentLength = (int)this.maxIncomingContentLength;
        }
        final HttpServerUpgradeHandler httpServerUpgradeHandler = new HttpServerUpgradeHandler(httpServerCodec, protocol -> AsciiString.contentEquals(Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME, protocol) ? new Http2ServerUpgradeCodec(Http2MultiplexCodecBuilder.forServer(jerseyChannelSubInitializer).build()) : null, maxIncomingContentLength);

        final CleartextHttp2ServerUpgradeHandler cleartextHttp2ServerUpgradeHandler = new CleartextHttp2ServerUpgradeHandler(httpServerCodec, httpServerUpgradeHandler, jerseyChannelSubInitializer /* <-- use this guy for http2, otherwise do nothing */);
        channelPipeline.addLast(cleartextHttp2ServerUpgradeHandler);

        // We add a handler for the (probably very common) case where
        // no one (a) connected with HTTP/2 or (b) asked for an HTTP/2
        // upgrade.  In this case after all the shenanigans we just
        // jumped through we're just a regular old common HTTP 1.1
        // connection.  Strangely, we have to handle this in Netty as
        // a special case even though it is likely to be the most
        // common one.
        channelPipeline.addLast(new SimpleChannelInboundHandler<HttpMessage>() {
            @Override
            protected final void channelRead0(final ChannelHandlerContext channelHandlerContext, final HttpMessage httpMessage) throws Exception {
              assert channelHandlerContext != null;
              final ChannelPipeline channelPipeline = channelHandlerContext.pipeline();
              assert channelPipeline != null;

              // We know that in "front" of us is an HttpServerCodec
              // because otherwise we wouldn't have been called (note
              // that our event is an HttpMessage).  Now that we know
              // this is going to be HTTP 1.1 with no upgrades,
              // replace this handler with a handler that deals with
              // HTTP 100-class statuses (this handler will never be
              // called again)...
              channelPipeline.replace(this, HttpServerExpectContinueHandler.class.getSimpleName(), new HttpServerExpectContinueHandler());

              // ...and then after that add the initializer that will
              // install a ChunkedWriteHandler followed by the main
              // Jersey integration.
              channelPipeline.addLast(JerseyChannelSubInitializer.class.getName(), jerseyChannelSubInitializer);

              // Forward the event on as we never touched it.
              channelHandlerContext.fireChannelRead(ReferenceCountUtil.retain(httpMessage));
            }
        });

      } else {

        // The SSL handler decodes TLS stuff...
        channelPipeline.addLast(sslHandler.getClass().getSimpleName(), sslHandler);

        // ...then the HttpNegotiationHandler does ALPN
        // (Application-Level Protocol Negotiation) to figure out
        // whether it's HTTP 1.1 or HTTP/2; see the private inner
        // class below for details.
        channelPipeline.addLast(HttpNegotiationHandler.class.getSimpleName(), new HttpNegotiationHandler());

      }

      this.postInitChannel(channel);
    }
  }

  /**
   * A hook for performing {@link SocketChannel} initialization before
   * the Jersey integration is set up.
   *
   * <p>This implementation {@linkplain
   * ChannelPipeline#addLast(String, ChannelHandler) installs} a
   * {@link LoggingHandler}.</p>
   *
   * <p>Overrides must not call {@link #initChannel(SocketChannel)} or
   * an infinite loop will result.</p>
   *
   * @param channel the {@link SocketChannel} being {@linkplain
   * #initChannel(SocketChannel) initialized}; may be {@code null} in
   * which case no action will be taken
   *
   * @see #initChannel(SocketChannel)
   *
   * @see ChannelPipeline#addLast(String, ChannelHandler)
   */
  protected void preInitChannel(final SocketChannel channel) {
    if (channel != null) {
      final ChannelPipeline channelPipeline = channel.pipeline();
      assert channelPipeline != null;
      channelPipeline.addLast("LoggingHandler", new LoggingHandler());
    }
  }

  /**
   * A hook for performing {@link SocketChannel} initialization after
   * the Jersey integration is set up.
   *
   * <p>This implementation does nothing.</p>
   *
   * <p>Overrides must not call {@link #initChannel(SocketChannel)} or
   * an infinite loop will result.</p>
   *
   * @param channel the {@link SocketChannel} being {@linkplain
   * #initChannel(SocketChannel) initialized}; may be {@code null} in
   * which case no action will be taken
   *
   * @see #initChannel(SocketChannel)
   *
   * @see ChannelPipeline#addLast(String, ChannelHandler)
   */
  protected void postInitChannel(final SocketChannel channel) {

  }

  /**
   * Creates and returns a new {@link SslHandler} when invoked.
   *
   * <p>This method never returns {@code null}.</p>
   *
   * <p>Overrides of this method must not return {@code null}.</p>
   *
   * @param sslContext the {@link SslContext} that may assist in the
   * creation; must not be {@code null}
   *
   * @param byteBufAllocator a {@link ByteBufAllocator} that may
   * assist in the creation; must not be {@code null}
   *
   * @return a new {@link SslHandler}; never {@code null}
   *
   * @see SslContext#newHandler(ByteBufAllocator)
   */
  protected SslHandler createSslHandler(final SslContext sslContext, final ByteBufAllocator byteBufAllocator) {
    return sslContext.newHandler(byteBufAllocator);
  }


  /*
   * Inner classes.
   */


  /**
   * A {@link ChannelInitializer} that {@linkplain
   * ChannelPipeline#addLast(String, ChannelHandler) adds} a {@link
   * ChunkedWriteHandler} and a {@link JerseyChannelInboundHandler}.
   *
   * @author <a href="https://about.me/lairdnelson"
   * target="_parent">Laird Nelson</a>
   *
   * @see ChunkedWriteHandler
   *
   * @see JerseyChannelInboundHandler
   *
   * @see ChannelInitializer
   */
  private final class JerseyChannelSubInitializer extends ChannelInitializer<SocketChannel> {

    /**
     * Creates a new {@link JerseyChannelSubInitializer}.
     */
    private JerseyChannelSubInitializer() {
      super();
    }

    /**
     * {@linkplain ChannelPipeline#addLast(String, ChannelHandler)
     * Adds} a {@link ChunkedWriteHandler} and a {@link
     * JerseyChannelInboundHandler} to the {@linkplain
     * SocketChannel#pipeline() pipeline}.
     *
     * @param channel the {@link SocketChannel} being configured; must
     * not be {@code null}
     *
     * @see ChunkedWriteHandler
     *
     * @see JerseyChannelInboundHandler
     */
    @Override
    protected final void initChannel(final SocketChannel channel) {
      assert channel != null;
      final ChannelPipeline channelPipeline = channel.pipeline();
      assert channelPipeline != null;
      channelPipeline.addLast(ChunkedWriteHandler.class.getSimpleName(), new ChunkedWriteHandler());
      channelPipeline.addLast(JerseyChannelInboundHandler.class.getSimpleName(), new JerseyChannelInboundHandler(baseUri, applicationHandler));
    }

  }

  /**
   * An {@link ApplicationProtocolNegotiationHandler} that knows how
   * to configure a {@link ChannelPipeline} for HTTP 1.1 or HTTP/2
   * requests that require Jersey integration.
   *
   * @author <a href="https://about.me/lairdnelson"
   * target="_parent">Laird Nelson</a>
   *
   * @see ApplicationProtocolNegotiationHandler
   */
  private final class HttpNegotiationHandler extends ApplicationProtocolNegotiationHandler {

    /**
     * Creates a new {@link HttpNegotiationHandler}.
     */
    private HttpNegotiationHandler() {
      super(ApplicationProtocolNames.HTTP_1_1);
    }

    /**
     * Sets up the {@linkplain ChannelHandlerContext#pipeline()
     * current pipeline} for HTTP 1.1 or HTTP/2 requests that require
     * Jersey integration.
     *
     * @param channelHandlerContext a {@link ChannelHandlerContext}
     * representing the current Netty execution; must not be {@code
     * null}
     *
     * @param protocol the protocol that was negotiated; must be equal
     * to either {@link ApplicationProtocolNames#HTTP_2} or {@link
     * ApplicationProtocolNames#HTTP_1_1}
     */
    @Override
    protected final void configurePipeline(final ChannelHandlerContext channelHandlerContext, final String protocol) {
      assert channelHandlerContext != null;
      assert protocol != null;
      final ChannelPipeline channelPipeline = channelHandlerContext.pipeline();
      assert channelPipeline != null;

      switch (protocol) {
      case ApplicationProtocolNames.HTTP_2:
        channelPipeline.addLast(Http2MultiplexCodecBuilder.forServer(new JerseyChannelSubInitializer()).build());
        break;
      case ApplicationProtocolNames.HTTP_1_1:
        channelPipeline.addLast(HttpServerCodec.class.getSimpleName(), new HttpServerCodec());
        channelPipeline.addLast(HttpServerExpectContinueHandler.class.getSimpleName(), new HttpServerExpectContinueHandler());
        channelPipeline.addLast(JerseyChannelSubInitializer.class.getSimpleName(), new JerseyChannelSubInitializer());
        break;
      default:
        throw new IllegalArgumentException("protocol: " + protocol);
      }
    }

  }

}