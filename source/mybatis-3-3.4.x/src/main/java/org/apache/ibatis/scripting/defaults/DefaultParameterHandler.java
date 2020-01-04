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
package org.apache.ibatis.scripting.defaults;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeException;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;

/**
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public class DefaultParameterHandler implements ParameterHandler {

    private final TypeHandlerRegistry typeHandlerRegistry;

    private final MappedStatement mappedStatement;
    private final Object parameterObject;
    private final BoundSql boundSql;
    private final Configuration configuration;

    public DefaultParameterHandler(MappedStatement mappedStatement, Object parameterObject, BoundSql boundSql) {
        this.mappedStatement = mappedStatement;
        this.configuration = mappedStatement.getConfiguration();
        this.typeHandlerRegistry = mappedStatement.getConfiguration().getTypeHandlerRegistry();
        this.parameterObject = parameterObject;
        this.boundSql = boundSql;
    }

    @Override
    public Object getParameterObject() {
        return parameterObject;
    }

    @Override
    public void setParameters(PreparedStatement ps) {
        /*
         * 从 BoundSql 中获取 ParameterMapping 列表，每个 ParameterMapping
         * 与原始 SQL 中的 #{xxx} 占位符一一对应
         */
        ErrorContext.instance().activity("setting parameters").object(mappedStatement.getParameterMap().getId());
        List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
        if (parameterMappings != null) {
            for (int i = 0; i < parameterMappings.size(); i++) {
                ParameterMapping parameterMapping = parameterMappings.get(i);

                // 检测参数类型，排除掉 mode 为 OUT 类型的 parameterMapping
                if (parameterMapping.getMode() != ParameterMode.OUT) {
                    Object value;
                    // 获取属性名
                    String propertyName = parameterMapping.getProperty();

                    // 检测 BoundSql 的 additionalParameters 是否包含 propertyName
                    if (boundSql.hasAdditionalParameter(propertyName)) { // issue #448 ask first for additional params
                        value = boundSql.getAdditionalParameter(propertyName);
                    } else if (parameterObject == null) {
                        value = null;
                        // 检测运行时参数是否有相应的类型解析器
                    } else if (typeHandlerRegistry.hasTypeHandler(parameterObject.getClass())) {
                        /*
                         * 若运行时参数的类型有相应的类型处理器 TypeHandler，则将
                         * parameterObject 设为当前属性的值。
                         */
                        value = parameterObject;
                    } else {
                        // 为用户传入的参数 parameterObject 创建元信息对象
                        MetaObject metaObject = configuration.newMetaObject(parameterObject);
                        // 从用户传入的参数中获取 propertyName 对应的值
                        value = metaObject.getValue(propertyName);
                    }

                    /////// 以上代码用于获取 #{xxx} 占位符属性所对应的运行时参数

                    TypeHandler typeHandler = parameterMapping.getTypeHandler();
                    JdbcType jdbcType = parameterMapping.getJdbcType();
                    if (value == null && jdbcType == null) {
                        // 此处 jdbcType = JdbcType.OTHER
                        jdbcType = configuration.getJdbcTypeForNull();
                    }

                    /////// 以上取 #{xxx} 占位符属性对应的 TypeHandler
                    try {

                        // jdbc下标从1开始，由具体的类型处理器进行参数的设置, 对于每个jdbcType, mybatis都提供了一个对应的Handler,
                        // 具体可参考上文TypeHandler详解, 其内部调用的是PrepareStatement.setXXX进行设置。
                        // 由类型处理器 typeHandler 向 ParameterHandler 设置参数
                        ////// 通过 TypeHandler 将运行时参数值设置到 PreparedStatement 中
                        typeHandler.setParameter(ps, i + 1, value, jdbcType);
                    } catch (TypeException e) {
                        throw new TypeException("Could not set parameters for mapping: " + parameterMapping + ". Cause: " + e, e);
                    } catch (SQLException e) {
                        throw new TypeException("Could not set parameters for mapping: " + parameterMapping + ". Cause: " + e, e);
                    }
                }
            }
        }
    }

}
