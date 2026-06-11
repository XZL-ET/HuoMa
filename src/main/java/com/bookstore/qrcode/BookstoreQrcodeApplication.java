package com.bookstore.qrcode;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 书店活码系统 &mdash; 主启动类。
 * <p>
 * 本系统提供企业微信活码（群活码/单人活码）的全生命周期管理能力，
 * 包括活码创建、客户扫码自动分配服务老师、日上限控制、员工轮换、
 * 客户自动打标、欢迎语发送、表单信息收集、回调事件处理等核心功能。
 * </p>
 *
 * <h3>核心能力</h3>
 * <ul>
 *   <li><b>活码管理</b> &mdash; 支持学校/区域维度创建和管理企业微信活码</li>
 *   <li><b>智能分配</b> &mdash; 客户扫码后按轮换策略分配给服务老师或接待员</li>
 *   <li><b>日上限控制</b> &mdash; 每位员工可独立设置每日添加客户上限，超限自动切换后备</li>
 *   <li><b>自动打标</b> &mdash; 客户扫码后根据活码配置自动打上自定义标签</li>
 *   <li><b>回调处理</b> &mdash; 通过 Redis Stream 异步消费企业微信回调事件</li>
 *   <li><b>批量导入</b> &mdash; 支持通过 Excel/CSV 批量导入活码数据</li>
 *   <li><b>欢迎语与表单</b> &mdash; 首次添加时自动发送欢迎语和收集表单</li>
 * </ul>
 *
 * <h3>技术架构</h3>
 * <ul>
 *   <li>Spring Boot 2.x + JDK 8/11 &mdash; 基础框架</li>
 *   <li>Redis Stream &mdash; 回调事件消息队列，保证可靠消费</li>
 *   <li>Redis Sorted Set &mdash; 滑动窗口速率限制</li>
 *   <li>分布式锁（Redis） &mdash; 员工轮换原子性保障</li>
 *   <li>@EnableAsync &mdash; 异步线程池处理回调与批量任务</li>
 *   <li>@EnableScheduling &mdash; 定时任务（如 access_token 自动续期）</li>
 * </ul>
 *
 * @author Bookstore Dev Team
 * @since 1.0.0
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class BookstoreQrcodeApplication {

    /**
     * 应用入口。
     * <p>
     * 初始化 Spring 应用上下文，自动装配所有 Bean，
     * 启动内嵌 Web 容器（Tomcat），开始接收 HTTP 请求。
     * </p>
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(BookstoreQrcodeApplication.class, args);
    }
}
