/**
 *    Copyright 2009-2017 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.executor.resultset;

import org.apache.ibatis.annotations.AutomapConstructor;
import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.cursor.defaults.DefaultCursor;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.executor.loader.ResultLoader;
import org.apache.ibatis.executor.loader.ResultLoaderMap;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.result.DefaultResultContext;
import org.apache.ibatis.executor.result.DefaultResultHandler;
import org.apache.ibatis.executor.result.ResultMapException;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultContext;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;

import java.lang.reflect.Constructor;
import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * @author Clinton Begin
 * @author Eduardo Macarron
 * @author Iwao AVE!
 * @author Kazuki Shimizu
 */
public class DefaultResultSetHandler implements ResultSetHandler {

    private static final Object DEFERED = new Object();

    private final Executor executor;
    private final Configuration configuration;
    private final MappedStatement mappedStatement;
    private final RowBounds rowBounds;
    private final ParameterHandler parameterHandler;
    private final ResultHandler<?> resultHandler;
    private final BoundSql boundSql;
    private final TypeHandlerRegistry typeHandlerRegistry;
    private final ObjectFactory objectFactory;
    private final ReflectorFactory reflectorFactory;

    // nested resultmaps
    private final Map<CacheKey, Object> nestedResultObjects = new HashMap<CacheKey, Object>();
    private final Map<String, Object> ancestorObjects = new HashMap<String, Object>();
    private Object previousRowValue;

    // multiple resultsets
    private final Map<String, ResultMapping> nextResultMaps = new HashMap<String, ResultMapping>();
    private final Map<CacheKey, List<PendingRelation>> pendingRelations = new HashMap<CacheKey, List<PendingRelation>>();

    // Cached Automappings
    private final Map<String, List<UnMappedColumnAutoMapping>> autoMappingsCache = new HashMap<String, List<UnMappedColumnAutoMapping>>();

    // temporary marking flag that indicate using constructor mapping (use field to reduce memory usage)
    private boolean useConstructorMappings;

    private final PrimitiveTypes primitiveTypes;

    private static class PendingRelation {
        public MetaObject metaObject;
        public ResultMapping propertyMapping;
    }

    private static class UnMappedColumnAutoMapping {
        private final String column;
        private final String property;
        private final TypeHandler<?> typeHandler;
        private final boolean primitive;

        public UnMappedColumnAutoMapping(String column, String property, TypeHandler<?> typeHandler, boolean primitive) {
            this.column = column;
            this.property = property;
            this.typeHandler = typeHandler;
            this.primitive = primitive;
        }
    }

    public DefaultResultSetHandler(Executor executor, MappedStatement mappedStatement, ParameterHandler parameterHandler, ResultHandler<?> resultHandler, BoundSql boundSql,
                                   RowBounds rowBounds) {
        this.executor = executor;
        this.configuration = mappedStatement.getConfiguration();
        this.mappedStatement = mappedStatement;
        this.rowBounds = rowBounds;
        this.parameterHandler = parameterHandler;
        this.boundSql = boundSql;
        this.typeHandlerRegistry = configuration.getTypeHandlerRegistry();
        this.objectFactory = configuration.getObjectFactory();
        this.reflectorFactory = configuration.getReflectorFactory();
        this.resultHandler = resultHandler;
        this.primitiveTypes = new PrimitiveTypes();
    }

    //
    // HANDLE OUTPUT PARAMETER
    //

    @Override
    public void handleOutputParameters(CallableStatement cs) throws SQLException {
        final Object parameterObject = parameterHandler.getParameterObject();
        final MetaObject metaParam = configuration.newMetaObject(parameterObject);
        final List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
        for (int i = 0; i < parameterMappings.size(); i++) {
            final ParameterMapping parameterMapping = parameterMappings.get(i);
            if (parameterMapping.getMode() == ParameterMode.OUT || parameterMapping.getMode() == ParameterMode.INOUT) {
                if (ResultSet.class.equals(parameterMapping.getJavaType())) {
                    handleRefCursorOutputParameter((ResultSet) cs.getObject(i + 1), parameterMapping, metaParam);
                } else {
                    final TypeHandler<?> typeHandler = parameterMapping.getTypeHandler();
                    metaParam.setValue(parameterMapping.getProperty(), typeHandler.getResult(cs, i + 1));
                }
            }
        }
    }

    private void handleRefCursorOutputParameter(ResultSet rs, ParameterMapping parameterMapping, MetaObject metaParam) throws SQLException {
        if (rs == null) {
            return;
        }
        try {
            final String resultMapId = parameterMapping.getResultMapId();
            final ResultMap resultMap = configuration.getResultMap(resultMapId);
            final ResultSetWrapper rsw = new ResultSetWrapper(rs, configuration);
            if (this.resultHandler == null) {
                final DefaultResultHandler resultHandler = new DefaultResultHandler(objectFactory);
                handleRowValues(rsw, resultMap, resultHandler, new RowBounds(), null);
                metaParam.setValue(parameterMapping.getProperty(), resultHandler.getResultList());
            } else {
                handleRowValues(rsw, resultMap, resultHandler, new RowBounds(), null);
            }
        } finally {
            // issue #228 (close resultsets)
            closeResultSet(rs);
        }
    }

    //
    // HANDLE RESULT SETS
    //
    @Override
    public List<Object> handleResultSets(Statement stmt) throws SQLException {
        ErrorContext.instance().activity("handling results").object(mappedStatement.getId());

        final List<Object> multipleResults = new ArrayList<Object>();

        int resultSetCount = 0;
        // 获取第一个结果集
        ResultSetWrapper rsw = getFirstResultSet(stmt);

        List<ResultMap> resultMaps = mappedStatement.getResultMaps();

        // 绝大部分情况下一个查询只有一个ResultMap, 除非多结果集查询
        int resultMapCount = resultMaps.size();
        validateResultMapsCount(rsw, resultMapCount);

        // 至少执行一次
        while (rsw != null && resultMapCount > resultSetCount) {
            ResultMap resultMap = resultMaps.get(resultSetCount);

            // 处理结果集
            handleResultSet(rsw, resultMap, multipleResults, null);

            // 获取下一个结果集
            rsw = getNextResultSet(stmt);

            // 清空嵌套结果集
            cleanUpAfterHandlingResultSet();
            resultSetCount++;
        }

        // 处理关联或集合
        String[] resultSets = mappedStatement.getResultSets();
        if (resultSets != null) {

            // 如果一个映射语句的resultSet数量比jdbc resultset多的话，多的部分就是嵌套结果集
            while (rsw != null && resultSetCount < resultSets.length) {
                // nextResultMaps初始化的时候为空,它是在处理主查询每行记录的时候写进去的，所以此时就可以得到主记录是哪个属性
                ResultMapping parentMapping = nextResultMaps.get(resultSets[resultSetCount]);
                if (parentMapping != null) {
                    String nestedResultMapId = parentMapping.getNestedResultMapId();
                    ResultMap resultMap = configuration.getResultMap(nestedResultMapId);

                    // 得到子结果集的resultMap之后，就可以进行填充了
                    handleResultSet(rsw, resultMap, null, parentMapping);
                }
                rsw = getNextResultSet(stmt);
                cleanUpAfterHandlingResultSet();
                resultSetCount++;
            }
        }

        return collapseSingleResultList(multipleResults);
    }

    @Override
    public <E> Cursor<E> handleCursorResultSets(Statement stmt) throws SQLException {
        ErrorContext.instance().activity("handling cursor results").object(mappedStatement.getId());

        ResultSetWrapper rsw = getFirstResultSet(stmt);

        List<ResultMap> resultMaps = mappedStatement.getResultMaps();

        int resultMapCount = resultMaps.size();
        validateResultMapsCount(rsw, resultMapCount);
        if (resultMapCount != 1) {
            throw new ExecutorException("Cursor results cannot be mapped to multiple resultMaps");
        }

        ResultMap resultMap = resultMaps.get(0);
        return new DefaultCursor<E>(this, resultMap, rsw, rowBounds);
    }

    private ResultSetWrapper getFirstResultSet(Statement stmt) throws SQLException {
        // 获取结果集
        ResultSet rs = stmt.getResultSet();
        while (rs == null) {
            // move forward to get the first resultset in case the driver
            // doesn't return the resultset as the first result (HSQLDB 2.1)
            /*
             * 移动 ResultSet 指针到下一个上，有些数据库驱动可能需要使用者
             * 先调用 getMoreResults 方法，然后才能调用 getResultSet 方法
             * 获取到第一个 ResultSet
             */
            if (stmt.getMoreResults()) {
                rs = stmt.getResultSet();
            } else {
                if (stmt.getUpdateCount() == -1) {
                    // no more results. Must be no resultset
                    break;
                }
            }
        }
        /*
         * 这里并不直接返回 ResultSet，而是将其封装到 ResultSetWrapper 中。
         * ResultSetWrapper 中包含了 ResultSet 一些元信息，比如列名称、每列对应的 JdbcType、
         * 以及每列对应的 Java 类名（class name，譬如 java.lang.String）等。
         */
        return rs != null ? new ResultSetWrapper(rs, configuration) : null;
    }

    private ResultSetWrapper getNextResultSet(Statement stmt) throws SQLException {
        // Making this method tolerant of bad JDBC drivers
        try {
            if (stmt.getConnection().getMetaData().supportsMultipleResultSets()) {
                // Crazy Standard JDBC way of determining if there are more results
                if (!(!stmt.getMoreResults() && stmt.getUpdateCount() == -1)) {
                    ResultSet rs = stmt.getResultSet();
                    if (rs == null) {
                        return getNextResultSet(stmt);
                    } else {
                        return new ResultSetWrapper(rs, configuration);
                    }
                }
            }
        } catch (Exception e) {
            // Intentionally ignored.
        }
        return null;
    }

    private void closeResultSet(ResultSet rs) {
        try {
            if (rs != null) {
                rs.close();
            }
        } catch (SQLException e) {
            // ignore
        }
    }

    private void cleanUpAfterHandlingResultSet() {
        nestedResultObjects.clear();
    }

    private void validateResultMapsCount(ResultSetWrapper rsw, int resultMapCount) {
        if (rsw != null && resultMapCount < 1) {
            throw new ExecutorException("A query was run and no Result Maps were found for the Mapped Statement '" + mappedStatement.getId()
                    + "'.  It's likely that neither a Result Type nor a Result Map was specified.");
        }
    }

    // 因为调用handleResultSet的只有handleResultSets，按说其中第一个调用永远不会出现parentMapping==null的情况，只有第二个调用才会出现这种情况，而且应该是连续的，
    // 因为第二个调用就是为了处理嵌套resultMap。所以在handleRowValues处理resultMap的时候，一定是主的先处理，嵌套的后处理
    private void handleResultSet(ResultSetWrapper rsw, ResultMap resultMap, List<Object> multipleResults, ResultMapping parentMapping) throws SQLException {
        try {
            // 处理嵌套/关联的resultMap(collection或association)
            if (parentMapping != null) {
                // 处理非主记录 resultHandler传递null，RowBounds传递默认值，parentMapping不为空
                handleRowValues(rsw, resultMap, null, RowBounds.DEFAULT, parentMapping);
            } else {
                /*
                 * 检测 resultHandler 是否为空。ResultHandler 是一个接口，使用者可实现该接口，
                 * 这样我们可以通过 ResultHandler 自定义接收查询结果的动作。比如我们可将结果存储到
                 * List、Map 亦或是 Set，甚至丢弃，这完全取决于大家的实现逻辑。
                 */
                if (resultHandler == null) {
                    // 创建默认的结果处理器
                    DefaultResultHandler defaultResultHandler = new DefaultResultHandler(objectFactory);
                    // 处理主记录，resultHander不为空,rowBounds不使用默认值,parentMapping传递null
                    // 处理结果集的行数据
                    handleRowValues(rsw, resultMap, defaultResultHandler, rowBounds, null);

                    //TODO /////////
                    // ?????? 作用
                    multipleResults.add(defaultResultHandler.getResultList());
                } else {
                    // 处理结果集的行数据
                    handleRowValues(rsw, resultMap, resultHandler, rowBounds, null);
                }
            }
        } finally {
            // issue #228 (close resultsets)
            closeResultSet(rsw.getResultSet());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> collapseSingleResultList(List<Object> multipleResults) {
        return multipleResults.size() == 1 ? (List<Object>) multipleResults.get(0) : multipleResults;
    }

    //
    // HANDLE ROWS FOR SIMPLE RESULTMAP
    //

    // 处理每一行记录,不管是主查询记录还是关联嵌套的查询结果集，他们的入参上通过resultHandler,rowBounds,parentMapping区分
    public void handleRowValues(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping) throws SQLException {
        // 具体实现上,处理嵌套记录和主记录的逻辑不一样
        if (resultMap.hasNestedResultMaps()) {
            ensureNoRowBounds();
            checkResultHandler();
            // 处理嵌套映射 含有嵌套resultMap的列处理，通常是collection或者association
            handleRowValuesForNestedResultMap(rsw, resultMap, resultHandler, rowBounds, parentMapping);
        } else {
            // 处理简单映射
            handleRowValuesForSimpleResultMap(rsw, resultMap, resultHandler, rowBounds, parentMapping);
        }
    }

    private void ensureNoRowBounds() {
        if (configuration.isSafeRowBoundsEnabled() && rowBounds != null && (rowBounds.getLimit() < RowBounds.NO_ROW_LIMIT || rowBounds.getOffset() > RowBounds.NO_ROW_OFFSET)) {
            throw new ExecutorException("Mapped Statements with nested result mappings cannot be safely constrained by RowBounds. "
                    + "Use safeRowBoundsEnabled=false setting to bypass this check.");
        }
    }

    protected void checkResultHandler() {
        if (resultHandler != null && configuration.isSafeResultHandlerEnabled() && !mappedStatement.isResultOrdered()) {
            throw new ExecutorException("Mapped Statements with nested result mappings cannot be safely used with a custom ResultHandler. "
                    + "Use safeResultHandlerEnabled=false setting to bypass this check "
                    + "or ensure your statement returns ordered data and set resultOrdered=true on it.");
        }
    }

    // 处理主记录
    private void handleRowValuesForSimpleResultMap(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping)
            throws SQLException {
        DefaultResultContext<Object> resultContext = new DefaultResultContext<Object>();
        // 根据 RowBounds 定位到指定行记录,这是mybatis进行内部分页,不是依赖服务器端的分页实现
        skipRows(rsw.getResultSet(), rowBounds);

        // 检测是否还有更多行的数据需要处理
        while (shouldProcessMoreRows(resultContext, rowBounds) && rsw.getResultSet().next()) {

            // 解析鉴别器，得到嵌套的最深的鉴别器对应的ResultMap，如果没有鉴别器，就返回最顶层的ResultMap
            ResultMap discriminatedResultMap = resolveDiscriminatedResultMap(rsw.getResultSet(), resultMap, null);

            // 这个时候resultMap是非常干净的,没有嵌套任何其他东西了，但是这也是最关键的地方，将ResultSet记录转换为业务层配置的对象类型或者Map类型
            Object rowValue = getRowValue(rsw, discriminatedResultMap);
            // 存储结果
            storeObject(resultHandler, resultContext, rowValue, parentMapping, rsw.getResultSet());
        }
    }

    private void storeObject(ResultHandler<?> resultHandler, DefaultResultContext<Object> resultContext, Object rowValue, ResultMapping parentMapping, ResultSet rs) throws SQLException {
        if (parentMapping != null) {
            // 如果不是主记录,则链接到主记录
            linkToParents(rs, parentMapping, rowValue);
        } else {
            // 存储结果
            callResultHandler(resultHandler, resultContext, rowValue);
        }
    }

    @SuppressWarnings("unchecked" /* because ResultHandler<?> is always ResultHandler<Object>*/)
    private void callResultHandler(ResultHandler<?> resultHandler, DefaultResultContext<Object> resultContext, Object rowValue) {
        // 设置结果到 resultContext 中
        resultContext.nextResultObject(rowValue);
        // 从 resultContext 获取结果，并存储到 resultHandler 中
        ((ResultHandler<Object>) resultHandler).handleResult(resultContext);
    }

    private boolean shouldProcessMoreRows(ResultContext<?> context, RowBounds rowBounds) throws SQLException {
        return !context.isStopped() && context.getResultCount() < rowBounds.getLimit();
    }

    private void skipRows(ResultSet rs, RowBounds rowBounds) throws SQLException {
        // 检测 rs 的类型，不同的类型行数据定位方式是不同的
        if (rs.getType() != ResultSet.TYPE_FORWARD_ONLY) {
            if (rowBounds.getOffset() != RowBounds.NO_ROW_OFFSET) {
                // 直接定位到 rowBounds.getOffset() 位置处
                rs.absolute(rowBounds.getOffset());
            }
        } else {
            for (int i = 0; i < rowBounds.getOffset(); i++) {
                /*
                 * 通过多次调用 rs.next() 方法实现行数据定位。
                 * 当 Offset 数值很大时，这种效率很低下
                 */
                rs.next();
            }
        }
    }

    //
    // GET VALUE FROM ROW FOR SIMPLE RESULT MAP
    //

    private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap) throws SQLException {
        final ResultLoaderMap lazyLoader = new ResultLoaderMap();
        // 创建实体类对象，比如 Article 对象
        Object rowValue = createResultObject(rsw, resultMap, lazyLoader, null);
        if (rowValue != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
            final MetaObject metaObject = configuration.newMetaObject(rowValue);
            boolean foundValues = this.useConstructorMappings;
            // 检测是否应该自动映射结果集
            if (shouldApplyAutomaticMappings(resultMap, false)) {
                // 进行自动映射
                foundValues = applyAutomaticMappings(rsw, resultMap, metaObject, null) || foundValues;
            }

            // 处理属性映射,这里会识别出哪些属性需要nestQuery，哪些是nest ResultMap
            // 根据 <resultMap> 节点中配置的映射关系进行映射
            foundValues = applyPropertyMappings(rsw, resultMap, metaObject, lazyLoader, null) || foundValues;
            foundValues = lazyLoader.size() > 0 || foundValues;
            rowValue = foundValues || configuration.isReturnInstanceForEmptyRow() ? rowValue : null;
        }
        return rowValue;
    }

    private boolean shouldApplyAutomaticMappings(ResultMap resultMap, boolean isNested) {
        // 检测 <resultMap> 是否配置了 autoMapping 属性
        if (resultMap.getAutoMapping() != null) {
            // 返回 autoMapping 属性
            return resultMap.getAutoMapping();
        } else {
            if (isNested) {
                // 对于嵌套 resultMap，仅当全局的映射行为为 FULL 时，才进行自动映射
                return AutoMappingBehavior.FULL == configuration.getAutoMappingBehavior();
            } else {
                // 对于普通的 resultMap，只要全局的映射行为不为 NONE，即可进行自动映射
                return AutoMappingBehavior.NONE != configuration.getAutoMappingBehavior();
            }
        }
    }

    //
    // PROPERTY MAPPINGS
    //

    private boolean applyPropertyMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, ResultLoaderMap lazyLoader, String columnPrefix)
            throws SQLException {
        // 获取已映射的列名
        final List<String> mappedColumnNames = rsw.getMappedColumnNames(resultMap, columnPrefix);
        boolean foundValues = false;
        // 获取 ResultMapping
        final List<ResultMapping> propertyMappings = resultMap.getPropertyResultMappings();
        for (ResultMapping propertyMapping : propertyMappings) {
            // 拼接列名前缀，得到完整列名
            String column = prependPrefix(propertyMapping.getColumn(), columnPrefix);
            if (propertyMapping.getNestedResultMapId() != null) {
                // the user added a column attribute to a nested result map, ignore it
                column = null;
            }
            /*
             * 下面的 if 分支由三个或条件组合而成，三个条件的含义如下：
             *   条件一：检测 column 是否为 {prop1=col1, prop2=col2} 形式，该
             *          种形式的 column 一般用于关联查询
             *   条件二：检测当前列名是否被包含在已映射的列名集合中，若包含则可进行数据集映射操作
             *   条件三：多结果集相关
             */
            if (propertyMapping.isCompositeResult()
                    || (column != null && mappedColumnNames.contains(column.toUpperCase(Locale.ENGLISH)))
                    || propertyMapping.getResultSet() != null) {
                // 从结果集中获取指定列的数据
                Object value = getPropertyMappingValue(rsw.getResultSet(), metaObject, propertyMapping, lazyLoader, columnPrefix);
                // issue #541 make property optional
                final String property = propertyMapping.getProperty();
                if (property == null) {
                    continue;
                    // 若获取到的值为 DEFERED，则延迟加载该值
                } else if (value == DEFERED) {
                    foundValues = true;
                    continue;
                }
                if (value != null) {
                    foundValues = true;
                }
                if (value != null || (configuration.isCallSettersOnNulls() && !metaObject.getSetterType(property).isPrimitive())) {
                    // gcode issue #377, call setter on nulls (value is not 'found')
                    // 将获取到的值设置到实体类对象中
                    metaObject.setValue(property, value);
                }
            }
        }
        return foundValues;
    }

    private Object getPropertyMappingValue(ResultSet rs, MetaObject metaResultObject, ResultMapping propertyMapping, ResultLoaderMap lazyLoader, String columnPrefix)
            throws SQLException {
        if (propertyMapping.getNestedQueryId() != null) {
            // 处理嵌套query类型的属性
            return getNestedQueryMappingValue(rs, metaResultObject, propertyMapping, lazyLoader, columnPrefix);
        } else if (propertyMapping.getResultSet() != null) {
            // 处理resultMap类型的属性,主要是：
            // 1、维护记录cacheKey和父属性/记录对Map的关联关系，便于在处理嵌套ResultMap时很快可以找到所有需要处理嵌套结果集的父属性；
            // 2、维护父属性和对应resultSet的关联关系。这两者都是为了在处理嵌套结果集是方便
            addPendingChildRelation(rs, metaResultObject, propertyMapping);   // TODO is that OK?
            return DEFERED;
        } else {
            final TypeHandler<?> typeHandler = propertyMapping.getTypeHandler();
            // 拼接前缀
            final String column = prependPrefix(propertyMapping.getColumn(), columnPrefix);
            // 从 ResultSet 中获取指定列的值
            return typeHandler.getResult(rs, column);
        }
    }

    private List<UnMappedColumnAutoMapping> createAutomaticMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, String columnPrefix) throws SQLException {
        final String mapKey = resultMap.getId() + ":" + columnPrefix;
        // 从缓存中获取 UnMappedColumnAutoMapping 列表
        List<UnMappedColumnAutoMapping> autoMapping = autoMappingsCache.get(mapKey);
        // 缓存未命中
        if (autoMapping == null) {
            autoMapping = new ArrayList<UnMappedColumnAutoMapping>();
            // 从 ResultSetWrapper 中获取未配置在 <resultMap> 中的列名
            final List<String> unmappedColumnNames = rsw.getUnmappedColumnNames(resultMap, columnPrefix);
            for (String columnName : unmappedColumnNames) {
                String propertyName = columnName;
                if (columnPrefix != null && !columnPrefix.isEmpty()) {
                    // When columnPrefix is specified,
                    // ignore columns without the prefix.
                    if (columnName.toUpperCase(Locale.ENGLISH).startsWith(columnPrefix)) {
                        // 获取不包含列名前缀的属性名
                        propertyName = columnName.substring(columnPrefix.length());
                    } else {
                        continue;
                    }
                }
                // 将下划线形式的列名转成驼峰式，比如 AUTHOR_NAME -> authorName
                final String property = metaObject.findProperty(propertyName, configuration.isMapUnderscoreToCamelCase());
                if (property != null && metaObject.hasSetter(property)) {
                    // 检测当前属性是否存在于 resultMap 中
                    if (resultMap.getMappedProperties().contains(property)) {
                        continue;
                    }
                    // 获取属性对于的类型
                    final Class<?> propertyType = metaObject.getSetterType(property);
                    if (typeHandlerRegistry.hasTypeHandler(propertyType, rsw.getJdbcType(columnName))) {
                        // 获取类型处理器
                        final TypeHandler<?> typeHandler = rsw.getTypeHandler(propertyType, columnName);
                        // 封装上面获取到的信息到 UnMappedColumnAutoMapping 对象中
                        autoMapping.add(new UnMappedColumnAutoMapping(columnName, property, typeHandler, propertyType.isPrimitive()));
                    } else {
                        configuration.getAutoMappingUnknownColumnBehavior()
                                .doAction(mappedStatement, columnName, property, propertyType);
                    }
                } else {
                    /*
                     * 若 property 为空，或实体类中无 property 属性，此时无法完成
                     * 列名与实体类属性建立映射关系。针对这种情况，有三种处理方式，
                     *   1. 什么都不做
                     *   2. 仅打印日志
                     *   3. 抛出异常
                     * 默认情况下，是什么都不做
                     */
                    configuration.getAutoMappingUnknownColumnBehavior()
                            .doAction(mappedStatement, columnName, (property != null) ? property : propertyName, null);
                }
            }
            // 写入缓存
            autoMappingsCache.put(mapKey, autoMapping);
        }
        return autoMapping;
    }

    private boolean applyAutomaticMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, String columnPrefix) throws SQLException {
        // 获取 UnMappedColumnAutoMapping 列表
        // UnMappedColumnAutoMapping 用于记录未配置在 <resultMap> 节点中的映射关系。
        List<UnMappedColumnAutoMapping> autoMapping = createAutomaticMappings(rsw, resultMap, metaObject, columnPrefix);
        boolean foundValues = false;
        if (!autoMapping.isEmpty()) {
            for (UnMappedColumnAutoMapping mapping : autoMapping) {
                // 通过 TypeHandler 从结果集中获取指定列的数据
                final Object value = mapping.typeHandler.getResult(rsw.getResultSet(), mapping.column);
                if (value != null) {
                    foundValues = true;
                }
                if (value != null || (configuration.isCallSettersOnNulls() && !mapping.primitive)) {
                    // gcode issue #377, call setter on nulls (value is not 'found')
                    // 通过元信息对象设置 value 到实体类对象的指定字段上
                    metaObject.setValue(mapping.property, value);
                }
            }
        }
        return foundValues;
    }

    // MULTIPLE RESULT SETS

    private void linkToParents(ResultSet rs, ResultMapping parentMapping, Object rowValue) throws SQLException {
        // 判断当前的主记录是否有待关联的子记录，是通过pendingRelations Map进行维护的， pendingRelations 是在 addPendingChildRelation 中添加主记录的
        CacheKey parentKey = createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(), parentMapping.getForeignColumn());
        List<PendingRelation> parents = pendingRelations.get(parentKey);
        if (parents != null) {
            for (PendingRelation parent : parents) {
                if (parent != null && rowValue != null) {
                    linkObjects(parent.metaObject, parent.propertyMapping, rowValue);
                }
            }
        }
    }

    private void addPendingChildRelation(ResultSet rs, MetaObject metaResultObject, ResultMapping parentMapping) throws SQLException {
        CacheKey cacheKey = createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(), parentMapping.getColumn());
        PendingRelation deferLoad = new PendingRelation();
        deferLoad.metaObject = metaResultObject;
        deferLoad.propertyMapping = parentMapping;
        List<PendingRelation> relations = pendingRelations.get(cacheKey);
        // issue #255
        if (relations == null) {
            relations = new ArrayList<DefaultResultSetHandler.PendingRelation>();
            pendingRelations.put(cacheKey, relations);
        }
        relations.add(deferLoad);
        ResultMapping previous = nextResultMaps.get(parentMapping.getResultSet());
        if (previous == null) {
            nextResultMaps.put(parentMapping.getResultSet(), parentMapping);
        } else {
            if (!previous.equals(parentMapping)) {
                throw new ExecutorException("Two different properties are mapped to the same resultSet");
            }
        }
    }

    private CacheKey createKeyForMultipleResults(ResultSet rs, ResultMapping resultMapping, String names, String columns) throws SQLException {
        CacheKey cacheKey = new CacheKey();
        cacheKey.update(resultMapping);
        if (columns != null && names != null) {
            String[] columnsArray = columns.split(",");
            String[] namesArray = names.split(",");
            for (int i = 0; i < columnsArray.length; i++) {
                Object value = rs.getString(columnsArray[i]);
                if (value != null) {
                    cacheKey.update(namesArray[i]);
                    cacheKey.update(value);
                }
            }
        }
        return cacheKey;
    }

    //
    // INSTANTIATION & CONSTRUCTOR MAPPING
    //

    private Object createResultObject(ResultSetWrapper rsw, ResultMap resultMap, ResultLoaderMap lazyLoader, String columnPrefix) throws SQLException {
        this.useConstructorMappings = false; // reset previous mapping result
        final List<Class<?>> constructorArgTypes = new ArrayList<Class<?>>();
        final List<Object> constructorArgs = new ArrayList<Object>();

        // 调用重载方法创建实体类对象
        Object resultObject = createResultObject(rsw, resultMap, constructorArgTypes, constructorArgs, columnPrefix);
        // 检测实体类是否有相应的类型处理器
        if (resultObject != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
            final List<ResultMapping> propertyMappings = resultMap.getPropertyResultMappings();
            for (ResultMapping propertyMapping : propertyMappings) {
                // 如果开启了延迟加载，则为 resultObject 生成代理类
                // issue gcode #109 && issue #149
                if (propertyMapping.getNestedQueryId() != null && propertyMapping.isLazy()) {
                    /*
                     * 创建代理类，默认使用 Javassist 框架生成代理类。由于实体类通常不会实现接口，
                     * 所以不能使用 JDK 动态代理 API 为实体类生成代理。
                     */
                    resultObject = configuration.getProxyFactory().createProxy(resultObject, lazyLoader, configuration, objectFactory, constructorArgTypes, constructorArgs);
                    break;
                }
            }
        }
        this.useConstructorMappings = resultObject != null && !constructorArgTypes.isEmpty(); // set current mapping result
        return resultObject;
    }

    private Object createResultObject(ResultSetWrapper rsw, ResultMap resultMap, List<Class<?>> constructorArgTypes, List<Object> constructorArgs, String columnPrefix)
            throws SQLException {
        final Class<?> resultType = resultMap.getType();
        final MetaClass metaType = MetaClass.forClass(resultType, reflectorFactory);
        // 获取 <constructor> 节点对应的 ResultMapping
        final List<ResultMapping> constructorMappings = resultMap.getConstructorResultMappings();
        // 是否有目标对象类型的处理器，有的话，直接调用类型处理器(这里为什么一定是原生类型)创建目标对象
        /*
         * 检测是否有与返回值类型相对应的 TypeHandler，若有则直接
         * 通过 TypeHandler 从结果集中提取数据，并生成返回值对象
         */
        if (hasTypeHandlerForResultObject(rsw, resultType)) {
            // 通过 TypeHandler 获取提取，并生成返回值对象
            return createPrimitiveResultObject(rsw, resultMap, columnPrefix);
            // 是否有构造器，有的话，使用指定的构造器创建目标对象（构造器里面如果嵌套了查询或者ResultMap，则进行处理）
        } else if (!constructorMappings.isEmpty()) {
            /*
             * 通过 <constructor> 节点配置的映射信息从 ResultSet 中提取数据，
             * 然后将这些数据传给指定构造方法，即可创建实体类对象
             */
            return createParameterizedResultObject(rsw, resultType, constructorMappings, constructorArgTypes, constructorArgs, columnPrefix);
            // 如果结果类型是接口或者具有默认构造器，则使用ObjectFactory创建默认目标对象
        } else if (resultType.isInterface() || metaType.hasDefaultConstructor()) {
            // 通过 ObjectFactory 调用目标类的默认构造方法创建实例
            return objectFactory.create(resultType);
            // 是否可以应用自动映射，默认是对非嵌套查询，只要没有明确设置AutoMappingBehavior.NONE就可以，对于嵌套查询，AutoMappingBehavior.FULL就可以。
            // 自动映射的逻辑是先找目标对象上具有@AutomapConstructor注解的构造器，然后根据ResultSet返回的字段清单找匹配的构造器，如果找不到，就报错
        } else if (shouldApplyAutomaticMappings(resultMap, false)) {
            // 通过自动映射查找合适的构造方法创建实例
            return createByConstructorSignature(rsw, resultType, constructorArgTypes, constructorArgs, columnPrefix);
        }
        throw new ExecutorException("Do not know how to create an instance of " + resultType);
    }

    Object createParameterizedResultObject(ResultSetWrapper rsw, Class<?> resultType, List<ResultMapping> constructorMappings,
                                           List<Class<?>> constructorArgTypes, List<Object> constructorArgs, String columnPrefix) {
        boolean foundValues = false;
        // 遍历构造函数中的子节点
        for (ResultMapping constructorMapping : constructorMappings) {
            final Class<?> parameterType = constructorMapping.getJavaType();
            final String column = constructorMapping.getColumn();
            final Object value;
            try {
                // 如果是一个嵌套查询，则去查询结果
                if (constructorMapping.getNestedQueryId() != null) {
                    value = getNestedQueryConstructorValue(rsw.getResultSet(), constructorMapping, columnPrefix);
                } else if (constructorMapping.getNestedResultMapId() != null) { // 存在嵌套结果
                    final ResultMap resultMap = configuration.getResultMap(constructorMapping.getNestedResultMapId());
                    value = getRowValue(rsw, resultMap);
                } else {
                    final TypeHandler<?> typeHandler = constructorMapping.getTypeHandler();
                    value = typeHandler.getResult(rsw.getResultSet(), prependPrefix(column, columnPrefix));
                }
            } catch (ResultMapException e) {
                throw new ExecutorException("Could not process result for mapping: " + constructorMapping, e);
            } catch (SQLException e) {
                throw new ExecutorException("Could not process result for mapping: " + constructorMapping, e);
            }
            constructorArgTypes.add(parameterType);
            constructorArgs.add(value);
            foundValues = value != null || foundValues;
        }
        // 最终调用objectFactory的create方法创建实例
        return foundValues ? objectFactory.create(resultType, constructorArgTypes, constructorArgs) : null;
    }

    private Object createByConstructorSignature(ResultSetWrapper rsw, Class<?> resultType, List<Class<?>> constructorArgTypes, List<Object> constructorArgs,
                                                String columnPrefix) throws SQLException {
        final Constructor<?>[] constructors = resultType.getDeclaredConstructors();
        // 返回存在 AutomapConstructor 注解的构造函数
        final Constructor<?> annotatedConstructor = findAnnotatedConstructor(constructors);
        // 存在注解 AutomapConstructor 则使用该构造函数创建对象
        if (annotatedConstructor != null) {
            return createUsingConstructor(rsw, resultType, constructorArgTypes, constructorArgs, columnPrefix, annotatedConstructor);
        } else {
            // 否则根据ResultSet返回的字段清单，遍历所有构造函数，找到匹配的。
            for (Constructor<?> constructor : constructors) {
                if (allowedConstructor(constructor, rsw.getClassNames())) {
                    return createUsingConstructor(rsw, resultType, constructorArgTypes, constructorArgs, columnPrefix, constructor);
                }
            }
        }
        throw new ExecutorException("No constructor found in " + resultType.getName() + " matching " + rsw.getClassNames());
    }

    private Object createUsingConstructor(ResultSetWrapper rsw, Class<?> resultType, List<Class<?>> constructorArgTypes, List<Object> constructorArgs, String columnPrefix, Constructor<?> constructor) throws SQLException {
        boolean foundValues = false;
        for (int i = 0; i < constructor.getParameterTypes().length; i++) {
            Class<?> parameterType = constructor.getParameterTypes()[i];
            String columnName = rsw.getColumnNames().get(i);
            TypeHandler<?> typeHandler = rsw.getTypeHandler(parameterType, columnName);
            Object value = typeHandler.getResult(rsw.getResultSet(), prependPrefix(columnName, columnPrefix));
            constructorArgTypes.add(parameterType);
            constructorArgs.add(value);
            foundValues = value != null || foundValues;
        }
        return foundValues ? objectFactory.create(resultType, constructorArgTypes, constructorArgs) : null;
    }

    private Constructor<?> findAnnotatedConstructor(final Constructor<?>[] constructors) {
        for (final Constructor<?> constructor : constructors) {
            if (constructor.isAnnotationPresent(AutomapConstructor.class)) {
                return constructor;
            }
        }
        return null;
    }

    private boolean allowedConstructor(final Constructor<?> constructor, final List<String> classNames) {
        final Class<?>[] parameterTypes = constructor.getParameterTypes();
        if (typeNames(parameterTypes).equals(classNames)) return true;
        if (parameterTypes.length != classNames.size()) return false;
        for (int i = 0; i < parameterTypes.length; i++) {
            final Class<?> parameterType = parameterTypes[i];
            if (parameterType.isPrimitive() && !primitiveTypes.getWrapper(parameterType).getName().equals(classNames.get(i))) {
                return false;
            } else if (!parameterType.isPrimitive() && !parameterType.getName().equals(classNames.get(i))) {
                return false;
            }
        }
        return true;
    }

    private List<String> typeNames(Class<?>[] parameterTypes) {
        List<String> names = new ArrayList<String>();
        for (Class<?> type : parameterTypes) {
            names.add(type.getName());
        }
        return names;
    }

    private Object createPrimitiveResultObject(ResultSetWrapper rsw, ResultMap resultMap, String columnPrefix) throws SQLException {
        final Class<?> resultType = resultMap.getType();
        final String columnName;
        if (!resultMap.getResultMappings().isEmpty()) {
            final List<ResultMapping> resultMappingList = resultMap.getResultMappings();
            final ResultMapping mapping = resultMappingList.get(0);
            columnName = prependPrefix(mapping.getColumn(), columnPrefix);
        } else {
            columnName = rsw.getColumnNames().get(0);
        }
        final TypeHandler<?> typeHandler = rsw.getTypeHandler(resultType, columnName);
        return typeHandler.getResult(rsw.getResultSet(), columnName);
    }

    //
    // NESTED QUERY
    //

    private Object getNestedQueryConstructorValue(ResultSet rs, ResultMapping constructorMapping, String columnPrefix) throws SQLException {
        final String nestedQueryId = constructorMapping.getNestedQueryId();
        final MappedStatement nestedQuery = configuration.getMappedStatement(nestedQueryId);
        final Class<?> nestedQueryParameterType = nestedQuery.getParameterMap().getType();
        final Object nestedQueryParameterObject = prepareParameterForNestedQuery(rs, constructorMapping, nestedQueryParameterType, columnPrefix);
        Object value = null;
        if (nestedQueryParameterObject != null) {
            final BoundSql nestedBoundSql = nestedQuery.getBoundSql(nestedQueryParameterObject);
            final CacheKey key = executor.createCacheKey(nestedQuery, nestedQueryParameterObject, RowBounds.DEFAULT, nestedBoundSql);
            final Class<?> targetType = constructorMapping.getJavaType();
            final ResultLoader resultLoader = new ResultLoader(configuration, executor, nestedQuery, nestedQueryParameterObject, targetType, key, nestedBoundSql);
            value = resultLoader.loadResult();
        }
        return value;
    }

    /**
     * 关联查询与延迟加载
     * @param rs
     * @param metaResultObject
     * @param propertyMapping
     * @param lazyLoader
     * @param columnPrefix
     * @return
     * @throws SQLException
     */
    private Object getNestedQueryMappingValue(ResultSet rs, MetaObject metaResultObject, ResultMapping propertyMapping, ResultLoaderMap lazyLoader, String columnPrefix)
            throws SQLException {
        // 获取关联查询 id，id = 命名空间 + <association> 的 select 属性值
        final String nestedQueryId = propertyMapping.getNestedQueryId();
        final String property = propertyMapping.getProperty();
        // 根据 nestedQueryId 获取 MappedStatement
        final MappedStatement nestedQuery = configuration.getMappedStatement(nestedQueryId);
        final Class<?> nestedQueryParameterType = nestedQuery.getParameterMap().getType();
        /*
         * 生成关联查询语句参数对象，参数类型可能是一些包装类，Map 或是自定义的实体类，
         * 具体类型取决于配置信息。以上面的例子为基础，下面分析不同配置对参数类型的影响：
         *   1. <association column="author_id">
         *      column 属性值仅包含列信息，参数类型为 author_id 列对应的类型，这里为 Integer
         *
         *   2. <association column="{id=author_id, name=title}">
         *      column 属性值包含了属性名与列名的复合信息，MyBatis 会根据列名从 ResultSet 中
         *      获取列数据，并将列数据设置到实体类对象的指定属性中，比如：
         *          Author{id=1, name="MyBatis 源码分析系列文章导读", age=null, ....}
         *      或是以键值对 <属性, 列数据> 的形式，将两者存入 Map 中。比如：
         *          {"id": 1, "name": "MyBatis 源码分析系列文章导读"}
         *
         *      至于参数类型到底为实体类还是 Map，取决于关联查询语句的配置信息。比如：
         *          <select id="findAuthor">  ->  参数类型为 Map
         *          <select id="findAuthor" parameterType="Author"> -> 参数类型为实体类
         */
        final Object nestedQueryParameterObject = prepareParameterForNestedQuery(rs, propertyMapping, nestedQueryParameterType, columnPrefix);
        Object value = null;
        if (nestedQueryParameterObject != null) {
            // 获取 BoundSql
            final BoundSql nestedBoundSql = nestedQuery.getBoundSql(nestedQueryParameterObject);
            final CacheKey key = executor.createCacheKey(nestedQuery, nestedQueryParameterObject, RowBounds.DEFAULT, nestedBoundSql);
            final Class<?> targetType = propertyMapping.getJavaType();

            // 检查一级缓存是否保存了关联查询结果
            if (executor.isCached(nestedQuery, key)) {
                /*
                 * 从一级缓存中获取关联查询的结果，并通过 metaResultObject
                 * 将结果设置到相应的实体类对象中
                 */
                executor.deferLoad(nestedQuery, metaResultObject, property, key, targetType);
                value = DEFERED;
            } else {
                // 创建结果加载器
                final ResultLoader resultLoader = new ResultLoader(configuration, executor, nestedQuery, nestedQueryParameterObject, targetType, key, nestedBoundSql);
                // 检测当前属性是否需要延迟加载
                if (propertyMapping.isLazy()) {
                    // 添加延迟加载相关的对象到 loaderMap 集合中
                    lazyLoader.addLoader(property, metaResultObject, resultLoader);
                    value = DEFERED;
                } else {
                    // 直接执行关联查询
                    value = resultLoader.loadResult();
                }
            }
        }
        return value;
    }

    private Object prepareParameterForNestedQuery(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix) throws SQLException {
        if (resultMapping.isCompositeResult()) {
            return prepareCompositeKeyParameter(rs, resultMapping, parameterType, columnPrefix);
        } else {
            return prepareSimpleKeyParameter(rs, resultMapping, parameterType, columnPrefix);
        }
    }

    private Object prepareSimpleKeyParameter(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix) throws SQLException {
        final TypeHandler<?> typeHandler;
        if (typeHandlerRegistry.hasTypeHandler(parameterType)) {
            typeHandler = typeHandlerRegistry.getTypeHandler(parameterType);
        } else {
            typeHandler = typeHandlerRegistry.getUnknownTypeHandler();
        }
        return typeHandler.getResult(rs, prependPrefix(resultMapping.getColumn(), columnPrefix));
    }

    private Object prepareCompositeKeyParameter(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix) throws SQLException {
        final Object parameterObject = instantiateParameterObject(parameterType);
        final MetaObject metaObject = configuration.newMetaObject(parameterObject);
        boolean foundValues = false;
        for (ResultMapping innerResultMapping : resultMapping.getComposites()) {
            final Class<?> propType = metaObject.getSetterType(innerResultMapping.getProperty());
            final TypeHandler<?> typeHandler = typeHandlerRegistry.getTypeHandler(propType);
            final Object propValue = typeHandler.getResult(rs, prependPrefix(innerResultMapping.getColumn(), columnPrefix));
            // issue #353 & #560 do not execute nested query if key is null
            if (propValue != null) {
                metaObject.setValue(innerResultMapping.getProperty(), propValue);
                foundValues = true;
            }
        }
        return foundValues ? parameterObject : null;
    }

    private Object instantiateParameterObject(Class<?> parameterType) {
        if (parameterType == null) {
            return new HashMap<Object, Object>();
        } else if (ParamMap.class.equals(parameterType)) {
            return new HashMap<Object, Object>(); // issue #649
        } else {
            return objectFactory.create(parameterType);
        }
    }

    //
    // DISCRIMINATOR
    //

    public ResultMap resolveDiscriminatedResultMap(ResultSet rs, ResultMap resultMap, String columnPrefix) throws SQLException {
        Set<String> pastDiscriminators = new HashSet<String>();
        Discriminator discriminator = resultMap.getDiscriminator();
        while (discriminator != null) {
            final Object value = getDiscriminatorValue(rs, discriminator, columnPrefix);
            final String discriminatedMapId = discriminator.getMapIdFor(String.valueOf(value));
            if (configuration.hasResultMap(discriminatedMapId)) {
                resultMap = configuration.getResultMap(discriminatedMapId);
                Discriminator lastDiscriminator = discriminator;
                discriminator = resultMap.getDiscriminator();
                if (discriminator == lastDiscriminator || !pastDiscriminators.add(discriminatedMapId)) {
                    break;
                }
            } else {
                break;
            }
        }
        return resultMap;
    }

    private Object getDiscriminatorValue(ResultSet rs, Discriminator discriminator, String columnPrefix) throws SQLException {
        final ResultMapping resultMapping = discriminator.getResultMapping();
        final TypeHandler<?> typeHandler = resultMapping.getTypeHandler();
        return typeHandler.getResult(rs, prependPrefix(resultMapping.getColumn(), columnPrefix));
    }

    private String prependPrefix(String columnName, String prefix) {
        if (columnName == null || columnName.length() == 0 || prefix == null || prefix.length() == 0) {
            return columnName;
        }
        return prefix + columnName;
    }

    //
    // HANDLE NESTED RESULT MAPS
    //

    private void handleRowValuesForNestedResultMap(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping) throws SQLException {
        final DefaultResultContext<Object> resultContext = new DefaultResultContext<Object>();

        // mybatis 分页处理
        skipRows(rsw.getResultSet(), rowBounds);

        // 前一次处理的记录,只有在映射语句的结果集无序的情况下有意义
        Object rowValue = previousRowValue;

        // 一直处理直到超出分页边界或者结果集处理完
        while (shouldProcessMoreRows(resultContext, rowBounds) && rsw.getResultSet().next()) {

            //同主记录,先解析到鉴别器的最底层的ResultMap
            final ResultMap discriminatedResultMap = resolveDiscriminatedResultMap(rsw.getResultSet(), resultMap, null);

            // 创建当前处理记录的rowKey，规则见下文
            final CacheKey rowKey = createRowKey(discriminatedResultMap, rsw, null);

            // 根据rowKey获取嵌套结果对象map中对应的值，因为在处理主记录时存储进去了，具体见上面addPending的流程图，所以partialObject一般不会为空
            Object partialObject = nestedResultObjects.get(rowKey);
            // issue #577 && #542

            // 根据映射语句的结果集是否有序走不同的逻辑
            if (mappedStatement.isResultOrdered()) {
                if (partialObject == null && rowValue != null) {
                    // 对于有序结果集的映射语句，如果嵌套结果对象map中不包含本记录，则清空嵌套结果对象，因为此时嵌套结果对象之前的记录已经没有意义了
                    nestedResultObjects.clear();

                    // 添加记录到resultHandler的list属性中 或 如果是非主记录,添加到主记录对应属性的list或者object中
                    storeObject(resultHandler, resultContext, rowValue, parentMapping, rsw.getResultSet());
                }
                rowValue = getRowValue(rsw, discriminatedResultMap, rowKey, null, partialObject);
            } else {
                rowValue = getRowValue(rsw, discriminatedResultMap, rowKey, null, partialObject);
                if (partialObject == null) {
                    storeObject(resultHandler, resultContext, rowValue, parentMapping, rsw.getResultSet());
                }
            }
        }
        if (rowValue != null && mappedStatement.isResultOrdered() && shouldProcessMoreRows(resultContext, rowBounds)) {
            storeObject(resultHandler, resultContext, rowValue, parentMapping, rsw.getResultSet());
            previousRowValue = null;
        } else if (rowValue != null) {
            previousRowValue = rowValue;
        }
    }

    //
    // GET VALUE FROM ROW FOR NESTED RESULT MAP
    //

    private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap, CacheKey combinedKey, String columnPrefix, Object partialObject) throws SQLException {
        final String resultMapId = resultMap.getId();
        Object rowValue = partialObject;
        if (rowValue != null) {
            final MetaObject metaObject = configuration.newMetaObject(rowValue);
            putAncestor(rowValue, resultMapId);
            applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey, false);
            ancestorObjects.remove(resultMapId);
        } else {
            final ResultLoaderMap lazyLoader = new ResultLoaderMap();
            // 创建实体类对象，比如 Article 对象
            rowValue = createResultObject(rsw, resultMap, lazyLoader, columnPrefix);
            if (rowValue != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
                final MetaObject metaObject = configuration.newMetaObject(rowValue);
                boolean foundValues = this.useConstructorMappings;
                // 检测是否应该自动映射结果集
                if (shouldApplyAutomaticMappings(resultMap, true)) {
                    // 进行自动映射
                    foundValues = applyAutomaticMappings(rsw, resultMap, metaObject, columnPrefix) || foundValues;
                }
                // 处理属性映射,这里会识别出哪些属性需要nestQuery，哪些是nest ResultMap
                // 根据 <resultMap> 节点中配置的映射关系进行映射
                foundValues = applyPropertyMappings(rsw, resultMap, metaObject, lazyLoader, columnPrefix) || foundValues;
                putAncestor(rowValue, resultMapId);
                // 处理嵌套映射 含有嵌套resultMap的列处理，通常是collection或者association
                foundValues = applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey, true) || foundValues;
                ancestorObjects.remove(resultMapId);
                foundValues = lazyLoader.size() > 0 || foundValues;
                rowValue = foundValues || configuration.isReturnInstanceForEmptyRow() ? rowValue : null;
            }
            if (combinedKey != CacheKey.NULL_CACHE_KEY) {
                nestedResultObjects.put(combinedKey, rowValue);
            }
        }
        return rowValue;
    }

    private void putAncestor(Object resultObject, String resultMapId) {
        ancestorObjects.put(resultMapId, resultObject);
    }

    //
    // NESTED RESULT MAP (JOIN MAPPING)
    //

    private boolean applyNestedResultMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, String parentPrefix, CacheKey parentRowKey, boolean newObject) {
        boolean foundValues = false;
        // 遍历resultMap的子节点
        for (ResultMapping resultMapping : resultMap.getPropertyResultMappings()) {
            final String nestedResultMapId = resultMapping.getNestedResultMapId();
            // 如果存在 ResultMap 属性 且不存在 ResultSet 则进行嵌套处理
            if (nestedResultMapId != null && resultMapping.getResultSet() == null) {
                try {
                    final String columnPrefix = getColumnPrefix(parentPrefix, resultMapping);
                    // 获取嵌套 ResultMap 对象。
                    final ResultMap nestedResultMap = getNestedResultMap(rsw.getResultSet(), nestedResultMapId, columnPrefix);
                    if (resultMapping.getColumnPrefix() == null) {
                        // try to fill circular reference only when columnPrefix
                        // is not specified for the nested result map (issue #215)
                        Object ancestorObject = ancestorObjects.get(nestedResultMapId);
                        if (ancestorObject != null) {
                            if (newObject) {
                                linkObjects(metaObject, resultMapping, ancestorObject); // issue #385
                            }
                            continue;
                        }
                    }
                    final CacheKey rowKey = createRowKey(nestedResultMap, rsw, columnPrefix);
                    final CacheKey combinedKey = combineKeys(rowKey, parentRowKey);
                    Object rowValue = nestedResultObjects.get(combinedKey);
                    boolean knownValue = rowValue != null;
                    instantiateCollectionPropertyIfAppropriate(resultMapping, metaObject); // mandatory
                    if (anyNotNullColumnHasValue(resultMapping, columnPrefix, rsw)) {
                        // 填充子节点行数据， nestedResultMap为嵌套 ResultMap
                        rowValue = getRowValue(rsw, nestedResultMap, combinedKey, columnPrefix, rowValue);
                        if (rowValue != null && !knownValue) {
                            // 赋值操作
                            linkObjects(metaObject, resultMapping, rowValue);
                            foundValues = true;
                        }
                    }
                } catch (SQLException e) {
                    throw new ExecutorException("Error getting nested result map values for '" + resultMapping.getProperty() + "'.  Cause: " + e, e);
                }
            }
        }
        return foundValues;
    }

    private String getColumnPrefix(String parentPrefix, ResultMapping resultMapping) {
        final StringBuilder columnPrefixBuilder = new StringBuilder();
        if (parentPrefix != null) {
            columnPrefixBuilder.append(parentPrefix);
        }
        if (resultMapping.getColumnPrefix() != null) {
            columnPrefixBuilder.append(resultMapping.getColumnPrefix());
        }
        return columnPrefixBuilder.length() == 0 ? null : columnPrefixBuilder.toString().toUpperCase(Locale.ENGLISH);
    }

    private boolean anyNotNullColumnHasValue(ResultMapping resultMapping, String columnPrefix, ResultSetWrapper rsw) throws SQLException {
        Set<String> notNullColumns = resultMapping.getNotNullColumns();
        if (notNullColumns != null && !notNullColumns.isEmpty()) {
            ResultSet rs = rsw.getResultSet();
            for (String column : notNullColumns) {
                rs.getObject(prependPrefix(column, columnPrefix));
                if (!rs.wasNull()) {
                    return true;
                }
            }
            return false;
        } else if (columnPrefix != null) {
            for (String columnName : rsw.getColumnNames()) {
                if (columnName.toUpperCase().startsWith(columnPrefix.toUpperCase())) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private ResultMap getNestedResultMap(ResultSet rs, String nestedResultMapId, String columnPrefix) throws SQLException {
        ResultMap nestedResultMap = configuration.getResultMap(nestedResultMapId);
        return resolveDiscriminatedResultMap(rs, nestedResultMap, columnPrefix);
    }

    //
    // UNIQUE RESULT KEY
    //

    private CacheKey createRowKey(ResultMap resultMap, ResultSetWrapper rsw, String columnPrefix) throws SQLException {
        final CacheKey cacheKey = new CacheKey();
        cacheKey.update(resultMap.getId());
        List<ResultMapping> resultMappings = getResultMappingsForRowKey(resultMap);
        if (resultMappings.isEmpty()) {
            if (Map.class.isAssignableFrom(resultMap.getType())) {
                createRowKeyForMap(rsw, cacheKey);
            } else {
                createRowKeyForUnmappedProperties(resultMap, rsw, cacheKey, columnPrefix);
            }
        } else {
            createRowKeyForMappedProperties(resultMap, rsw, cacheKey, resultMappings, columnPrefix);
        }
        if (cacheKey.getUpdateCount() < 2) {
            return CacheKey.NULL_CACHE_KEY;
        }
        return cacheKey;
    }

    private CacheKey combineKeys(CacheKey rowKey, CacheKey parentRowKey) {
        if (rowKey.getUpdateCount() > 1 && parentRowKey.getUpdateCount() > 1) {
            CacheKey combinedKey;
            try {
                combinedKey = rowKey.clone();
            } catch (CloneNotSupportedException e) {
                throw new ExecutorException("Error cloning cache key.  Cause: " + e, e);
            }
            combinedKey.update(parentRowKey);
            return combinedKey;
        }
        return CacheKey.NULL_CACHE_KEY;
    }

    private List<ResultMapping> getResultMappingsForRowKey(ResultMap resultMap) {
        List<ResultMapping> resultMappings = resultMap.getIdResultMappings();
        if (resultMappings.isEmpty()) {
            resultMappings = resultMap.getPropertyResultMappings();
        }
        return resultMappings;
    }

    private void createRowKeyForMappedProperties(ResultMap resultMap, ResultSetWrapper rsw, CacheKey cacheKey, List<ResultMapping> resultMappings, String columnPrefix) throws SQLException {
        for (ResultMapping resultMapping : resultMappings) {
            if (resultMapping.getNestedResultMapId() != null && resultMapping.getResultSet() == null) {
                // Issue #392
                final ResultMap nestedResultMap = configuration.getResultMap(resultMapping.getNestedResultMapId());
                createRowKeyForMappedProperties(nestedResultMap, rsw, cacheKey, nestedResultMap.getConstructorResultMappings(),
                        prependPrefix(resultMapping.getColumnPrefix(), columnPrefix));
            } else if (resultMapping.getNestedQueryId() == null) {
                final String column = prependPrefix(resultMapping.getColumn(), columnPrefix);
                final TypeHandler<?> th = resultMapping.getTypeHandler();
                List<String> mappedColumnNames = rsw.getMappedColumnNames(resultMap, columnPrefix);
                // Issue #114
                if (column != null && mappedColumnNames.contains(column.toUpperCase(Locale.ENGLISH))) {
                    final Object value = th.getResult(rsw.getResultSet(), column);
                    if (value != null || configuration.isReturnInstanceForEmptyRow()) {
                        cacheKey.update(column);
                        cacheKey.update(value);
                    }
                }
            }
        }
    }

    private void createRowKeyForUnmappedProperties(ResultMap resultMap, ResultSetWrapper rsw, CacheKey cacheKey, String columnPrefix) throws SQLException {
        final MetaClass metaType = MetaClass.forClass(resultMap.getType(), reflectorFactory);
        List<String> unmappedColumnNames = rsw.getUnmappedColumnNames(resultMap, columnPrefix);
        for (String column : unmappedColumnNames) {
            String property = column;
            if (columnPrefix != null && !columnPrefix.isEmpty()) {
                // When columnPrefix is specified, ignore columns without the prefix.
                if (column.toUpperCase(Locale.ENGLISH).startsWith(columnPrefix)) {
                    property = column.substring(columnPrefix.length());
                } else {
                    continue;
                }
            }
            if (metaType.findProperty(property, configuration.isMapUnderscoreToCamelCase()) != null) {
                String value = rsw.getResultSet().getString(column);
                if (value != null) {
                    cacheKey.update(column);
                    cacheKey.update(value);
                }
            }
        }
    }

    private void createRowKeyForMap(ResultSetWrapper rsw, CacheKey cacheKey) throws SQLException {
        List<String> columnNames = rsw.getColumnNames();
        for (String columnName : columnNames) {
            final String value = rsw.getResultSet().getString(columnName);
            if (value != null) {
                cacheKey.update(columnName);
                cacheKey.update(value);
            }
        }
    }

    // 集合与非集合的处理逻辑对外封装在一起,这样便于用户使用, 内部通过判断 resultMapping 中的类型确定是否为集合类型。
    // 无论是否为集合类型，最后都添加到parent的metaObject所封装的原始Object对应的属性上
    private void linkObjects(MetaObject metaObject, ResultMapping resultMapping, Object rowValue) {
        final Object collectionProperty = instantiateCollectionPropertyIfAppropriate(resultMapping, metaObject);
        if (collectionProperty != null) {
            final MetaObject targetMetaObject = configuration.newMetaObject(collectionProperty);
            targetMetaObject.add(rowValue);
        } else {
            metaObject.setValue(resultMapping.getProperty(), rowValue);
        }
    }

    private Object instantiateCollectionPropertyIfAppropriate(ResultMapping resultMapping, MetaObject metaObject) {
        final String propertyName = resultMapping.getProperty();
        Object propertyValue = metaObject.getValue(propertyName);
        if (propertyValue == null) {
            Class<?> type = resultMapping.getJavaType();
            if (type == null) {
                type = metaObject.getSetterType(propertyName);
            }
            try {
                if (objectFactory.isCollection(type)) {
                    propertyValue = objectFactory.create(type);
                    metaObject.setValue(propertyName, propertyValue);
                    return propertyValue;
                }
            } catch (Exception e) {
                throw new ExecutorException("Error instantiating collection property for result '" + resultMapping.getProperty() + "'.  Cause: " + e, e);
            }
        } else if (objectFactory.isCollection(propertyValue.getClass())) {
            return propertyValue;
        }
        return null;
    }

    private boolean hasTypeHandlerForResultObject(ResultSetWrapper rsw, Class<?> resultType) {
        if (rsw.getColumnNames().size() == 1) {
            return typeHandlerRegistry.hasTypeHandler(resultType, rsw.getJdbcType(rsw.getColumnNames().get(0)));
        }
        return typeHandlerRegistry.hasTypeHandler(resultType);
    }

}
