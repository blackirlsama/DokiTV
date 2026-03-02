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

/**
 * WebSocket核心业务处理器
 * 继承SimpleChannelInboundHandler<TextWebSocketFrame>：专注处理WebSocket文本消息帧
 * 核心功能：
 * 1. 处理WebSocket握手事件，提取视频ID并将客户端加入对应房间
 * 2. 接收客户端弹幕消息，校验登录状态后广播给同房间所有客户端
 * 3. 心跳检测超时处理，保持长连接
 * 4. 管理客户端连接生命周期，维护房间在线人数
 * 5. 异常处理与无效连接清理
 */
@Slf4j
@AllArgsConstructor // Lombok：生成全参构造器，Spring注入依赖
public class WebSocketHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    /**
     * RocketMQ生产者：用于将弹幕消息发送到MQ进行持久化/后续消费
     */
    private final RocketMQProducer producer;

    /**
     * Redis模板：用于校验用户登录状态（通过userId查询token）
     */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 日志对象：独立声明避免lombok的log冲突（也可直接用@Slf4j的log）
     */
    private static final Logger logger = LoggerFactory.getLogger(WebSocketHandler.class);

    /**
     * 房间映射表：存储「视频ID-客户端连接组」的关系
     * ConcurrentHashMap：保证多线程环境下的线程安全（多个客户端同时连接/发消息）
     * ChannelGroup：Netty提供的线程安全的Channel集合，用于批量管理同房间客户端连接
     */
    private static final ConcurrentMap<String, ChannelGroup> videoMap = new ConcurrentHashMap<>();

    /**
     * Channel属性键：用于给每个客户端连接绑定对应的视频ID（区分不同房间）
     * AttributeKey：Netty中用于给Channel附加自定义属性的工具类
     */
    private static final AttributeKey<String> VIDEOID = AttributeKey.valueOf("videoId");

    /**
     * 核心方法：处理客户端发送的WebSocket文本消息（弹幕消息）
     * @param ctx 通道上下文：包含当前连接的所有信息（Channel、Pipeline等）
     * @param msg WebSocket文本帧：客户端发送的弹幕消息（JSON格式）
     * @throws Exception 处理过程中的异常（如JSON解析失败、MQ发送失败等）
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame msg) throws Exception {
        // 1. 从当前客户端连接中获取绑定的视频ID（确定所属房间）
        String videoId = ctx.channel().attr(VIDEOID).get();
        if (videoId != null) {
            // 2. 校验客户端是否登录（从Redis查询userId对应的token）
            boolean login = checkOnline(msg.text());
            System.out.println("用户登录状态校验结果：" + login);
            if (login) {
                // 3. 登录成功：处理弹幕消息并广播给同房间所有客户端
                System.out.println("消息发送成功：" + msg.text());
                broadcastMessage(videoId, onlineMessage(msg.text()));
            } else {
                // 4. 未登录：给当前客户端推送「请先登录」提示
                needLoginMessage(videoId, ctx.channel());
            }
        }
    }

    /**
     * 广播消息给指定房间的所有客户端
     * @param videoId 视频ID（房间ID）
     * @param message 要广播的消息（JSON格式的弹幕/在线人数/提示信息）
     */
    private void broadcastMessage(String videoId, String message) {
        // 空消息校验：避免无效广播
        if (message == null || message.isEmpty()) {
            return;
        }
        // 获取当前房间的客户端连接组
        ChannelGroup group = videoMap.get(videoId);
        if (group != null && !group.isEmpty()) {
            // 批量发送消息给组内所有客户端（TextWebSocketFrame是WebSocket文本消息载体）
            group.writeAndFlush(new TextWebSocketFrame(message)).addListener(future -> {
                // 消息发送失败的回调处理
                if (!future.isSuccess()) {
                    logger.error("消息广播失败到房间：{}，原因：{}", videoId, future.cause().getMessage());
                    // 清理无效连接（客户端断开但未从Group中移除的Channel）
                    cleanupInvalidChannels(group);
                }
            });
        }
    }

    /**
     * 处理弹幕消息：生成唯一ID、发送MQ、构造广播响应
     * @param text 客户端原始弹幕消息（JSON字符串）
     * @return 构造好的、用于广播的弹幕响应消息（JSON字符串）
     */
    public String onlineMessage(String text) {
        // 1. 构建通用响应对象（统一消息格式）
        BulletScreenResponse bulletScreenResponse = new BulletScreenResponse();
        // 设置消息类型为「弹幕消息」（前端根据该类型解析展示）
        bulletScreenResponse.setType(WebSocketConstant.ONLINE_BULLET);

        // 2. 解析客户端发送的弹幕请求参数
        SendBulletRequest sendBulletRequest = JSONUtil.toBean(text, SendBulletRequest.class);

        // 3. 生成唯一弹幕ID（雪花算法：保证分布式环境下ID不重复）
        Snowflake snowflake = IdUtil.getSnowflake(SnowflakeConstant.WORKER_ID, SnowflakeConstant.DATA_CENTER_ID);
        sendBulletRequest.setBulletId(snowflake.nextId());

        // 4. 将弹幕消息发送到RocketMQ（用于持久化/后续消费，如存储历史弹幕）
        String messageMQ = JSONUtil.parse(sendBulletRequest).toString();
        producer.sendMessage("tianmu-topic", messageMQ);
        System.out.println("弹幕消息发送到MQ：" + messageMQ);

        // 5. 构造前端展示用的弹幕数据
        OnlineBulletResponse onlineBulletResponse = new OnlineBulletResponse();
        onlineBulletResponse.setPlaybackTime(sendBulletRequest.getPlaybackTime()); // 弹幕播放时间点
        onlineBulletResponse.setText(sendBulletRequest.getContent()); // 弹幕内容
        onlineBulletResponse.setUserId(sendBulletRequest.getUserId().toString()); // 发送用户ID
        onlineBulletResponse.setBulletId(sendBulletRequest.getBulletId().toString()); // 弹幕唯一ID

        // 6. 封装响应数据并转为JSON字符串（供前端解析）
        bulletScreenResponse.setData(onlineBulletResponse);
        return JSONUtil.parse(bulletScreenResponse).toString();
    }

    /**
     * 清理无效连接：移除ChannelGroup中已断开/关闭的客户端连接
     * @param group 待清理的房间连接组
     */
    private void cleanupInvalidChannels(ChannelGroup group) {
        // 筛选出无效连接：非活跃（isActive）或已关闭（isOpen）的Channel
        List<Channel> invalidChannels = group.stream()
                .filter(ch -> !ch.isActive() || !ch.isOpen())
                .collect(Collectors.toList());
        // 从组中移除所有无效连接（避免广播时发送失败）
        invalidChannels.forEach(group::remove);
    }

    /**
     * 事件触发处理：处理Netty的非消息类事件（心跳超时、WebSocket握手完成等）
     * @param ctx 通道上下文
     * @param evt 触发的事件对象
     * @throws Exception 处理异常
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        // ========== 处理心跳超时事件 ==========
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent event = (IdleStateEvent) evt;
            // 检测到「读空闲」（客户端60秒未发送任何消息）
            if (event.state() == IdleState.READER_IDLE) {
                log.info("客户端{} 60秒未发送消息，发送心跳包保持连接", ctx.channel());
                // 发送ping消息给客户端（WebSocket心跳，前端需回复pong）
                ctx.channel().writeAndFlush(new TextWebSocketFrame(JSON.toJSONString("ping"))).addListener(future -> {
                    if (!future.isSuccess()) {
                        log.error("心跳包发送失败: {}", future.cause());
                    }
                });
            }
        } else {
            // 非IdleStateEvent事件，交给父类处理（保证Netty默认逻辑执行）
            super.userEventTriggered(ctx, evt);
        }

        // ========== 处理WebSocket握手完成事件 ==========
        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            // 获取握手完成事件对象（包含客户端连接的URL等信息）
            WebSocketServerProtocolHandler.HandshakeComplete handshake = (WebSocketServerProtocolHandler.HandshakeComplete) evt;
            String uri = handshake.requestUri(); // 客户端连接的URL：如/ws/bulletScreen/123
            String videoId = extractRoomId(uri); // 从URL中提取视频ID（房间ID）
            if (videoId != null) {
                // 将视频ID绑定到当前客户端连接的属性中（后续发消息时识别所属房间）
                ctx.channel().attr(VIDEOID).set(videoId);
                // 将当前客户端连接加入对应房间的ChannelGroup
                joinRoom(videoId, ctx.channel());
                // 广播当前房间的在线人数（更新所有客户端的在线人数展示）
                broadcastOnlineCount(videoId);
            }
        }
    }

    /**
     * 从WebSocket连接URL中提取视频ID（房间ID）
     * @param uri 客户端连接的URL：如/ws/bulletScreen/123
     * @return 提取的视频ID：如123
     */
    private String extractRoomId(String uri) {
        // 按"/"分割URL路径，取最后一段作为视频ID
        String[] pathSegments = uri.split("/");
        return pathSegments[pathSegments.length - 1];
    }

    /**
     * 将客户端连接加入指定房间
     * @param videoId 视频ID（房间ID）
     * @param channel 客户端连接通道
     */
    private void joinRoom(String videoId, Channel channel) {
        // computeIfAbsent：如果key不存在则创建新的ChannelGroup，存在则直接获取
        // DefaultChannelGroup：Netty提供的线程安全的Channel集合，GlobalEventExecutor保证全局线程安全
        videoMap.computeIfAbsent(videoId, k -> new DefaultChannelGroup(GlobalEventExecutor.INSTANCE)).add(channel);
    }

    /**
     * 广播指定房间的在线人数给所有客户端
     * @param videoId 视频ID（房间ID）
     */
    private void broadcastOnlineCount(String videoId) {
        // 获取当前房间的连接组
        ChannelGroup group = videoMap.get(videoId);
        if (group != null) {
            // 构建在线人数响应对象
            BulletScreenResponse bulletScreenResponse = new BulletScreenResponse();
            bulletScreenResponse.setType(WebSocketConstant.ONLINE_NUMBER); // 消息类型：在线人数
            bulletScreenResponse.setData(group.size()); // 在线人数=连接组的大小
            // 转为JSON字符串并广播
            String message = JSONUtil.parse(bulletScreenResponse).toString();
            group.writeAndFlush(new TextWebSocketFrame(message));
        }
    }

    /**
     * 连接添加时触发（客户端刚建立连接）
     * @param ctx 通道上下文
     * @throws Exception 处理异常
     */
    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        super.handlerAdded(ctx);
        System.out.println("客户端连接添加：" + ctx.channel().id());
    }

    /**
     * 连接移除时触发（客户端断开连接）
     * @param ctx 通道上下文
     * @throws Exception 处理异常
     */
    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        super.handlerRemoved(ctx);
        // 获取当前连接绑定的视频ID
        String videoId = ctx.channel().attr(VIDEOID).get();
        if (videoId != null) {
            // 获取对应房间的连接组
            ChannelGroup group = videoMap.get(videoId);
            if (group != null) {
                // 从组中移除当前断开的连接
                group.remove(ctx.channel());
                // 重新广播在线人数（更新房间人数）
                broadcastOnlineCount(videoId);
            }
        }
        System.out.println("客户端连接移除：" + ctx.channel().id());
    }

    /**
     * 异常捕获：处理客户端连接过程中的异常（如网络中断、消息解析失败等）
     * @param ctx 通道上下文
     * @param cause 异常原因
     * @throws Exception 处理异常
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        super.exceptionCaught(ctx, cause);
        System.out.println("客户端连接异常：" + ctx.channel().id() + "，原因：" + cause.getMessage());
        // 异常时关闭连接（避免无效长连接占用资源）
        ctx.close();
    }

    /**
     * 通道激活时触发（客户端连接建立成功，可读写）
     * @param ctx 通道上下文
     * @throws Exception 处理异常
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        System.out.println("客户端连接激活：" + ctx.channel().id());
    }

    /**
     * 通道非激活时触发（客户端连接断开）
     * @param ctx 通道上下文
     * @throws Exception 处理异常
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        System.out.println("客户端连接断开：" + ctx.channel().id());
    }

    /**
     * 校验用户登录状态：从Redis查询userId对应的token是否存在
     * @param text 客户端发送的弹幕消息（包含userId）
     * @return true=已登录，false=未登录
     */
    public boolean checkOnline(String text) {
        // 解析弹幕请求参数，获取userId
        SendBulletRequest request = JSONUtil.toBean(text, SendBulletRequest.class);
        String userId = request.getUserId().toString();
        // 从Redis中查询userId对应的token（登录时存储）
        String token = stringRedisTemplate.opsForValue().get(userId);
        // token非空则表示用户已登录
        return token != null;
    }

    /**
     * 给未登录的客户端发送「请先登录」提示消息
     * @param videoId 视频ID（房间ID）
     * @param channel 未登录的客户端连接通道
     */
    private void needLoginMessage(String videoId, Channel channel) {
        // 构建登录提示响应对象
        BulletScreenResponse bulletScreenResponse = new BulletScreenResponse();
        bulletScreenResponse.setType(WebSocketConstant.LOGIN_MESSAGE); // 消息类型：登录提示
        bulletScreenResponse.setData("请先登录"); // 提示内容
        // 转为JSON字符串
        String message = JSON.toJSONString(bulletScreenResponse);
        // 发送给当前未登录的客户端
        channel.writeAndFlush(new TextWebSocketFrame(message)).addListener(future -> {
            if (!future.isSuccess()) {
                logger.error("登录提示消息发送失败到房间：{}，原因：{}", videoId, future.cause().getMessage());
                // 清理当前房间的无效连接
                cleanupInvalidChannels(videoMap.get(videoId));
            }
        });
    }

}