package com.shanyangcode.tianmu.websocket;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;



/**
 * WebSocket处理器类，继承自SimpleChannelInboundHandler，专门处理TextWebSocketFrame类型的消息
 */
public class WebSocketHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {


    /**
     * 处理接收到的WebSocket文本消息
     * @param channelHandlerContext 通道处理器上下文
     * @param textWebSocketFrame 收到的文本WebSocket帧
     * @throws Exception 可能抛出的异常
     */
    @Override
    protected void channelRead0(ChannelHandlerContext channelHandlerContext, TextWebSocketFrame textWebSocketFrame) throws Exception {
        System.out.println("收到消息：" + textWebSocketFrame.text());
    }

    /**
     * 当处理器被添加到管道时调用
     * @param ctx 通道处理器上下文
     * @throws Exception 可能抛出的异常
     */
    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        super.handlerAdded(ctx);
        System.out.println("handlerAdded");
    }

    /**
     * 当处理器从管道中移除时调用
     * @param ctx 通道处理器上下文
     * @throws Exception 可能抛出的异常
     */
    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        super.handlerRemoved(ctx);
        System.out.println("handlerRemoved");
    }


    /**
     * 当处理过程中发生异常时调用
     * @param ctx 通道处理器上下文
     * @param cause 捕获到的异常对象
     * @throws Exception 可能抛出的异常
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        super.exceptionCaught(ctx, cause);
        System.out.println("exceptionCaught");
    }


    /**
     * 当通道变为活跃状态时调用
     * @param ctx 通道处理器上下文
     * @throws Exception 可能抛出的异常
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        System.out.println("channelActive");
    }


    /**
     * 当通道变为非活跃状态时调用
     * @param ctx 通道处理器上下文
     * @throws Exception 可能抛出的异常
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        System.out.println("channelInactive");
    }



}
