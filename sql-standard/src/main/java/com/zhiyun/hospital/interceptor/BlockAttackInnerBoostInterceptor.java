package com.zhiyun.hospital.interceptor;

import com.zhiyun.hospital.exception.SqlStandardException;
import com.zhiyun.hospital.util.Assert;
import com.zhiyun.hospital.util.EncryptUtils;
import com.zhiyun.hospital.util.InterceptorIgnoreHelper;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.update.Update;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ReflectionException;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.scripting.xmltags.DynamicSqlSource;
import org.apache.ibatis.scripting.xmltags.TextSqlNode;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * 基于Mybatis-Plus提供的 BlockAttackInnerInterceptor 进行定制修改
 * 攻击 SQL 阻断解析器,防止全表更新与删除
 * <p>
 * 若有需求使用全表更新，请手动写SQL
 * 并添加忽略插件拦截器注解 {@link com.zhiyun.hospital.InterceptorIgnore}
 * 其中 blockAttack = true
 * 支持注解在 mapper 上以及 mapper.method 上
 * 同时存在则 mapper.method 比 mapper 优先级高
 *
 * @author xiaozhikuan
 * @since mybatis
 */
@Intercepts({@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}),
    @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class,
        ResultHandler.class}),
    @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class,
        ResultHandler.class, CacheKey.class, BoundSql.class}),})
@Slf4j
public class BlockAttackInnerBoostInterceptor extends JsqlParserSupport implements Interceptor {
    /**
     * 缓存验证结果，提高性能
     */
    private static final Set<String> cacheValidResult = new HashSet<>(64);

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        MappedStatement ms = (MappedStatement)invocation.getArgs()[0];
        Object parameter = null;
        if (invocation.getArgs().length > 1) {
            parameter = invocation.getArgs()[1];
        }
        //参数填充后sql
        BoundSql boundSql = ms.getBoundSql(parameter);
        //缓存判断
        String originalSql = boundSql.getSql();
        String md5Base64 = EncryptUtils.md5Base64(originalSql);
        if (cacheValidResult.contains(md5Base64)) {
            log.debug("该SQL已验证，无需再次验证，，SQL:" + originalSql);
            return invocation.proceed();
        }
        SqlCommandType sct = ms.getSqlCommandType();
        if (sct == SqlCommandType.UPDATE || sct == SqlCommandType.DELETE) {
            if (InterceptorIgnoreHelper.willIgnoreBlockAttack(ms.getId())) {
                //缓存验证结果
                cacheValidResult.add(md5Base64);
                return invocation.proceed();
            }
            //sql校验
            parserMulti(boundSql.getSql(), null);
        }

        //拦截使用$的sql
        try {
            SqlSource sqlSource = ms.getSqlSource();
            if (sqlSource instanceof DynamicSqlSource) {
                MetaObject metaObject = SystemMetaObject.forObject(sqlSource);
                Object contents = metaObject.getValue("rootSqlNode.contents");
                if (contents instanceof List) {
                    List<Object> sqlNodes = (List<Object>)contents;
                    for (Object node : sqlNodes) {
                        //如果是TextSqlNode则表明原始SQL种存在$，打印该SQL并抛出异常
                        if (node instanceof TextSqlNode) {
                            Object o2 = getPrivateParam(node, "text");
                            log.warn("替换参数前的SQL，$符号不建议使用 SQL:{}", o2);
                            throw new SqlStandardException("非法SQL，禁止使用$符");
                        }
                    }
                }
            }
        } catch (NoSuchFieldException | IllegalAccessException | ReflectionException ex) {
            log.error("Mybatis-Plus 拦截器 拦截异常 | originalSql:{}", originalSql, ex);
            throw new SqlStandardException("Mybatis-Plus 拦截异常");
        }

        //缓存验证结果
        cacheValidResult.add(md5Base64);
        return invocation.proceed();
    }

    @Override
    protected void processDelete(Delete delete, int index, Object obj) {
        this.checkWhere(delete.getWhere(), "Prohibition of full table deletion");
    }

    @Override
    protected void processUpdate(Update update, int index, Object obj) {
        this.checkWhere(update.getWhere(), "Prohibition of table update operation");
    }

    protected void checkWhere(Expression where, String ex) {
        Assert.notNull(where, ex);
        if (where instanceof EqualsTo) {
            // example: 1=1
            EqualsTo equalsTo = (EqualsTo)where;
            Expression leftExpression = equalsTo.getLeftExpression();
            Expression rightExpression = equalsTo.getRightExpression();
            Assert.isFalse(leftExpression.toString().equals(rightExpression.toString()), ex);
        } else if (where instanceof NotEqualsTo) {
            // example: 1 != 2
            NotEqualsTo notEqualsTo = (NotEqualsTo)where;
            Expression leftExpression = notEqualsTo.getLeftExpression();
            Expression rightExpression = notEqualsTo.getRightExpression();
            Assert.isTrue(leftExpression.toString().equals(rightExpression.toString()), ex);
        }
        //继续判断 仅有一个条件 deleted = 0 的情况，防止全表更新
        if (where instanceof EqualsTo) {
            EqualsTo equalsTo = (EqualsTo)where;
            Expression leftExpression = equalsTo.getLeftExpression();
            Expression rightExpression = equalsTo.getRightExpression();
            boolean logicDelCheck =
                "deleted".equals(leftExpression.toString()) && "0".equals(rightExpression.toString());
            if (logicDelCheck) {
                throw new SqlStandardException(ex);
            }
        }
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {
        // NOP
    }

    /**
     * 从指定对象反射获取私有字段
     * 请确保使用的时候有该字段
     *
     * @param obj
     * @param filedName
     * @return
     * @throws NoSuchFieldException
     * @throws IllegalAccessException
     */
    private Object getPrivateParam(Object obj, final String filedName)
        throws NoSuchFieldException, IllegalAccessException {
        Field field = obj.getClass().getDeclaredField(filedName);
        field.setAccessible(true);
        return field.get(obj);
    }
}
