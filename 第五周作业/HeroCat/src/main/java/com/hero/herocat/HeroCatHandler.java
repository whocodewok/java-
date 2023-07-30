package com.hero.herocat;

import com.hero.servlet.HeroRequest;
import com.hero.servlet.HeroResponse;
import com.hero.servlet.HeroServlet;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * HeroCat服务端处理器
 * <p>
 * 1）从用户请求URI中解析出要访问的Servlet名称
 * 2）从nameToServletMap中查找是否存在该名称的key。若存在，则直接使用该实例，否则执
 * 行第3）步
 * 3）从nameToClassNameMap中查找是否存在该名称的key，若存在，则获取到其对应的全限定
 * 性类名，
 * 使用反射机制创建相应的serlet实例，并写入到nameToServletMap中，若不存在，则直
 * 接访问默认Servlet
 */
public class HeroCatHandler extends ChannelInboundHandlerAdapter {

    private Map<String, HeroServlet> nameToServletMap;//线程安全 servlet-->对象
    private Map<String, String> nameToClassNameMap;//线程不安全 servlet-->全限定名称

    public HeroCatHandler(Map<String, HeroServlet> nameToServletMap,
                          Map<String, String> nameToClassNameMap) {
        this.nameToServletMap = nameToServletMap;
        this.nameToClassNameMap = nameToClassNameMap;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws
            Exception {
        if (msg instanceof HttpRequest) {
            HttpRequest request = (HttpRequest) msg;
            String uri = request.uri();

            if (!uri.contains("servlet?")) {
                serveStaticResource(ctx, uri);
            } else {
                serveDynamicContent(ctx, request);
            }

            ctx.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
            throws Exception {
        cause.printStackTrace();
        ctx.close();
    }

    private HeroServlet registerServlet(String uri) throws Exception {

        HeroServlet heroServlet = new DefaultHeroServlet();

        // 从请求中解析出要访问的Servlet名称
        //aaa/bbb/twoservlet?name=aa
        String servletName = uri.substring(uri.lastIndexOf("/") + 1,
                uri.indexOf("?"));
        if (nameToServletMap.containsKey(servletName)) {//每次访问都走这里(除了第一次)
            heroServlet = nameToServletMap.get(servletName);//Servlet是懒加载
        } else if (nameToClassNameMap.containsKey(servletName)) {//初始化Servlet对应的对象
            // double-check，双重检测锁
            if (nameToServletMap.get(servletName) == null) {
                synchronized (this) {
                    if (nameToServletMap.get(servletName) == null) {
                        // 获取当前Servlet的全限定性类名
                        String className =
                                nameToClassNameMap.get(servletName);
                        // 使用反射机制创建Servlet实例
                        heroServlet = (HeroServlet)
                                Class.forName(className).newInstance();
                        // 将Servlet实例写入到nameToServletMap
                        nameToServletMap.put(servletName, heroServlet);
                    }
                }
            }
        }
        return heroServlet;
    }

    private String getHtmlFromResource(String fileName) throws IOException {
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream(fileName);
        if (inputStream != null) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                StringBuilder htmlBuilder = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    htmlBuilder.append(line);
                    htmlBuilder.append(System.lineSeparator());
                }
                return htmlBuilder.toString();
            }
        }
        return "";
    }


    private void sendHttpResponse(ChannelHandlerContext ctx, HttpResponseStatus status, String content) {
        ByteBuf buffer = Unpooled.copiedBuffer(content, StandardCharsets.UTF_8);
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, buffer);

        HttpHeaders headers = response.headers();
        headers.set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
        headers.set(HttpHeaderNames.CONTENT_LENGTH, buffer.readableBytes());

        ctx.writeAndFlush(response);
    }

    private void serveStaticResource(ChannelHandlerContext ctx, String uri) throws IOException {
        // Remove the "/" prefix to get the resource path
        String resourcePath = uri.substring("/".length());

        if (uri.endsWith(".jpg")) {
            byte[] imageData = getBytesFromResource(resourcePath);
            if (imageData != null) {
                FullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                        Unpooled.wrappedBuffer(imageData));
                response.headers().set("Content-Type", "image/jpeg");

                ctx.writeAndFlush(response);
            }
        } else {
            String htmlContent = getHtmlFromResource(resourcePath);
            if (!htmlContent.isEmpty()) {
                ByteBuf content = Unpooled.copiedBuffer(htmlContent, StandardCharsets.UTF_8);

                FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());

                ctx.writeAndFlush(response);
            } else {
                sendHttpResponse(ctx, HttpResponseStatus.NOT_FOUND, "404 Not Found");
            }
        }

    }

    private void serveDynamicContent(ChannelHandlerContext ctx, HttpRequest request) throws Exception {
        HeroServlet heroServlet = registerServlet(request.getUri());

        HeroRequest req = new HttpHeroRequest(request);
        HeroResponse res = new HttpHeroResponse(request, ctx);
        // 根据不同的请求类型，调用heroServlet实例的不同方法
        if (request.method().name().equalsIgnoreCase("GET")) {
            heroServlet.doGet(req, res);
        } else if (request.method().name().equalsIgnoreCase("POST")) {
            heroServlet.doPost(req, res);
        }
    }

    private byte[] getBytesFromResource(String fileName) throws IOException {
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream(fileName);
        if (inputStream != null) {
            try (BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStream);
                 ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {

                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = bufferedInputStream.read(buffer)) != -1) {
                    byteArrayOutputStream.write(buffer, 0, bytesRead);
                }
                return byteArrayOutputStream.toByteArray();
            }
        }
        return new byte[0];
    }
}