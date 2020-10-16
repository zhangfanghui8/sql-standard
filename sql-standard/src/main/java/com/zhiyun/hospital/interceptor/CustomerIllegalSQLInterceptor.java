package com.zhiyun.hospital.interceptor;

import com.zhiyun.hospital.exception.SqlStandardException;
import com.zhiyun.hospital.util.EncryptUtils;
import com.zhiyun.hospital.util.PluginUtils;
import lombok.Data;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.statement.update.Update;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.*;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 由于开发人员水平参差不齐，即使订了开发规范很多人也不遵守
 * <p>SQL是影响系统性能最重要的因素，所以拦截掉垃圾SQL语句</p>
 * <br>
 * <p>拦截SQL类型的场景</p>
 * <p>1.必须使用到索引，包含left join连接字段，符合索引最左原则</p>
 * <p>必须使用索引好处，</p>
 * <p>1.1 如果因为动态SQL，bug导致update的where条件没有带上，全表更新上万条数据</p>
 * <p>1.2 如果检查到使用了索引，SQL性能基本不会太差</p>
 * <br>
 * <p>2.SQL尽量单表执行，有查询left join的语句，必须在注释里面允许该SQL运行，否则会被拦截，有left join的语句，如果不能拆成单表执行的SQL，请leader商量在做</p>
 * <p>https://gaoxianglong.github.io/shark</p>
 * <p>SQL尽量单表执行的好处</p>
 * <p>2.1 查询条件简单、易于开理解和维护；</p>
 * <p>2.2 扩展性极强；（可为分库分表做准备）</p>
 * <p>2.3 缓存利用率高；</p>
 * <p>2.在字段上使用函数</p>
 * <br>
 * <p>3.where条件为空</p>
 * <p>4.where条件使用了 !=</p>
 * <p>5.where条件使用了 not 关键字</p>
 * <p>6.where条件使用了 or 关键字</p>
 * <p>7.where条件使用了 使用子查询</p>
 *
 * @author zhangfanghui
 * @Title:
 * @Description:
 * @date 2020/10/15 18:01
 */
@Intercepts({@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class})})
public class CustomerIllegalSQLInterceptor extends JsqlParserSupport implements Interceptor {

    private static final Log logger = LogFactory.getLog(CustomerIllegalSQLInterceptor.class);

    private String path = null;
    /**
     * 缓存验证结果，提高性能
     */
    private static final Set<String> cacheValidResult = new HashSet<>();
    /**
     * 缓存表的索引信息
     */
    private static final Map<String, List<CustomerIllegalSQLInterceptor.IndexInfo>> indexInfoMap =
        new ConcurrentHashMap<>();

    public CustomerIllegalSQLInterceptor(String path) {
        this.path = path;
    }

    /**
     * 验证expression对象是不是 or、not等等
     *
     * @param expression ignore
     */
    private void validExpression(Expression expression) {
        //where条件使用了 or 关键字
        if (expression instanceof OrExpression) {
            OrExpression orExpression = (OrExpression)expression;
            throw new SqlStandardException("非法SQL，where条件中不能使用【or】关键字，错误or信息：" + orExpression.toString());
        } else if (expression instanceof NotEqualsTo) {
            NotEqualsTo notEqualsTo = (NotEqualsTo)expression;
            throw new SqlStandardException("非法SQL，where条件中不能使用【!=】关键字，错误!=信息：" + notEqualsTo.toString());
        } else if (expression instanceof BinaryExpression) {
            BinaryExpression binaryExpression = (BinaryExpression)expression;
            // TODO 升级 jsqlparser 后待实现
            //            if (binaryExpression.isNot()) {
            //                throw new SqlStandardException("非法SQL，where条件中不能使用【not】关键字，错误not信息：" + binaryExpression.toString());
            //            }
            if (binaryExpression.getLeftExpression() instanceof Function) {
                Function function = (Function)binaryExpression.getLeftExpression();
                throw new SqlStandardException("非法SQL，where条件中不能使用数据库函数，错误函数信息：" + function.toString());
            }
            if (binaryExpression.getRightExpression() instanceof SubSelect) {
                SubSelect subSelect = (SubSelect)binaryExpression.getRightExpression();
                throw new SqlStandardException("非法SQL，where条件中不能使用子查询，错误子查询SQL信息：" + subSelect.toString());
            }
        } else if (expression instanceof InExpression) {
            InExpression inExpression = (InExpression)expression;
            if (inExpression.getRightItemsList() instanceof SubSelect) {
                SubSelect subSelect = (SubSelect)inExpression.getRightItemsList();
                throw new SqlStandardException("非法SQL，where条件中不能使用子查询，错误子查询SQL信息：" + subSelect.toString());
            }
        }

    }

    /**
     * 如果SQL用了 left Join，验证是否有or、not等等，并且验证是否使用了索引
     *
     * @param joins      ignore
     * @param table      ignore
     * @param connection ignore
     */
    private void validJoins(List<Join> joins, Table table, Connection connection) {
        if (!CollectionUtils.isEmpty(joins) && joins.size() >= 3) {
            throw new SqlStandardException("非法SQL，超过三个表禁止join");
        }
        //允许执行join，验证jion是否使用索引等等
        if (joins != null) {
            for (Join join : joins) {
                Table rightTable = (Table)join.getRightItem();
                Expression expression = join.getOnExpression();
                validWhere(expression, table, rightTable, connection);
            }
        }
    }

    /**
     * 检查是否使用索引
     *
     * @param table      ignore
     * @param columnName ignore
     * @param connection ignore
     */
    private void validUseIndex(Table table, String columnName, Connection connection) {
        //是否使用索引
        boolean useIndexFlag = false;

        String tableInfo = table.getName();
        //表存在的索引
        String dbName = null;
        String tableName;
        String[] tableArray = tableInfo.split("\\.");
        if (tableArray.length == 1) {
            tableName = tableArray[0];
        } else {
            dbName = tableArray[0];
            tableName = tableArray[1];
        }
        List<CustomerIllegalSQLInterceptor.IndexInfo> indexInfos = getIndexInfos(dbName, tableName, connection);
        for (CustomerIllegalSQLInterceptor.IndexInfo indexInfo : indexInfos) {
            if (null != columnName && columnName.equalsIgnoreCase(indexInfo.getColumnName())) {
                useIndexFlag = true;
                break;
            }
        }
        if (!useIndexFlag) {
            throw new SqlStandardException("非法SQL，SQL未使用到索引, table:" + table + ", columnName:" + columnName);
        }
    }

    /**
     * 验证where条件的字段，是否有not、or等等，并且where的第一个字段，必须使用索引
     *
     * @param expression ignore
     * @param table      ignore
     * @param connection ignore
     */
    private void validWhere(Expression expression, Table table, Connection connection) {
        validWhere(expression, table, null, connection);
    }

    /**
     * 验证where条件的字段，是否有not、or等等，并且where的第一个字段，必须使用索引
     *
     * @param expression ignore
     * @param table      ignore
     * @param joinTable  ignore
     * @param connection ignore
     */
    private void validWhere(Expression expression, Table table, Table joinTable, Connection connection) {
        validExpression(expression);
        if (expression instanceof BinaryExpression) {
            //获得左边表达式
            Expression leftExpression = ((BinaryExpression)expression).getLeftExpression();
            validExpression(leftExpression);

            //如果左边表达式为Column对象，则直接获得列名
            if (leftExpression instanceof Column) {
                Expression rightExpression = ((BinaryExpression)expression).getRightExpression();
                if (joinTable != null && rightExpression instanceof Column) {
                    if (Objects.equals(((Column)rightExpression).getTable().getName(), table.getAlias().getName())) {
                        validUseIndex(table, ((Column)rightExpression).getColumnName(), connection);
                        validUseIndex(joinTable, ((Column)leftExpression).getColumnName(), connection);
                    } else {
                        validUseIndex(joinTable, ((Column)rightExpression).getColumnName(), connection);
                        validUseIndex(table, ((Column)leftExpression).getColumnName(), connection);
                    }
                } else {
                    //获得列名
                    validUseIndex(table, ((Column)leftExpression).getColumnName(), connection);
                }
            }
            //如果BinaryExpression，进行迭代
            else if (leftExpression instanceof BinaryExpression) {
                validWhere(leftExpression, table, joinTable, connection);
            }

            //获得右边表达式，并分解
            Expression rightExpression = ((BinaryExpression)expression).getRightExpression();
            validExpression(rightExpression);
        }
    }

    /**
     * 验证select item 部分规则
     *
     * @param selectItems
     */
    private static void validSelectItem(List<SelectItem> selectItems) {
        //select语句禁止使用count(*)
        if (selectItems.stream().filter(f -> (f.toString().toLowerCase().contains("count(*)"))).findFirst()
            .isPresent()) {
            throw new SqlStandardException("非法SQL，SQL使用到'count(*)'");
        }
        //select语句禁止使用*
        if (selectItems.stream().filter(f -> (f.toString().contains("*"))).findFirst()
            .isPresent()) {
            throw new SqlStandardException("非法SQL，SQL使用到'select *'");
        }
    }

    /**
     * 得到表的索引信息
     *
     * @param dbName    ignore
     * @param tableName ignore
     * @param conn      ignore
     * @return ignore
     */
    public List<CustomerIllegalSQLInterceptor.IndexInfo> getIndexInfos(String dbName, String tableName,
        Connection conn) {
        return getIndexInfos(null, dbName, tableName, conn);
    }

    /**
     * 得到表的索引信息
     *
     * @param key       ignore
     * @param dbName    ignore
     * @param tableName ignore
     * @param conn      ignore
     * @return ignore
     */
    public List<CustomerIllegalSQLInterceptor.IndexInfo> getIndexInfos(String key, String dbName, String tableName,
        Connection conn) {
        List<CustomerIllegalSQLInterceptor.IndexInfo> indexInfos = null;
        if (!StringUtils.isEmpty(key)) {
            indexInfos = indexInfoMap.get(key);
        }
        if (indexInfos == null || indexInfos.isEmpty()) {
            ResultSet rs;
            try {
                DatabaseMetaData metadata = conn.getMetaData();
                String catalog = StringUtils.isEmpty(dbName) ? conn.getCatalog() : dbName;
                String schema = StringUtils.isEmpty(dbName) ? conn.getSchema() : dbName;
                rs = metadata.getIndexInfo(catalog, schema, tableName, false, true);
                indexInfos = new ArrayList<>();
                while (rs.next()) {
                    //索引中的列序列号等于1，才有效
                    if (Objects.equals(rs.getString(8), "1")) {
                        CustomerIllegalSQLInterceptor.IndexInfo indexInfo =
                            new CustomerIllegalSQLInterceptor.IndexInfo();
                        indexInfo.setDbName(rs.getString(1));
                        indexInfo.setTableName(rs.getString(3));
                        indexInfo.setColumnName(rs.getString(9));
                        indexInfos.add(indexInfo);
                    }
                }
                if (!StringUtils.isEmpty(key)) {
                    indexInfoMap.put(key, indexInfos);
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
        return indexInfos;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        StatementHandler statementHandler = PluginUtils.realTarget(invocation.getTarget());
        PluginUtils.MPStatementHandler mpStatementHandler = PluginUtils.mpStatementHandler(statementHandler);
        MappedStatement ms = mpStatementHandler.mappedStatement();
        Connection connection = (Connection)invocation.getArgs()[0];
        // 如果是insert操作 不进行验证
        if (SqlCommandType.INSERT.equals(ms.getSqlCommandType())) {
            return invocation.proceed();
        }
        if (StringUtils.isEmpty(path) || ms.getId().equals(path)) {
            BoundSql boundSql = mpStatementHandler.boundSql();
            String originalSql = boundSql.getSql();
            logger.debug("检查SQL是否合规，SQL:" + originalSql);
            String md5Base64 = EncryptUtils.md5Base64(originalSql);
            if (cacheValidResult.contains(md5Base64)) {
                logger.debug("该SQL已验证，无需再次验证，，SQL:" + originalSql);
                return invocation.proceed();
            }
            parserSingle(originalSql, connection);
            //缓存验证结果
            cacheValidResult.add(md5Base64);
        }
        return invocation.proceed();
    }

    @Override
    protected void processSelect(Select select, int index, Object obj) {
        PlainSelect plainSelect = (PlainSelect)select.getSelectBody();
        Expression where = plainSelect.getWhere();
        Assert.notNull(where, "非法SQL，必须要有where条件");
        Table table = (Table)plainSelect.getFromItem();
        List<Join> joins = plainSelect.getJoins();
        List<SelectItem> selectItems = plainSelect.getSelectItems();
        if(!CollectionUtils.isEmpty(selectItems)){
            validSelectItem(selectItems);
        }
        validWhere(where, table, (Connection)obj);
        validJoins(joins, table, (Connection)obj);
    }

    @Override
    protected void processUpdate(Update update, int index, Object obj) {
        Expression where = update.getWhere();
        Assert.notNull(where, "非法SQL，必须要有where条件");
        Table table = update.getTable();
        List<Join> joins = update.getJoins();
        validWhere(where, table, (Connection)obj);
        validJoins(joins, table, (Connection)obj);
    }

    @Override
    protected void processDelete(Delete delete, int index, Object obj) {
        Expression where = delete.getWhere();
        Assert.notNull(where, "非法SQL，必须要有where条件");
        Table table = delete.getTable();
        List<Join> joins = delete.getJoins();
        validWhere(where, table, (Connection)obj);
        validJoins(joins, table, (Connection)obj);
    }

    @Override
    public Object plugin(Object target) {
        if (target instanceof StatementHandler) {
            return Plugin.wrap(target, this);
        }
        return target;
    }
    /**
     * parserMulti
     * 索引对象
     */
    @Data
    private static class IndexInfo {

        private String dbName;

        private String tableName;

        private String columnName;
    }
}
