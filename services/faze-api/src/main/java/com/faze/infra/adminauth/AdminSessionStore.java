/*
 * 职责：读 RuoYi 写入的登录态（Redis `login_tokens:<uuid>`），把非标准 JSON 抽成 AdminSession。
 * 调用方：security/AdminAuthenticationFilter（每个 `/api/v1/admin/**` 请求一次）。
 * 数据来源：Redis **逻辑库 0**（RuoYi 的库），由独立的 Lettuce 连接工厂连接。
 * 注意：
 *   1. **必须用独立连接工厂、且不注册成 Spring Bean**。Boot 的 Redis 自动配置是
 *      `@ConditionalOnMissingBean(RedisConnectionFactory.class)`：一旦本类把第二个工厂暴露成 Bean，
 *      faze-api 原有的 1 号库（验证码 / 令牌 / 锁定计数 / 支付密码计数）会全部失去自动配置。
 *      所以工厂在本类内部创建、内部销毁，不进入容器。
 *   2. **值不是标准 JSON**（2026-11-05 实测）。RuoYi 用 fastjson2 的
 *      `WriteClassName` 序列化，落盘形如
 *      `{"@type":"...LoginUser","deptId":103L,...,"permissions":Set["*:*:*"],...,"userId":1L,"username":"admin"}`。
 *      其中 `103L` 的 `L` 后缀、`Set[...]` 的集合写法都**不是合法 JSON**，
 *      Jackson 直接解析会抛 `JsonParseException`。接口规范 §1.3.1 第 5 条写的
 *      「用任意 JSON 库都能读」**与实测不符**，按实测处理：只做定点抽取，不整体解析。
 *   3. 抽取锚点是「`"userId":<数字>L?,"username":"<登录名>"`」这一对相邻字段。
 *      它只在**顶层**出现——`user` 子对象里那个是 `"userId":...,"userName":...`（大写 N），
 *      不会误命中。用相邻对而不是单个 `"userId"`，是为了避免命中子对象里同名字段。
 *   4. **抽不出来就当作未登录**（fail closed），并打 error 日志带样本前缀。
 *      绝不能「抽不到就给个默认管理员 id」——那等于鉴权失效。
 *   5. key 的存活即有效期：RuoYi 的 token 30 分钟滚动续期，key 没了就是没登录，
 *      不需要再看 JWT 里的时间（RuoYi 的 JWT 根本不含 exp 声明）。
 */
package com.faze.infra.adminauth;

import com.faze.config.properties.FazeAdminAuthProperties;
import jakarta.annotation.Nullable;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * RuoYi 登录态只读存储。
 */
@Component
@Slf4j
public class AdminSessionStore implements InitializingBean, DisposableBean {

    /**
     * 顶层 `"userId":<数字>L?,"username":"<名字>"` 对。
     *
     * <p>见类注释第 2、3 条：这是非标准 JSON，只能定点抽取。</p>
     */
    private static final Pattern LOGIN_USER_PATTERN = Pattern.compile(
            "\"userId\"\\s*:\\s*(\\d+)[lL]?\\s*,\\s*\"username\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** 命令超时：Redis 不通时必须快速失败，而不是把每个管理端请求拖到 30 秒。 */
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(3);

    private final FazeAdminAuthProperties properties;

    /** 独立连接工厂；**不注册为 Bean**，见类注释第 1 条。 */
    private LettuceConnectionFactory connectionFactory;

    private StringRedisTemplate redisTemplate;

    /**
     * 构造存储。
     *
     * @param properties 管理端鉴权配置（Redis 地址、库号、key 前缀）
     */
    public AdminSessionStore(FazeAdminAuthProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        RedisStandaloneConfiguration standalone =
                new RedisStandaloneConfiguration(properties.redisHost(), properties.redisPort());
        standalone.setDatabase(properties.redisDatabase());
        if (StringUtils.hasText(properties.redisPassword())) {
            standalone.setPassword(RedisPassword.of(properties.redisPassword()));
        }
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(COMMAND_TIMEOUT)
                .build();
        this.connectionFactory = new LettuceConnectionFactory(standalone, clientConfig);
        this.connectionFactory.afterPropertiesSet();
        this.redisTemplate = new StringRedisTemplate(this.connectionFactory);
        this.redisTemplate.afterPropertiesSet();
        log.info("管理端鉴权桥已就绪 redis={}:{} db={} keyPrefix={}",
                properties.redisHost(), properties.redisPort(),
                properties.redisDatabase(), properties.tokenKeyPrefix());
    }

    @Override
    public void destroy() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    /**
     * 按 JWT 里的 `login_user_key` 取登录态。
     *
     * <p>副作用：一次 Redis `GET`。</p>
     *
     * @param loginUserKey RuoYi 签发的登录态 UUID；null 或空直接返回空
     * @return 登录态；key 不存在（未登录 / 已过期 / 已登出）或抽取失败时为空
     */
    public Optional<AdminSession> find(@Nullable String loginUserKey) {
        if (!StringUtils.hasText(loginUserKey)) {
            return Optional.empty();
        }
        if (redisTemplate == null) {
            return Optional.empty();
        }
        String raw;
        try {
            raw = redisTemplate.opsForValue().get(properties.tokenKeyPrefix() + loginUserKey);
        } catch (RuntimeException e) {
            // Redis 不可用：当作未登录（fail closed），但日志要能区分「没登录」与「读不到」
            log.error("读取 RuoYi 登录态失败 key={}", properties.tokenKeyPrefix() + loginUserKey, e);
            return Optional.empty();
        }
        return parse(raw);
    }

    /**
     * 从 RuoYi 的 LoginUser 序列化文本里抽出 adminId 与 username。
     *
     * <p>抽不出来时打 error 并返回空——**绝不兜底成默认账号**（类注释第 4 条）。</p>
     *
     * @param raw Redis 里的原始值
     * @return 登录态；为空或格式不认识时为空
     */
    Optional<AdminSession> parse(@Nullable String raw) {
        if (!StringUtils.hasText(raw)) {
            return Optional.empty();
        }
        Matcher matcher = LOGIN_USER_PATTERN.matcher(raw);
        if (!matcher.find()) {
            // 只打前 160 字符：足够定位格式变化，又不至于把整份登录态刷进日志
            log.error("RuoYi 登录态格式无法识别（LoginUser 结构可能已变更），样本前缀：{}",
                    raw.length() > 160 ? raw.substring(0, 160) + "..." : raw);
            return Optional.empty();
        }
        try {
            Long adminId = Long.valueOf(matcher.group(1));
            return Optional.of(new AdminSession(adminId, unescape(matcher.group(2))));
        } catch (NumberFormatException e) {
            log.error("RuoYi 登录态里的 userId 不是合法整数：{}", matcher.group(1));
            return Optional.empty();
        }
    }

    /**
     * 还原 JSON 字符串转义（登录名里理论上不会有，但不做就是潜在的错误值）。
     *
     * @param raw 匹配到的原始字符串内容
     * @return 还原后的文本
     */
    private static String unescape(String raw) {
        if (raw.indexOf('\\') < 0) {
            return raw;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '\\' || i + 1 >= raw.length()) {
                sb.append(c);
                continue;
            }
            char next = raw.charAt(++i);
            switch (next) {
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (i + 4 < raw.length()) {
                        sb.append((char) Integer.parseInt(raw.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                }
                default -> sb.append(next);
            }
        }
        return sb.toString();
    }
}
