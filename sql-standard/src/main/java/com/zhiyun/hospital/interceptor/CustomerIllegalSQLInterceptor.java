package com.zhiyun.hospital.interceptor;

import com.baomidou.mybatisplus.core.exceptions.MybatisPlusException;
import com.baomidou.mybatisplus.core.plugins.InterceptorIgnoreHelper;
import com.baomidou.mybatisplus.core.toolkit.*;
import com.baomidou.mybatisplus.extension.parser.JsqlParserSupport;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import lombok.Data;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.ItemsList;
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.statement.update.Update;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlCommandType;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 由于开发人员水平参差不齐，即使订了开发规范很多人也不遵守
 * <p>SQL是影响系统性能最重要的因素，所以拦截掉垃圾SQL语句</p>
 * <br>
 * 业务上具有唯一特性的字段，即使是多个字段的组合，也必须建成唯一索引。
 * <br>
 * 超过三个表禁止 join。需要 join 的字段，数据类型必须绝对一致;多表关联查询时， 保证被关联的字段需要有索引。(原因:性能低,sql语句相对复杂不利于维护、理解,如果以后分库分表不利)
 * 说明:即使双表 join 也要注意表索引、SQL 性能。
 * <br>
 * 不要使用 count(列名)或 count(常量)来替代 count(*)，count(*)是 SQL92 定义的 标准统计行数的语法，跟数据库无关，跟 NULL 和非 NULL 无关。说明:count(*)会统计值为 NULL 的行，而 count(列名)不会统计此列为 NULL 值的行。
 * <br>
 * 不要使用 select * ,建议需要什么数据就拿什么数据,多余的数据字段需要网络带宽、内存,影响性能
 * <br>
 * where后面必须要有条件,必须要用到索引，包含left join连接字段，符合索引最左原则(原因:1、如果因为动态SQL，bug导致update,delete的where条件没有带上，全表操作; 2、使用了索引，SQL性能基本不检查到会太差)
 * <br>
 * sql不等于尽量使用<>,!=有些版本、平台上不适用,而<>是sql标准不等于
 * <br>
 * where后面尽量不适用数据库函数
 *
 * @author zhangfanghui
 * @Title:
 * @Description:
 * @date 2020/10/15 18:01
 */
public class CustomerIllegalSQLInterceptor extends JsqlParserSupport implements InnerInterceptor {

    private List<String> paths = null;
    /**
     * 缓存验证结果，提高性能
     */
    private static final Set<String> cacheValidResult = new HashSet<>();
    /**
     * 缓存表的索引信息
     */
    private static final Map<String, List<CustomerIllegalSQLInterceptor.IndexInfo>> indexInfoMap =
        new ConcurrentHashMap<>();

    public CustomerIllegalSQLInterceptor(List<String> paths) {
        this.paths = paths;
    }

    @Override
    public void beforePrepare(StatementHandler sh, Connection connection, Integer transactionTimeout) {
        PluginUtils.MPStatementHandler mpStatementHandler = PluginUtils.mpStatementHandler(sh);
        MappedStatement ms = mpStatementHandler.mappedStatement();
        SqlCommandType sct = ms.getSqlCommandType();
        if (sct == SqlCommandType.INSERT || InterceptorIgnoreHelper.willIgnoreIllegalSql(ms.getId())) {
            return;
        }
        if (CollectionUtils.isEmpty(paths) || (!CollectionUtils.isEmpty(paths) &&
                paths.stream().anyMatch(path -> ms.getId().contains(path)))) {
            BoundSql boundSql = mpStatementHandler.boundSql();
            String originalSql = boundSql.getSql();
            logger.debug("检查SQL是否合规，SQL:" + originalSql);
            String md5Base64 = EncryptUtils.md5Base64(originalSql);
            if (cacheValidResult.contains(md5Base64)) {
                logger.debug("该SQL已验证，无需再次验证，，SQL:" + originalSql);
                return;
            }
            parserSingle(originalSql, connection);
            boolean doCache = validLimit(boundSql, connection);
            //缓存验证结果
            if (doCache){
               cacheValidResult.add(md5Base64);
            }
        }
    }

    @Override
    protected void processSelect(Select select, int index, Object obj) {
        PlainSelect plainSelect = (PlainSelect)select.getSelectBody();
        Expression where = plainSelect.getWhere();
        Assert.notNull(where, "非法SQL，必须要有where条件");
        Table table = (Table)plainSelect.getFromItem();
        List<Join> joins = plainSelect.getJoins();
        List<SelectItem> selectItems = plainSelect.getSelectItems();
        if (CollectionUtils.isNotEmpty(selectItems)) {
            selectItems.forEach(CustomerIllegalSQLInterceptor::validSelectItem);
        }
        Limit limit = plainSelect.getLimit();
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

    /**
     * 验证expression对象是不是 or、not等等
     *
     * @param expression ignore
     */
    private void validExpression(Expression expression) {
        //where条件使用了 or 关键字

        if (expression instanceof OrExpression) {
//            OrExpression orExpression = (OrExpression)expression;
//            throw new MybatisPlusException("非法SQL，where条件中不能使用【or】关键字，错误or信息：" + orExpression.toString());
        } else if (expression instanceof NotEqualsTo) {
            NotEqualsTo notEqualsTo = (NotEqualsTo)expression;
            String notEqualsToStr = notEqualsTo.getStringExpression().trim();
            if(notEqualsToStr.contains("!=")|| notEqualsToStr.contains("<>")){
                throw new MybatisPlusException("非法SQL，where条件中不能使用【!= 或 <>】关键字，错误!=信息：" + notEqualsTo.toString());
            }
        } else if (expression instanceof BinaryExpression) {
            BinaryExpression binaryExpression = (BinaryExpression)expression;
            // TODO 升级 jsqlparser 后待实现
            //            if (binaryExpression.isNot()) {
            //                throw new MybatisPlusException("非法SQL，where条件中不能使用【not】关键字，错误not信息：" + binaryExpression.toString());
            //            }
            if (binaryExpression.getLeftExpression() instanceof Function) {
                Function function = (Function)binaryExpression.getLeftExpression();
                throw new MybatisPlusException("非法SQL，where条件中不能使用数据库函数，错误函数信息：" + function.toString());
            }
            if (binaryExpression.getRightExpression() instanceof SubSelect) {
                SubSelect subSelect = (SubSelect)binaryExpression.getRightExpression();
                throw new MybatisPlusException("非法SQL，where条件中不能使用子查询，错误子查询SQL信息：" + subSelect.toString());
            }
        } else if (expression instanceof InExpression) {
            InExpression inExpression = (InExpression)expression;
            ItemsList rightItemsList = inExpression.getRightItemsList();
            if (rightItemsList instanceof ExpressionList){
                ExpressionList expressionList = (ExpressionList)rightItemsList;
                List<Expression> expressions = expressionList.getExpressions();
                Assert.isFalse(expressions.size() > 1000,"非法SQL，where条件中【in】关键字查询数量不可超过1000");
            }

//            if (inExpression.getRightItemsList() instanceof SubSelect) {
//                SubSelect subSelect = (SubSelect)inExpression.getRightItemsList();
//                throw new SqlStandardException("非法SQL，where条件中不能使用子查询，错误子查询SQL信息：" + subSelect.toString());
//            }
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
        if (CollectionUtils.isNotEmpty(joins) && joins.size() >= 3) {
            throw new MybatisPlusException("非法SQL，超过三个表禁止join");
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
     * 验证limit条件，如果是动态的参数则不进行缓存
     * @param boundSql
     * @param connection
     * @return true 缓存 false 不进行缓存
     */
    private boolean validLimit(BoundSql boundSql, Connection connection) {
        //默认缓存
        boolean isCache = true;
        Statement statement = null;
        String sql = boundSql.getSql();
        try {
            statement = CCJSqlParserUtil.parse(sql);
        } catch (JSQLParserException e) {
            logger.error("解析SQL出错， sql: "+sql,e);
        }
        if (statement instanceof Select) {
            Select select = (Select)statement;
            PlainSelect plainSelect = (PlainSelect)select.getSelectBody();
            Limit limit = plainSelect.getLimit();
            if (limit != null ){
                Expression offset = limit.getOffset();
                if (offset instanceof LongValue){
                    LongValue offsetLong = (LongValue)offset;
                    long offsetValue = offsetLong.getValue();
                    Assert.isFalse(offsetValue > 100000,"非法SQL，【limit】关键字offset数量必须小于等于100000");
                }else if (offset instanceof JdbcParameter){
                    try{
                        List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
                        Map paramMap = (Map)boundSql.getParameterObject();
                        int total = parameterMappings.size();
                        String[] limits = sql.split(" limit ");
                        if (limits.length > 0){
                            int question = (int) Stream.of(limits[1].split("")).filter("?"::equals).count();
                            int index = total - question;
                            ParameterMapping parameterMapping = parameterMappings.get(index);
                            String property = parameterMapping.getProperty();
                            Object value = paramMap.get(property);
                            if (value instanceof Integer){
                                Integer intValue = (Integer)value;
                                Assert.isFalse(intValue > 100000,"非法SQL，【limit】关键字offset数量必须小于等于100000");
                            }else if (value instanceof Long){
                                Long longValue = (Long)value;
                                Assert.isFalse(longValue.intValue() > 100000,"非法SQL，【limit】关键字offset数量必须小于等于100000");
                            }
                        }
                    }catch (MybatisPlusException myEx){
                        //除了sql校验异常，捕获后继续抛出
                        throw myEx;
                    } catch (Exception ex){
                        //暂不抛出其他异常
                        logger.error("含有limit解析异常 SQL："+sql,ex);
                    }
                    //有动态参数判断则不缓存
                    isCache = false;
                }
            }
        }
        return isCache;
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
            throw new MybatisPlusException("非法SQL，SQL未使用到索引, table:" + table + ", columnName:" + columnName);
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
                //                if (joinTable != null && rightExpression instanceof Column) {
                //                    if (Objects.equals(((Column) rightExpression).getTable().getName(), table.getAlias().getName())) {
                //                        validUseIndex(table, ((Column) rightExpression).getColumnName(), connection);
                //                        validUseIndex(joinTable, ((Column) leftExpression).getColumnName(), connection);
                //                    } else {
                //                        validUseIndex(joinTable, ((Column) rightExpression).getColumnName(), connection);
                //                        validUseIndex(table, ((Column) leftExpression).getColumnName(), connection);
                //                    }
                //                } else {
                //                    //获得列名
                //                    validUseIndex(table, ((Column) leftExpression).getColumnName(), connection);
                //                }
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
     * @param selectItem
     */
    private static void validSelectItem(SelectItem selectItem) {
        String selectParam = selectItem.toString();
        //select语句允许使用count(*)，判断
        if (selectParam.toLowerCase().contains("count(*)")) {
            return;
        }
        //select语句禁止使用*
        else if (selectParam.contains("*")) {
            throw new MybatisPlusException("非法SQL，SQL使用到'select *'");
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
        if (StringUtils.isNotBlank(key)) {
            indexInfos = indexInfoMap.get(key);
        }
        if (indexInfos == null || indexInfos.isEmpty()) {
            ResultSet rs;
            try {
                DatabaseMetaData metadata = conn.getMetaData();
                String catalog = StringUtils.isBlank(dbName) ? conn.getCatalog() : dbName;
                String schema = StringUtils.isBlank(dbName) ? conn.getSchema() : dbName;
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
                if (StringUtils.isNotBlank(key)) {
                    indexInfoMap.put(key, indexInfos);
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
        return indexInfos;
    }

    /**
     * 索引对象
     */
    @Data
    private static class IndexInfo {

        private String dbName;

        private String tableName;

        private String columnName;
    }
}
