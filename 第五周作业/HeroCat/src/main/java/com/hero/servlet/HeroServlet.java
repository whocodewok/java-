package com.hero.servlet;

/**
 * 定义Servlet规范
 */
public abstract class HeroServlet {
    //处理Http的get请求
    public abstract void doGet(HeroRequest request, HeroResponse response)
            throws Exception;

    //处理Http的post请求
    public abstract void doPost(HeroRequest request, HeroResponse response)
            throws Exception;
}