package com.orangecode.tianmu.websocket;

import java.util.concurrent.TimeUnit;

import com.orangecode.tianmu.producer.RocketMQProducer;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.handler.traffic.ChannelTrafficShapingHandler;
import io.netty.util.NettyRuntime;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class NettyService {



    @Value("${netty.port}")
    private int port;

    private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);

    private final EventLoopGroup workerGroup = new NioEventLoopGroup(NettyRuntime.availableProcessors() * 2);

    private final RocketMQProducer rocketMQProducer;

    private final StringRedisTemplate stringRedisTemplate;

    private Channel serverChannel;


    @PostConstruct
    public void start() {

        new Thread(() -> {
            try {
                run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Netty server start interrupted", e);
            }
        }, "netty-starter").start();
    }


    public void run() throws InterruptedException{
        ServerBootstrap serverBootstrap = new ServerBootstrap();
        serverBootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 1024)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.SO_RCVBUF, 1024 * 1024)
                .childOption(ChannelOption.SO_SNDBUF, 1024 * 1024)
                .handler(new LoggingHandler(LogLevel.DEBUG))
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel socketChannel) {
                        ChannelPipeline pipeline = socketChannel.pipeline();
                        pipeline.addLast(new IdleStateHandler(60, 0, 0, TimeUnit.SECONDS));
                        pipeline.addLast(new HttpServerCodec());
                        pipeline.addLast(new HttpObjectAggregator(65536));
                        pipeline.addLast(new WebSocketServerProtocolHandler("/ws/bulletScreen", null, true, 65536, false, true));
                        pipeline.addLast(new ChannelTrafficShapingHandler(1024 * 1024, 1024 * 1024));
                        pipeline.addLast(new WebSocketHandler(rocketMQProducer, stringRedisTemplate));
                    }
                });
        serverChannel = serverBootstrap.bind(port).addListener(future -> {
            if (future.isSuccess()) {
                log.info("Netty server started on port {}", port);
            } else {
                log.error("Failed to start netty server", future.cause());
            }
        }).sync().channel();
    }


    @PreDestroy
    public void destroy() {

        if (serverChannel != null) {
            serverChannel.close();
        }

        workerGroup.shutdownGracefully();
        bossGroup.shutdownGracefully();
    }

}
