package com.zhiyun.hospital.interceptor;

import com.zhiyun.hospital.util.*;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Statement;
import java.util.*;

/**
 * 性能分析拦截器，超过指定时间(默认2000 毫秒)，将会抛出异常暂停业务
 * 建议是在非线上环境运行
 * maxTime: SQL超时时间
 * isThrowEx: 是否抛出业务异常阻止后续操作
 * 配置可以自定义：
 * # application.yml配置
 * mybatis:
 *   configurationProperties:
 * 		# 最大超时时间 默认2000 ms
 *     maxTime: 500
 * 		# 是否需要抛出业务异常 默认false
 *     isThrowEx: true
 *
 *
 * @author xiaozhikuan
 * @since 2020年10月23日
 */
@Intercepts({
    @Signature(type = StatementHandler.class, method = "query", args = {Statement.class, ResultHandler.class}),
    @Signature(type = StatementHandler.class, method = "update", args = {Statement.class}),
    @Signature(type = StatementHandler.class, method = "batch", args = {Statement.class})
})
public class PerformanceInterceptor implements Interceptor {

    private static final Logger logger = LoggerFactory.getLogger(PerformanceInterceptor.class);
    /**
     * SQL 执行最大时长，超过自动停止运行，有助于发现问题。
     */
    @Setter
    @Getter
    @Accessors(chain = true)
    private long maxTime = 2000;

    /**
     * 是否需要由于超时而抛出业务异常
     * true 抛出异常
     * false 不抛出异常
     */
    @Setter
    @Getter
    private boolean isThrowEx = false;

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        StatementHandler statementHandler = PluginUtils.realTarget(invocation.getTarget());
        PluginUtils.MPStatementHandler mpStatementHandler = PluginUtils.mpStatementHandler(statementHandler);
        String originalSql = mpStatementHandler.boundSql().getSql();

        originalSql = originalSql.replaceAll("[\\s]+", StringPool.SPACE);
        int index = indexOfSqlStart(originalSql);
        if (index > 0) {
            originalSql = originalSql.substring(index);
        }

        // 计算执行 SQL 耗时
        long start = System.currentTimeMillis();
        Object result = invocation.proceed();
        long timing = System.currentTimeMillis() - start;
        logger.debug("SQL耗时："+timing+" ms | SQL："+originalSql);
        boolean isOverSpend = this.getMaxTime() >= 1 && timing > this.getMaxTime();
        //isThrowEx 开关打开才会主动抛出异常
        Assert.isFalse(isThrowEx & isOverSpend,
                " 该SQL耗时超过"+maxTime+" ms, 请优化 ! SQL："+originalSql);
        if (isOverSpend){
            logger.error(" 该SQL耗时超过"+maxTime+" ms, 请优化 ! SQL："+originalSql);
        }

        return result;
    }

    @Override
    public Object plugin(Object target) {
        if (target instanceof StatementHandler) {
            return Plugin.wrap(target, this);
        }
        return target;
    }

    @Override
    public void setProperties(Properties prop) {
        String maxTime = prop.getProperty("maxTime");
        String isThrowEx = prop.getProperty("isThrowEx");
        if (StringUtils.isNotEmpty(maxTime)) {
            this.maxTime = Long.parseLong(maxTime);
        }
        if (StringUtils.isNotEmpty(isThrowEx)) {
            this.isThrowEx = Boolean.parseBoolean(isThrowEx);
        }
    }


    /**
     * 获取sql语句开头部分
     *
     * @param sql ignore
     * @return ignore
     */
    private int indexOfSqlStart(String sql) {
        String upperCaseSql = sql.toUpperCase();
        Set<Integer> set = new HashSet<>();
        set.add(upperCaseSql.indexOf("SELECT "));
        set.add(upperCaseSql.indexOf("UPDATE "));
        set.add(upperCaseSql.indexOf("INSERT "));
        set.add(upperCaseSql.indexOf("DELETE "));
        set.remove(-1);
        if (CollectionUtils.isEmpty(set)) {
            return -1;
        }
        List<Integer> list = new ArrayList<>(set);
        list.sort(Comparator.naturalOrder());
        return list.get(0);
    }
}
