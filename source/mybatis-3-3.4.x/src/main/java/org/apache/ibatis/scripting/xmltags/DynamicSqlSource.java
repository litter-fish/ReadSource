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
package org.apache.ibatis.scripting.xmltags;

import java.util.Map;

import org.apache.ibatis.builder.SqlSourceBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.session.Configuration;

/**
 * @author Clinton Begin
 */
public class DynamicSqlSource implements SqlSource {

    private final Configuration configuration;
    private final SqlNode rootSqlNode;

    public DynamicSqlSource(Configuration configuration, SqlNode rootSqlNode) {
        this.configuration = configuration;
        this.rootSqlNode = rootSqlNode;
    }

    /**
     * 创建 DynamicContext
     * 解析 SQL 片段，并将解析结果存储到 DynamicContext 中
     * 解析 SQL 语句，并构建 StaticSqlSource
     * 调用 StaticSqlSource 的 getBoundSql 获取 BoundSql
     * 将 DynamicContext 的 ContextMap 中的内容拷贝到 BoundSql 中
     */
    @Override
    public BoundSql getBoundSql(Object parameterObject) {
        // 创建 DynamicContext
        DynamicContext context = new DynamicContext(configuration, parameterObject);
        // 解析 SQL 片段，并将解析结果存储到 DynamicContext 中
        rootSqlNode.apply(context);
        // 创建 SqlSourceBuilder 对象
        SqlSourceBuilder sqlSourceParser = new SqlSourceBuilder(configuration);
        Class<?> parameterType = parameterObject == null ? Object.class : parameterObject.getClass();

        /*
         * 构建 StaticSqlSource，在此过程中将 sql 语句中的占位符 #{} 替换为问号 ?，
         * 并为每个占位符构建相应的 ParameterMapping
         */
        SqlSource sqlSource = sqlSourceParser.parse(context.getSql(), parameterType, context.getBindings());

        // 调用 StaticSqlSource 的 getBoundSql 获取 BoundSql
        BoundSql boundSql = sqlSource.getBoundSql(parameterObject);
        // 将 DynamicContext 的 ContextMap 中的内容拷贝到 BoundSql 中
        for (Map.Entry<String, Object> entry : context.getBindings().entrySet()) {
            boundSql.setAdditionalParameter(entry.getKey(), entry.getValue());
        }
        return boundSql;
    }

}
