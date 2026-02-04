package com.shanyangcode.tianmu.websocket;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.NettyRuntime;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestControllerAdvice;


/**
 * NettyService类是一个配置类，用于配置和启动Netty服务器
 * 使用了Spring的@Configuration和@RestControllerAdvice注解，使其成为一个配置组件并具备全局异常处理能力
 */
@Configuration
@RestControllerAdvice
public class NettyService {



    // 从配置文件中获取netty.port的值，并赋给port变量
    @Value("${netty.port}")
    private int port;

    // 创建bossEventLoopGroup，用于处理服务器连接请求，线程数为1
    private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);

    // 创建workerEventLoopGroup，用于处理网络I/O操作，线程数为可用处理器的两倍
    private final EventLoopGroup workerGroup = new NioEventLoopGroup(NettyRuntime.availableProcessors() * 2);


    /**
     * 初始化方法，在Bean构造完成后自动调用
     * 使用@PostConstruct注解标记，确保在Bean初始化完成后执行
     * @throws InterruptedException 如果线程被中断，则抛出此异常
     */
    @PostConstruct
    public void start() throws InterruptedException{
        run();
    }


/**
 * 运行Netty服务器的方法
     * 创建并配置ServerBootstrap，绑定端口并启动服务器
 * @throws InterruptedException 如果线程被中断，则抛出此异常
 */
    public void run() throws InterruptedException{
    // 创建ServerBootstrap实例，用于配置和启动服务器
        ServerBootstrap serverBootstrap = new ServerBootstrap();
    // 配置服务器bootstrap
    // 设置事件循环组，bossGroup用于接受连接，workerGroup用于处理连接
        serverBootstrap.group(bossGroup, workerGroup)
            // 指定使用NIO模式的ServerSocketChannel
                .channel(NioServerSocketChannel.class)
            // 设置子通道的处理器，用于处理网络I/O
                .childHandler(new ChannelInitializer<SocketChannel>() {
                // 初始化SocketChannel的管道
                    @Override
                    protected void initChannel(SocketChannel socketChannel) {
                    // 获取ChannelPipeline，它包含了所有处理入站和出站事件的处理器
                        ChannelPipeline pipeline = socketChannel.pipeline();
                    // 添加HTTP编解码器，用于处理HTTP请求和响应
                        pipeline.addLast(new HttpServerCodec());
                    // 添加HTTP对象聚合器，将多个HTTP消息聚合为一个完整的FullHttpRequest或FullHttpResponse
                        pipeline.addLast(new HttpObjectAggregator(65536));
                    // 添加WebSocket协议处理器，处理WebSocket升级握手
                        pipeline.addLast(new WebSocketServerProtocolHandler("/nettyService"));
                    // 添加自定义的WebSocket处理器，处理WebSocket业务逻辑
                        pipeline.addLast(new WebSocketHandler());
                    }
                });
    // 绑定端口并同步等待服务器启动完成
        serverBootstrap.bind(port).sync();
    }


/**
 * 销毁方法，用于在容器销毁前执行资源清理工作
 * 当Spring容器关闭时，会自动调用此方法
 * 该方法使用@PreDestroy注解标记，确保在Bean销毁前执行
 */
    @PreDestroy
    public void destroy() {
    // 优雅关闭workerGroup，完成已接收但未处理的任务
        workerGroup.shutdownGracefully();
    // 优雅关闭bossGroup，完成已接收但未处理的任务
        bossGroup.shutdownGracefully();
    }

}
