package com.zhiyun.hospital.interceptor;


import com.baomidou.mybatisplus.core.exceptions.MybatisPlusException;
import com.baomidou.mybatisplus.core.toolkit.EncryptUtils;
import com.baomidou.mybatisplus.core.toolkit.PluginUtils;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.scripting.xmltags.DynamicSqlSource;
import org.apache.ibatis.scripting.xmltags.MixedSqlNode;
import org.apache.ibatis.scripting.xmltags.TextSqlNode;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 基于Mybatis-Plus提供的 BlockAttackInnerInterceptor 进行定制修改
 * 攻击 SQL 阻断解析器,防止全表更新与删除
 *
 * 若有需求使用全表更新，请手动写SQL
 * 并添加忽略插件拦截器注解 {@link com.baomidou.mybatisplus.annotation.InterceptorIgnore}
 * 其中 blockAttack = true
 * 支持注解在 mapper 上以及 mapper.method 上
 * 同时存在则 mapper.method 比 mapper 优先级高
 *
 * @author xiaozhikuan
 * @since mybatis-plus 3.4.0
 */
@Slf4j
public class BlockAttackInnerBoostInterceptor extends BlockAttackInnerInterceptor {
    /**
     * 缓存验证结果，提高性能
     */
    private static final Set<String> cacheValidResult = new HashSet<>();

    @Override
    protected void checkWhere(Expression where, String ex) {
        //原来的基础判断继续
        super.checkWhere(where,ex);
        //继续判断 仅有一个条件 deleted = 0 的情况，防止全表更新
        if (where instanceof EqualsTo) {
            EqualsTo equalsTo = (EqualsTo) where;
            Expression leftExpression = equalsTo.getLeftExpression();
            Expression rightExpression = equalsTo.getRightExpression();
            boolean logicDelCheck = "deleted".equals(leftExpression.toString()) && "0".equals(rightExpression.toString());
            if(logicDelCheck){
                throw new MybatisPlusException(ex);
            }
        }
    }

    /**
     * 操作前的判断
     * @param sh
     * @param connection
     * @param transactionTimeout
     */
    @Override
    public void beforePrepare(StatementHandler sh, Connection connection, Integer transactionTimeout) {
        PluginUtils.MPStatementHandler handler = PluginUtils.mpStatementHandler(sh);
        BoundSql boundSql = handler.boundSql();
        String originalSql = boundSql.getSql();
        String md5Base64 = EncryptUtils.md5Base64(originalSql);
        if (cacheValidResult.contains(md5Base64)) {
            logger.debug("该SQL已验证，无需再次验证，，SQL:" + originalSql);
            return;
        }
         //拦截使用$的sql
        try {
            MappedStatement ms = handler.mappedStatement();
            SqlSource sqlSource = ms.getSqlSource();
            if (sqlSource instanceof DynamicSqlSource){
                Object rootSqlNode = getPrivateParam(sqlSource, "rootSqlNode");
                if (rootSqlNode instanceof MixedSqlNode){
                    Object contents = getPrivateParam(rootSqlNode, "contents");
                    if(contents instanceof List){
                        List<Object> sqlNodes = (List<Object>)contents;
                        for (Object node : sqlNodes) {
                            //如果是TextSqlNode则表明原始SQL种存在$，打印该SQL并抛出异常
                            if (node instanceof TextSqlNode){
                                Object o2 = getPrivateParam(node, "text");
                                log.warn("替换参数前的SQL，$符号不建议使用 SQL:{}",o2);
                                throw new MybatisPlusException("非法SQL，禁止使用$符");
                            }
                        }
                    }
                }
            }
        } catch (NoSuchFieldException | IllegalAccessException ex) {
            log.error("Mybatis-Plus 拦截异常",ex);
            throw new MybatisPlusException("Mybatis-Plus 拦截异常");
        }
        //继续完成父类的方法
        super.beforePrepare(sh,connection,transactionTimeout);
        //缓存验证结果
        cacheValidResult.add(md5Base64);
    }

    /**
     * 从指定对象反射获取私有字段
     * 请确保使用的时候有该字段
     * @param obj
     * @param filedName
     * @return
     * @throws NoSuchFieldException
     * @throws IllegalAccessException
     */
    private Object getPrivateParam(Object obj,final String filedName) throws NoSuchFieldException, IllegalAccessException {
        Field field = obj.getClass().getDeclaredField(filedName);
        field.setAccessible(true);
        return field.get(obj);
    }
}
