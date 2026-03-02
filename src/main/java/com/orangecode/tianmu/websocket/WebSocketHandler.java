package com.orangecode.tianmu.websocket;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import com.alibaba.fastjson.JSON;
import com.orangecode.tianmu.constants.SnowflakeConstant;
import com.orangecode.tianmu.constants.WebSocketConstant;
import com.orangecode.tianmu.model.dto.bullet.SendBulletRequest;
import com.orangecode.tianmu.model.vo.bullet.BulletScreenResponse;
import com.orangecode.tianmu.model.vo.bullet.OnlineBulletResponse;
import com.orangecode.tianmu.producer.RocketMQProducer;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSONUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.GlobalEventExecutor;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;


@Slf4j
@AllArgsConstructor
public class WebSocketHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {


    private final RocketMQProducer producer;

    private final StringRedisTemplate stringRedisTemplate;

    private static final Logger logger = LoggerFactory.getLogger(WebSocketHandler.class);


    private static final ConcurrentMap<String, ChannelGroup> videoMap = new ConcurrentHashMap<>();

    private static final AttributeKey<String> VIDEOID = AttributeKey.valueOf("videoId");



    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame msg) throws Exception {
        String videoId = ctx.channel().attr(VIDEOID).get();
        if (videoId != null) {
            boolean login = checkOnline(msg.text());
            System.out.println(login);
            if (login) {
                System.out.println("消息发送成功：" + msg.text());
                broadcastMessage(videoId, onlineMessage(msg.text()));
            } else {
                needLoginMessage(videoId, ctx.channel());
            }
        }

    }

    private void broadcastMessage(String videoId, String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        ChannelGroup group = videoMap.get(videoId);
        if (group != null && !group.isEmpty()) {
            group.writeAndFlush(new TextWebSocketFrame(message)).addListener(future -> {
                if (!future.isSuccess()) {
                    logger.error("消息失败到房间：{}，原因：{}", videoId, future.cause().getMessage());
                    cleanupInvalidChannels(group);
                }
            });
        }
    }

    public String onlineMessage(String text) {
        BulletScreenResponse bulletScreenResponse = new BulletScreenResponse();
        bulletScreenResponse.setType(WebSocketConstant.ONLINE_BULLET);
        SendBulletRequest sendBulletRequest = JSONUtil.toBean(text, SendBulletRequest.class);
        Snowflake snowflake = IdUtil.getSnowflake(SnowflakeConstant.WORKER_ID, SnowflakeConstant.DATA_CENTER_ID);
        sendBulletRequest.setBulletId(snowflake.nextId());
        String messageMQ = JSONUtil.parse(sendBulletRequest).toString();

        // 生产
        producer.sendMessage("tianmu-topic", messageMQ);


        System.out.println("发送到consumer: " + messageMQ);
        OnlineBulletResponse onlineBulletResponse = new OnlineBulletResponse();
        onlineBulletResponse.setPlaybackTime(sendBulletRequest.getPlaybackTime());
        onlineBulletResponse.setText(sendBulletRequest.getContent());
        onlineBulletResponse.setUserId(sendBulletRequest.getUserId().toString());
        onlineBulletResponse.setBulletId(sendBulletRequest.getBulletId().toString());
        bulletScreenResponse.setData(onlineBulletResponse);
        return JSONUtil.parse(bulletScreenResponse).toString();
    }


    private void cleanupInvalidChannels(ChannelGroup group) {
        List<Channel> invalidChannels = group.stream().filter(ch -> !ch.isActive() || !ch.isOpen()).collect(Collectors.toList());
        invalidChannels.forEach(group::remove);
    }


    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {

        if (evt instanceof IdleStateEvent) {
            IdleStateEvent event = (IdleStateEvent) evt;
            if (event.state() == IdleState.READER_IDLE) {
                log.info("30 秒没有读取到数据，发送心跳保持连接: {}", ctx.channel());
                ctx.channel().writeAndFlush(new TextWebSocketFrame(JSON.toJSONString("ping"))).addListener(future -> {
                    if (!future.isSuccess()) {
                        log.error("发送心跳失败: {}", future.cause());
                    }
                });
            }
        } else {
            super.userEventTriggered(ctx, evt);
        }

        // 处理 WebSocket 握手完成事件
        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            WebSocketServerProtocolHandler.HandshakeComplete handshake = (WebSocketServerProtocolHandler.HandshakeComplete) evt;
            String uri = handshake.requestUri();
            String videoId = extractRoomId(uri);
            if (videoId != null) {
                ctx.channel().attr(VIDEOID).set(videoId);
                joinRoom(videoId, ctx.channel());
                broadcastOnlineCount(videoId);
            }
        }
    }


    private String extractRoomId(String uri) {
        String[] pathSegments = uri.split("/");
        return pathSegments[pathSegments.length - 1];
    }

    private void joinRoom(String videoId, Channel channel) {
        videoMap.computeIfAbsent(videoId, k -> new DefaultChannelGroup(GlobalEventExecutor.INSTANCE)).add(channel);
    }

    private void broadcastOnlineCount(String videoId) {
        ChannelGroup group = videoMap.get(videoId);
        if (group != null) {
            BulletScreenResponse bulletScreenResponse = new BulletScreenResponse();
            bulletScreenResponse.setType(WebSocketConstant.ONLINE_NUMBER);
            bulletScreenResponse.setData(group.size());
            String message = JSONUtil.parse(bulletScreenResponse).toString();
            group.writeAndFlush(new TextWebSocketFrame(message));
        }
    }


    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        super.handlerAdded(ctx);
        System.out.println("handlerAdded");
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        super.handlerRemoved(ctx);
        String videoId = ctx.channel().attr(VIDEOID).get();
        if (videoId != null) {
            ChannelGroup group = videoMap.get(videoId);
            if (group != null) {
                group.remove(ctx.channel());
                broadcastOnlineCount(videoId);
            }
        }
        System.out.println("handler removed");
    }



    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        super.exceptionCaught(ctx, cause);
        System.out.println("exceptionCaught");
    }


    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        System.out.println("channelActive");
    }


    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        System.out.println("channelInactive");
    }

    public boolean checkOnline(String text) {
        SendBulletRequest request = JSONUtil.toBean(text, SendBulletRequest.class);
        String userId = request.getUserId().toString();
        String token = stringRedisTemplate.opsForValue().get(userId);
        return token != null;
    }

    private void needLoginMessage(String videoId, Channel channel) {
        BulletScreenResponse bulletScreenResponse = new BulletScreenResponse();
        bulletScreenResponse.setType(WebSocketConstant.LOGIN_MESSAGE);
        bulletScreenResponse.setData("请先登录");
        String message = JSON.toJSONString(bulletScreenResponse);
        channel.writeAndFlush(new TextWebSocketFrame(message)).addListener(future -> {
            if (!future.isSuccess()) {
                logger.error("消息失败到房间：{}，原因：{}", videoId, future.cause().getMessage());
                cleanupInvalidChannels(videoMap.get(videoId));
            }
        });
    }

}
