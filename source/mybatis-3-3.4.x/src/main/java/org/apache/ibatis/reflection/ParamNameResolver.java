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

package org.apache.ibatis.reflection;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

public class ParamNameResolver {

    private static final String GENERIC_NAME_PREFIX = "param";

    /**
     * <p>
     * The key is the index and the value is the name of the parameter.<br />
     * The name is obtained from {@link Param} if specified. When {@link Param} is not specified,
     * the parameter index is used. Note that this index could be different from the actual index
     * when the method has special parameters (i.e. {@link RowBounds} or {@link ResultHandler}).
     * </p>
     * <ul>
     * <li>aMethod(@Param("M") int a, @Param("N") int b) -&gt; {{0, "M"}, {1, "N"}}</li>
     * <li>aMethod(int a, int b) -&gt; {{0, "0"}, {1, "1"}}</li>
     * <li>aMethod(int a, RowBounds rb, int b) -&gt; {{0, "0"}, {2, "1"}}</li>
     * </ul>
     */
    private final SortedMap<Integer, String> names;

    private boolean hasParamAnnotation;

    public ParamNameResolver(Configuration config, Method method) {

        // 获取参数类型列表
        final Class<?>[] paramTypes = method.getParameterTypes();

        // 获取参数注解
        final Annotation[][] paramAnnotations = method.getParameterAnnotations();
        final SortedMap<Integer, String> map = new TreeMap<Integer, String>();
        int paramCount = paramAnnotations.length;
        // get names from @Param annotations
        for (int paramIndex = 0; paramIndex < paramCount; paramIndex++) {
            // 检测当前的参数类型是否为 RowBounds 或 ResultHandler
            if (isSpecialParameter(paramTypes[paramIndex])) {
                // skip special parameters
                continue;
            }
            String name = null;
            for (Annotation annotation : paramAnnotations[paramIndex]) {
                if (annotation instanceof Param) {
                    hasParamAnnotation = true;
                    // 获取 @Param 注解内容
                    name = ((Param) annotation).value();
                    break;
                }
            }
            // name 为空，表明未给参数配置 @Param 注解
            if (name == null) {
                // @Param was not specified.
                // 检测是否设置了 useActualParamName 全局配置
                if (config.isUseActualParamName()) {
                    /*
                     * 通过反射获取参数名称。此种方式要求 JDK 版本为 1.8+，
                     * 且要求编译时加入 -parameters 参数，否则获取到的参数名
                     * 仍然是 arg1, arg2, ..., argN
                     */
                    name = getActualParamName(method, paramIndex);
                }
                if (name == null) {
                    // use the parameter index as the name ("0", "1", ...)
                    // gcode issue #71
                    /*
                     * 使用 map.size() 返回值作为名称，思考一下为什么不这样写：
                     *   name = String.valueOf(paramIndex);
                     * 因为如果参数列表中包含 RowBounds 或 ResultHandler，这两个参数
                     * 会被忽略掉，这样将导致名称不连续。
                     *
                     * 比如参数列表 (int p1, int p2, RowBounds rb, int p3)
                     *  - 期望得到名称列表为 ["0", "1", "2"]
                     *  - 实际得到名称列表为 ["0", "1", "3"]
                     */
                    name = String.valueOf(map.size());
                }
            }
            // 存储 paramIndex 到 name 的映射
            map.put(paramIndex, name);
        }
        names = Collections.unmodifiableSortedMap(map);
    }

    private String getActualParamName(Method method, int paramIndex) {
        if (Jdk.parameterExists) {
            return ParamNameUtil.getParamNames(method).get(paramIndex);
        }
        return null;
    }

    private static boolean isSpecialParameter(Class<?> clazz) {
        return RowBounds.class.isAssignableFrom(clazz) || ResultHandler.class.isAssignableFrom(clazz);
    }

    /**
     * Returns parameter names referenced by SQL providers.
     */
    public String[] getNames() {
        return names.values().toArray(new String[0]);
    }

    /**
     * <p>
     * A single non-special parameter is returned without a name.<br />
     * Multiple parameters are named using the naming rule.<br />
     * In addition to the default names, this method also adds the generic names (param1, param2,
     * ...).
     * </p>
     */
    public Object getNamedParams(Object[] args) {
        final int paramCount = names.size();
        if (args == null || paramCount == 0) {
            // 如果没参数
            return null;
        } else if (!hasParamAnnotation && paramCount == 1) {
            /*
             * 如果方法参数列表无 @Param 注解，且仅有一个非特别参数，则返回该参数的值。
             * 比如如下方法：
             *     List findList(RowBounds rb, String name)
             * names 如下：
             *     names = {1 : "0"}
             * 此种情况下，返回 args[names.firstKey()]，即 args[1] -> name
             */
            return args[names.firstKey()];
        } else {
            // 否则，返回一个ParamMap，修改参数名，参数名就是其位置
            final Map<String, Object> param = new ParamMap<Object>();
            int i = 0;
            for (Map.Entry<Integer, String> entry : names.entrySet()) {
                // 添加 <参数名, 参数值> 键值对到 param 中
                param.put(entry.getValue(), args[entry.getKey()]);
                // genericParamName = param + index。比如 param1, param2, ... paramN
                final String genericParamName = GENERIC_NAME_PREFIX + String.valueOf(i + 1);
                /*
                 * 检测 names 中是否包含 genericParamName，什么情况下会包含？答案如下：
                 *
                 *   使用者显式将参数名称配置为 param1，即 @Param("param1")
                 */
                if (!names.containsValue(genericParamName)) {
                    // 添加 <param*, value> 到 param 中
                    param.put(genericParamName, args[entry.getKey()]);
                }
                i++;
            }
            return param;
        }
    }
}
