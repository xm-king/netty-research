/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec.http;

import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.List;

import static io.netty.handler.codec.http.HttpHeaders.*;

/**
 * A {@link ChannelHandler} that aggregates an {@link HttpMessage}
 * and its following {@link HttpContent}s into a single {@link HttpMessage} with
 * no following {@link HttpContent}s.  It is useful when you don't want to take
 * care of HTTP messages whose transfer encoding is 'chunked'.  Insert this
 * handler after {@link HttpObjectDecoder} in the {@link ChannelPipeline}:
 * <pre>
 * {@link ChannelPipeline} p = ...;
 * ...
 * p.addLast("encoder", new {@link HttpResponseEncoder}());
 * p.addLast("decoder", new {@link HttpRequestDecoder}());
 * p.addLast("aggregator", <b>new {@link HttpObjectAggregator}(1048576)</b>);
 * ...
 * p.addLast("handler", new HttpRequestHandler());
 * </pre>
 */
public class HttpObjectAggregator extends MessageToMessageDecoder<HttpObject> {

    /**
     * @deprecated Will be removed in the next minor version bump.
     */
    @Deprecated
    public static final int DEFAULT_MAX_COMPOSITEBUFFER_COMPONENTS = 1024; // TODO: Make it private in the next bump.

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(HttpObjectAggregator.class);

    private static final FullHttpResponse CONTINUE = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE, Unpooled.EMPTY_BUFFER);
    private static final FullHttpResponse TOO_LARGE = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, Unpooled.EMPTY_BUFFER);

    static {
        TOO_LARGE.headers().set(Names.CONTENT_LENGTH, 0);
    }

    private final int maxContentLength;
    private FullHttpMessage currentMessage;
    private boolean handlingOversizedMessage;

    private int maxCumulationBufferComponents = DEFAULT_MAX_COMPOSITEBUFFER_COMPONENTS;
    private ChannelHandlerContext ctx;

    /**
     * Creates a new instance.
     *
     * @param maxContentLength
     *        the maximum length of the aggregated content.
     *        If the length of the aggregated content exceeds this value,
     *        {@link #handleOversizedMessage(ChannelHandlerContext, HttpMessage)}
     *        will be called.
     */
    public HttpObjectAggregator(int maxContentLength) {
        if (maxContentLength <= 0) {
            throw new IllegalArgumentException(
                    "maxContentLength must be a positive integer: " +
                    maxContentLength);
        }
        this.maxContentLength = maxContentLength;
    }

    /**
     * Returns the maximum number of components in the cumulation buffer.  If the number of
     * the components in the cumulation buffer exceeds this value, the components of the
     * cumulation buffer are consolidated into a single component, involving memory copies.
     * The default value of this property is {@value #DEFAULT_MAX_COMPOSITEBUFFER_COMPONENTS}.
     */
    public final int getMaxCumulationBufferComponents() {
        return maxCumulationBufferComponents;
    }

    /**
     * Sets the maximum number of components in the cumulation buffer.  If the number of
     * the components in the cumulation buffer exceeds this value, the components of the
     * cumulation buffer are consolidated into a single component, involving memory copies.
     * The default value of this property is {@value #DEFAULT_MAX_COMPOSITEBUFFER_COMPONENTS}
     * and its minimum allowed value is {@code 2}.
     */
    public final void setMaxCumulationBufferComponents(int maxCumulationBufferComponents) {
        if (maxCumulationBufferComponents < 2) {
            throw new IllegalArgumentException(
                    "maxCumulationBufferComponents: " + maxCumulationBufferComponents +
                    " (expected: >= 2)");
        }

        if (ctx == null) {
            this.maxCumulationBufferComponents = maxCumulationBufferComponents;
        } else {
            throw new IllegalStateException(
                    "decoder properties cannot be changed once the decoder is added to a pipeline.");
        }
    }

    @Override
    protected void decode(final ChannelHandlerContext ctx, HttpObject msg, List<Object> out) throws Exception {
        FullHttpMessage currentMessage = this.currentMessage;

        if (msg instanceof HttpMessage) {
            handlingOversizedMessage = false;
            assert currentMessage == null;

            HttpMessage m = (HttpMessage) msg;

            // if content length is set, preemptively close if it's too large
            if (isContentLengthSet(m)) {
                if (getContentLength(m) > maxContentLength) {
                    // handle oversized message
                    invokeHandleOversizedMessage(ctx, m);
                    return;
                }
            }

            // Handle the 'Expect: 100-continue' header if necessary.
            if (is100ContinueExpected(m)) {
                ctx.writeAndFlush(CONTINUE).addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        if (!future.isSuccess()) {
                            ctx.fireExceptionCaught(future.cause());
                        }
                    }
                });
            }

            if (!m.getDecoderResult().isSuccess()) {
                removeTransferEncodingChunked(m);
                out.add(toFullMessage(m));
                this.currentMessage = null;
                return;
            }
            if (msg instanceof HttpRequest) {
                HttpRequest header = (HttpRequest) msg;
                this.currentMessage = currentMessage = new DefaultFullHttpRequest(header.getProtocolVersion(),
                        header.getMethod(), header.getUri(), Unpooled.compositeBuffer(maxCumulationBufferComponents));
            } else if (msg instanceof HttpResponse) {
                HttpResponse header = (HttpResponse) msg;
                this.currentMessage = currentMessage = new DefaultFullHttpResponse(
                        header.getProtocolVersion(), header.getStatus(),
                        Unpooled.compositeBuffer(maxCumulationBufferComponents));
            } else {
                throw new Error();
            }

            currentMessage.headers().set(m.headers());

            // A streamed message - initialize the cumulative buffer, and wait for incoming chunks.
            removeTransferEncodingChunked(currentMessage);
        } else if (msg instanceof HttpContent) {
            if (handlingOversizedMessage) {
                if (msg instanceof LastHttpContent) {
                    this.currentMessage = null;
                }
                // already detect the too long frame so just discard the content
                return;
            }
            assert currentMessage != null;

            // Merge the received chunk into the content of the current message.
            HttpContent chunk = (HttpContent) msg;
            CompositeByteBuf content = (CompositeByteBuf) currentMessage.content();

            if (content.readableBytes() > maxContentLength - chunk.content().readableBytes()) {
                // handle oversized message
                invokeHandleOversizedMessage(ctx, currentMessage);
                return;
            }

            // Append the content of the chunk
            if (chunk.content().isReadable()) {
                chunk.retain();
                content.addComponent(chunk.content());
                content.writerIndex(content.writerIndex() + chunk.content().readableBytes());
            }

            final boolean last;
            if (!chunk.getDecoderResult().isSuccess()) {
                currentMessage.setDecoderResult(
                        DecoderResult.failure(chunk.getDecoderResult().cause()));
                last = true;
            } else {
                last = chunk instanceof LastHttpContent;
            }

            if (last) {
                // Merge trailing headers into the message.
                if (chunk instanceof LastHttpContent) {
                    LastHttpContent trailer = (LastHttpContent) chunk;
                    currentMessage.headers().add(trailer.trailingHeaders());
                }

                // Set the 'Content-Length' header.
                currentMessage.headers().set(
                        HttpHeaders.Names.CONTENT_LENGTH,
                        String.valueOf(content.readableBytes()));

                // All done
                out.add(currentMessage);
                this.currentMessage = null;
            }
        } else {
            throw new Error();
        }
    }

    private void invokeHandleOversizedMessage(ChannelHandlerContext ctx, HttpMessage msg) throws Exception {
        handlingOversizedMessage = true;
        currentMessage = null;
        try {
            handleOversizedMessage(ctx, msg);
        } finally {
            // Release the message in case it is a full one.
            ReferenceCountUtil.release(msg);

            if (msg instanceof HttpRequest) {
                // If an oversized request was handled properly and the connection is still alive
                // (i.e. rejected 100-continue). the decoder should prepare to handle a new message.
                HttpObjectDecoder decoder = ctx.pipeline().get(HttpObjectDecoder.class);
                if (decoder != null) {
                    decoder.reset();
                }
            }
        }
    }

    /**
     * Invoked when an incoming request exceeds the maximum content length.
     *
     * The default behavior is:
     * <ul>
     * <li>Oversized request: Send a {@link HttpResponseStatus#REQUEST_ENTITY_TOO_LARGE} and close the connection
     *     if keep-alive is not enabled.</li>
 *     <li>Oversized response: Close the connection and raise {@link TooLongFrameException}.</li>
     * </ul>
     * Sub-classes may override this method to change the default behavior.  The specified {@code msg} is released
     * once this method returns.
     *
     * @param ctx the {@link ChannelHandlerContext}
     * @param msg the accumulated HTTP message up to this point
     */
    @SuppressWarnings("UnusedParameters")
    protected void handleOversizedMessage(final ChannelHandlerContext ctx, HttpMessage msg) throws Exception {
        if (msg instanceof HttpRequest) {
            // send back a 413 and close the connection
            ChannelFuture future = ctx.writeAndFlush(TOO_LARGE).addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                    if (!future.isSuccess()) {
                        logger.debug("Failed to send a 413 Request Entity Too Large.", future.cause());
                        ctx.close();
                    }
                }
            });

            // If the client started to send data already, close because it's impossible to recover.
            // If 'Expect: 100-continue' is missing, close becuase it's impossible to recover.
            // If keep-alive is off, no need to leave the connection open.
            if (msg instanceof FullHttpMessage || !is100ContinueExpected(msg) || !isKeepAlive(msg)) {
                future.addListener(ChannelFutureListener.CLOSE);
            }
        } else if (msg instanceof HttpResponse) {
            ctx.close();
            throw new TooLongFrameException("Response entity too large: " + msg);
        } else {
            throw new IllegalStateException();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // release current message if it is not null as it may be a left-over
        if (currentMessage != null) {
            currentMessage.release();
            currentMessage = null;
        }

        super.channelInactive(ctx);
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        this.ctx = ctx;
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        super.handlerRemoved(ctx);

        // release current message if it is not null as it may be a left-over as there is not much more we can do in
        // this case
        if (currentMessage != null) {
            currentMessage.release();
            currentMessage = null;
        }
    }

    private static FullHttpMessage toFullMessage(HttpMessage msg) {
        if (msg instanceof FullHttpMessage) {
            return ((FullHttpMessage) msg).retain();
        }

        FullHttpMessage fullMsg;
        if (msg instanceof HttpRequest) {
            HttpRequest req = (HttpRequest) msg;
            fullMsg = new DefaultFullHttpRequest(
                    req.getProtocolVersion(), req.getMethod(), req.getUri(), Unpooled.EMPTY_BUFFER, false);
        } else if (msg instanceof HttpResponse) {
            HttpResponse res = (HttpResponse) msg;
            fullMsg = new DefaultFullHttpResponse(
                    res.getProtocolVersion(), res.getStatus(), Unpooled.EMPTY_BUFFER, false);
        } else {
            throw new IllegalStateException();
        }

        return fullMsg;
    }
}
