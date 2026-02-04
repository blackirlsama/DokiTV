package com.shanyangcode.tianmu.websocket;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import com.alibaba.fastjson.JSON;
import com.shanyangcode.tianmu.constants.SnowflakeConstant;
import com.shanyangcode.tianmu.constants.WebSocketConstant;
import com.shanyangcode.tianmu.model.dto.bullet.SendBulletRequest;
import com.shanyangcode.tianmu.model.vo.bullet.BulletScreenResponse;
import com.shanyangcode.tianmu.model.vo.bullet.OnlineBulletResponse;
import com.shanyangcode.tianmu.producer.RocketMQProducer;

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


    /**
     * RocketMQ生产者，用于发送消息到消息队列
     */
    private final RocketMQProducer producer;

    /**
     * Redis模板，用于操作Redis数据
     */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 日志记录器，用于记录日志信息
     */
    private static final Logger logger = LoggerFactory.getLogger(WebSocketHandler.class);


    /**
     * 用于存储视频ID和对应频道组的映射关系
     */
    private static final ConcurrentMap<String, ChannelGroup> videoMap = new ConcurrentHashMap<>();

    /**
     * 用于存储频道中的视频ID属性
     */
    private static final AttributeKey<String> VIDEOID = AttributeKey.valueOf("videoId");



    /**
     * 处理接收到的WebSocket消息
     * @param ctx 通道处理器上下文
     * @param msg 接收到的文本WebSocket帧
     * @throws Exception 可能抛出的异常
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame msg) throws Exception {
        // 获取视频ID
        String videoId = ctx.channel().attr(VIDEOID).get();
        if (videoId != null) {
            // 检查用户是否在线
            boolean login = checkOnline(msg.text());
            System.out.println(login);
            if (login) {
                System.out.println("消息发送成功：" + msg.text());
                // 广播消息到对应视频的房间
                broadcastMessage(videoId, onlineMessage(msg.text()));
            } else {
                // 如果用户未登录，发送需要登录的消息
                needLoginMessage(videoId, ctx.channel());
            }
        }

    }

    /**
     * 广播消息到指定视频的房间
     * @param videoId 视频ID
     * @param message 要广播的消息
     */
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

    /**
     * 处理在线消息
     * @param text 接收到的文本消息
     * @return 处理后的在线消息字符串
     */
    public String onlineMessage(String text) {
        // 创建弹幕响应对象
        BulletScreenResponse bulletScreenResponse = new BulletScreenResponse();
        bulletScreenResponse.setType(WebSocketConstant.ONLINE_BULLET);
        // 将文本转换为发送弹幕请求对象
        SendBulletRequest sendBulletRequest = JSONUtil.toBean(text, SendBulletRequest.class);
        // 生成雪花ID并设置到弹幕请求对象中
        Snowflake snowflake = IdUtil.getSnowflake(SnowflakeConstant.WORKER_ID, SnowflakeConstant.DATA_CENTER_ID);
        sendBulletRequest.setBulletId(snowflake.nextId());
    // 将请求对象转换为JSON字符串
        String messageMQ = JSONUtil.parse(sendBulletRequest).toString();

        // 生产
        producer.sendMessage("zzz-topic", messageMQ);


        System.out.println("发送到consumer: " + messageMQ);
        OnlineBulletResponse onlineBulletResponse = new OnlineBulletResponse();
        onlineBulletResponse.setPlaybackTime(sendBulletRequest.getPlaybackTime());
        onlineBulletResponse.setText(sendBulletRequest.getContent());
        onlineBulletResponse.setUserId(sendBulletRequest.getUserId().toString());
        onlineBulletResponse.setBulletId(sendBulletRequest.getBulletId().toString());
        bulletScreenResponse.setData(onlineBulletResponse);
        return JSONUtil.parse(bulletScreenResponse).toString();
    }


/**
 * 清理无效的通道
 * @param group 需要清理的通道组
 */
    private void cleanupInvalidChannels(ChannelGroup group) {
        // 使用流式处理筛选出无效的通道（不活跃或已关闭）
        List<Channel> invalidChannels = group.stream().filter(ch -> !ch.isActive() || !ch.isOpen()).collect(Collectors.toList());
        // 从通道组中移除所有无效通道
        invalidChannels.forEach(group::remove);
    }


    @Override
    /**
     * 处理用户触发的事件
     * @param ctx ChannelHandlerContext 通道处理器上下文
     * @param evt Object 触发的事件对象
     * @throws Exception 可能抛出的异常
     */
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {

        // 判断事件是否为空闲状态事件
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent event = (IdleStateEvent) evt;
            // 判断是否为读空闲状态
            if (event.state() == IdleState.READER_IDLE) {
                // 记录日志：30秒没有读取到数据，发送心跳保持连接
                log.info("30 秒没有读取到数据，发送心跳保持连接: {}", ctx.channel());
                // 发送心跳消息，并添加监听器处理发送失败的情况
                ctx.channel().writeAndFlush(new TextWebSocketFrame(JSON.toJSONString("ping"))).addListener(future -> {
                    if (!future.isSuccess()) {
                        // 记录日志：发送心跳失败
                        log.error("发送心跳失败: {}", future.cause());
                    }
                });
            }
        } else {
            // 如果不是空闲状态事件，则调用父类的方法处理
            super.userEventTriggered(ctx, evt);
        }

        // 处理 WebSocket 握手完成事件
        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            WebSocketServerProtocolHandler.HandshakeComplete handshake = (WebSocketServerProtocolHandler.HandshakeComplete) evt;
            String uri = handshake.requestUri();
            String videoId = extractRoomId(uri);
            if (videoId != null) {
                // 设置通道的VIDEOID属性
                ctx.channel().attr(VIDEOID).set(videoId);
                // 加入房间
                joinRoom(videoId, ctx.channel());
                // 广播在线人数
                broadcastOnlineCount(videoId);
            }
        }
    }


/**
 * 从URI中提取房间ID
 * @param uri 包含房间ID的URI字符串
 * @return 返回URI中的最后一个路径段作为房间ID
 */
    private String extractRoomId(String uri) {
    // 将URI按"/"分割成多个路径段
        String[] pathSegments = uri.split("/");
    // 返回最后一个路径段作为房间ID
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
