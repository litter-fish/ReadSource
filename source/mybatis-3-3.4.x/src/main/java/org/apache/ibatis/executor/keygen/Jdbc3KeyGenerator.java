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
package org.apache.ibatis.executor.keygen;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.binding.BindingException;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class Jdbc3KeyGenerator implements KeyGenerator {

    /**
     * A shared instance.
     * @since 3.4.3
     */
    public static final Jdbc3KeyGenerator INSTANCE = new Jdbc3KeyGenerator();

    @Override
    public void processBefore(Executor executor, MappedStatement ms, Statement stmt, Object parameter) {
        // do nothing
    }

    @Override
    public void processAfter(Executor executor, MappedStatement ms, Statement stmt, Object parameter) {
        processBatch(ms, stmt, getParameters(parameter));
    }

    public void processBatch(MappedStatement ms, Statement stmt, Collection<Object> parameters) {
        ResultSet rs = null;
        try {
            rs = stmt.getGeneratedKeys();
            final Configuration configuration = ms.getConfiguration();
            final TypeHandlerRegistry typeHandlerRegistry = configuration.getTypeHandlerRegistry();
            // 获取主键字段
            final String[] keyProperties = ms.getKeyProperties();
            // 获取结果集 ResultSet 的元数据
            final ResultSetMetaData rsmd = rs.getMetaData();
            TypeHandler<?>[] typeHandlers = null;
            // ResultSet 中数据的列数要大于等于主键的数量
            if (keyProperties != null && rsmd.getColumnCount() >= keyProperties.length) {
                // 遍历 parameters
                for (Object parameter : parameters) {
                    // there should be one row for each statement (also one for each parameter)
                    // 对于批量插入，ResultSet 会返回多行数据
                    if (!rs.next()) {
                        break;
                    }
                    final MetaObject metaParam = configuration.newMetaObject(parameter);
                    if (typeHandlers == null) {
                        // 为每个主键属性获取 TypeHandler
                        typeHandlers = getTypeHandlers(typeHandlerRegistry, metaParam, keyProperties, rsmd);
                    }
                    // 填充结果到运行时参数中
                    populateKeys(rs, metaParam, keyProperties, typeHandlers);
                }
            }
        } catch (Exception e) {
            throw new ExecutorException("Error getting generated key or setting result to parameter object. Cause: " + e, e);
        } finally {
            if (rs != null) {
                try {
                    rs.close();
                } catch (Exception e) {
                    // ignore
                }
            }
        }
    }

    private Collection<Object> getParameters(Object parameter) {
        Collection<Object> parameters = null;
        if (parameter instanceof Collection) {
            parameters = (Collection) parameter;
        } else if (parameter instanceof Map) {
            /*
             * 如果 parameter 是 Map 类型，则从其中提取指定 key 对应的值。
             * 至于 Map 中为什么会出现 collection/list/array 等键。大家
             * 可以参考 DefaultSqlSession 的 wrapCollection 方法
             */
            Map parameterMap = (Map) parameter;
            if (parameterMap.containsKey("collection")) {
                parameters = (Collection) parameterMap.get("collection");
            } else if (parameterMap.containsKey("list")) {
                parameters = (List) parameterMap.get("list");
            } else if (parameterMap.containsKey("array")) {
                parameters = Arrays.asList((Object[]) parameterMap.get("array"));
            }
        }
        if (parameters == null) {
            parameters = new ArrayList<Object>();
            // 将普通的对象添加到 parameters 中
            parameters.add(parameter);
        }
        return parameters;
    }

    private TypeHandler<?>[] getTypeHandlers(TypeHandlerRegistry typeHandlerRegistry, MetaObject metaParam, String[] keyProperties, ResultSetMetaData rsmd) throws SQLException {
        TypeHandler<?>[] typeHandlers = new TypeHandler<?>[keyProperties.length];
        for (int i = 0; i < keyProperties.length; i++) {
            if (metaParam.hasSetter(keyProperties[i])) {
                TypeHandler<?> th;
                try {
                    Class<?> keyPropertyType = metaParam.getSetterType(keyProperties[i]);
                    th = typeHandlerRegistry.getTypeHandler(keyPropertyType, JdbcType.forCode(rsmd.getColumnType(i + 1)));
                } catch (BindingException e) {
                    th = null;
                }
                typeHandlers[i] = th;
            }
        }
        return typeHandlers;
    }

    private void populateKeys(ResultSet rs, MetaObject metaParam, String[] keyProperties, TypeHandler<?>[] typeHandlers) throws SQLException {
        // 遍历 keyProperties
        for (int i = 0; i < keyProperties.length; i++) {
            // 获取主键属性
            String property = keyProperties[i];
            TypeHandler<?> th = typeHandlers[i];
            if (th != null) {
                // 从 ResultSet 中获取某列的值
                Object value = th.getResult(rs, i + 1);
                // 设置结果值到运行时参数中
                metaParam.setValue(property, value);
            }
        }
    }
}
