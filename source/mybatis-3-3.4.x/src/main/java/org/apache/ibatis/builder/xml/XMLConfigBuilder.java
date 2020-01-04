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
package org.apache.ibatis.builder.xml;

import java.io.InputStream;
import java.io.Reader;
import java.util.Properties;
import javax.sql.DataSource;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.datasource.DataSourceFactory;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.loader.ProxyFactory;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.io.VFS;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.AutoMappingUnknownColumnBehavior;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class XMLConfigBuilder extends BaseBuilder {

    private boolean parsed;
    private final XPathParser parser;
    private String environment;
    private final ReflectorFactory localReflectorFactory = new DefaultReflectorFactory();

    public XMLConfigBuilder(Reader reader) {
        this(reader, null, null);
    }

    public XMLConfigBuilder(Reader reader, String environment) {
        this(reader, environment, null);
    }

    public XMLConfigBuilder(Reader reader, String environment, Properties props) {
        /**
         * new XPathParser参数：Reader，是否进行DTD 校验，属性配置，XML实体节点解析器。
         */
        this(new XPathParser(reader, true, props, new XMLMapperEntityResolver()), environment, props);
    }

    public XMLConfigBuilder(InputStream inputStream) {
        this(inputStream, null, null);
    }

    public XMLConfigBuilder(InputStream inputStream, String environment) {
        this(inputStream, environment, null);
    }

    public XMLConfigBuilder(InputStream inputStream, String environment, Properties props) {
        this(new XPathParser(inputStream, true, props, new XMLMapperEntityResolver()), environment, props);
    }

    private XMLConfigBuilder(XPathParser parser, String environment, Properties props) {
        super(new Configuration());
        ErrorContext.instance().resource("SQL Mapper Configuration");
        this.configuration.setVariables(props);
        this.parsed = false;
        this.environment = environment;
        this.parser = parser;
    }

    public Configuration parse() {
        // 判断有没有解析过配置文件，只有没有解析过才允许解析。
        if (parsed) {
            throw new BuilderException("Each XMLConfigBuilder can only be used once.");
        }
        parsed = true;
        // mybatis配置文件解析的主流程
        parseConfiguration(parser.evalNode("/configuration"));
        return configuration;
    }

    private void parseConfiguration(XNode root) {
        try {
            //issue #117 read properties first
            /**
             *
             */
            propertiesElement(root.evalNode("properties"));
            //  加载settings节点settingsAsProperties, MyBatis 中极为重要的调整设置，它们会改变 MyBatis 的运行时行为
            Properties settings = settingsAsProperties(root.evalNode("settings"));
            // 加载自定义VFS loadCustomVfs, VFS主要用来加载容器内的各种资源，比如jar或者class文件。TODO ？？？？？
            /**
             * mybatis提供了2个实现 JBoss6VFS 和 DefaultVFS，并提供了用户扩展点，用于自定义VFS实现，加载顺序是自定义VFS实现 > 默认VFS实现 取第一个加载成功的，
             * 默认情况下会先加载JBoss6VFS，如果classpath下找不到jboss的vfs实现才会加载默认VFS实现，启动打印的日志如下：
             *
             *    org.apache.ibatis.io.VFS.getClass(VFS.java:111) Class not found: org.jboss.vfs.VFS
             * 　　org.apache.ibatis.io.JBoss6VFS.setInvalid(JBoss6VFS.java:142) JBoss 6 VFS API is not available in this environment.
             * 　　org.apache.ibatis.io.VFS.getClass(VFS.java:111) Class not found: org.jboss.vfs.VirtualFile
             * 　　org.apache.ibatis.io.VFS$VFSHolder.createVFS(VFS.java:63) VFS implementation org.apache.ibatis.io.JBoss6VFS is not valid in this environment.
             * 　　org.apache.ibatis.io.VFS$VFSHolder.createVFS(VFS.java:77) Using VFS adapter org.apache.ibatis.io.DefaultVFS
             *
             * 　　jboss vfs的maven仓库坐标为：
             *
             *     <dependency>
             *         <groupId>org.jboss</groupId>
             *         <artifactId>jboss-vfs</artifactId>
             *         <version>3.2.12.Final</version>
             *     </dependency>
             * 　　找到jboss vfs实现后，输出的日志如下：
             * 　　org.apache.ibatis.io.VFS$VFSHolder.createVFS(VFS.java:77) Using VFS adapter org.apache.ibatis.io.JBoss6VFS
             */
            loadCustomVfs(settings);

            // 解析类型别名typeAliasesElement, 类型别名是为 Java 类型设置一个短的名字。 它只和 XML 配置有关，存在的意义仅在于用来减少类完全限定名的冗余。
            /**
             * 具体类的别名
             * <typeAliases>
             *   <typeAlias alias="Blog" type="domain.blog.Blog"/>
             * </typeAliases>
             *
             * 包的别名
             * <typeAliases>
             *   <package name="domain.blog"/>
             * </typeAliases>
             */
            typeAliasesElement(root.evalNode("typeAliases"));

            // 加载插件pluginElement, MyBatis 允许你在已映射语句执行过程中的某一点进行拦截调用。默认情况下，MyBatis 允许使用插件来拦截的方法调用包括：
            //
            // Executor (update, query, flushStatements, commit, rollback, getTransaction, close, isClosed)
            // ParameterHandler (getParameterObject, setParameters)
            // ResultSetHandler (handleResultSets, handleOutputParameters)
            // StatementHandler (prepare, parameterize, batch, update, query)
            // 通过 MyBatis 提供的强大机制，使用插件是非常简单的，只需实现 Interceptor 接口，并指定想要拦截的方法签名即可。
            pluginElement(root.evalNode("plugins"));

            // 加载对象工厂objectFactoryElement, MyBatis 每次创建结果对象的新实例时，它都会使用一个对象工厂（ObjectFactory）实例来完成。
            // 默认的对象工厂需要做的仅仅是实例化目标类，要么通过默认构造方法，要么在参数映射存在的时候通过参数构造方法来实例化。
            // 如果想覆盖对象工厂的默认行为，则可以通过创建自己的对象工厂来实现。
            objectFactoryElement(root.evalNode("objectFactory"));

            // 创建对象包装器工厂objectWrapperFactoryElement
            // 主要用来包装返回result对象，比如说可以用来设置某些敏感字段脱敏或者加密等。  TODO ???????
            objectWrapperFactoryElement(root.evalNode("objectWrapperFactory"));

            // 加载反射工厂reflectorFactoryElement
            // 要实现反射工厂，只要实现ReflectorFactory接口即可。默认的反射工厂是DefaultReflectorFactory。
            reflectorFactoryElement(root.evalNode("reflectorFactory"));
            settingsElement(settings);

            // read it after objectFactory and objectWrapperFactory issue #631
            // 解析环境配置（environments）
            environmentsElement(root.evalNode("environments"));
            // 获取数据库厂商标识
            databaseIdProviderElement(root.evalNode("databaseIdProvider"));
            // 解析类型处理器（typeHandlers）
            typeHandlerElement(root.evalNode("typeHandlers"));

            // 解析定义 SQL 映射语句
            // 使用相对于类路径的资源引用：<mapper resource="org/mybatis/builder/AuthorMapper.xml"/>
            // 使用完全限定资源定位符（URL）：<mapper url="file:///var/mappers/AuthorMapper.xml"/>
            // 使用映射器接口实现类的完全限定类名：<mapper class="org.mybatis.builder.AuthorMapper"/>
            // 将包内的映射器接口实现全部注册为映射器：<package name="org.mybatis.builder"/>
            mapperElement(root.evalNode("mappers"));
        } catch (Exception e) {
            throw new BuilderException("Error parsing SQL Mapper Configuration. Cause: " + e, e);
        }
    }

    private Properties settingsAsProperties(XNode context) {
        if (context == null) {
            return new Properties();
        }
        // 获取配置中所有子节点，并加入到Properties对象中
        Properties props = context.getChildrenAsProperties();
        // MetaClass 对象为Configuration对象通过反射解析的元属性
        // 检查所有从settings加载的设置,确保它们都在Configuration定义的范围内
        MetaClass metaConfig = MetaClass.forClass(Configuration.class, localReflectorFactory);
        for (Object key : props.keySet()) {
            if (!metaConfig.hasSetter(String.valueOf(key))) {
                throw new BuilderException("The setting " + key + " is not known.  Make sure you spelled it correctly (case sensitive).");
            }
        }
        return props;
    }

    private void loadCustomVfs(Properties props) throws ClassNotFoundException {
        String value = props.getProperty("vfsImpl");
        if (value != null) {
            String[] clazzes = value.split(",");
            for (String clazz : clazzes) {
                if (!clazz.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    Class<? extends VFS> vfsImpl = (Class<? extends VFS>)Resources.classForName(clazz);
                    configuration.setVfsImpl(vfsImpl);
                }
            }
        }
    }

    private void typeAliasesElement(XNode parent) {
        if (parent != null) {
            for (XNode child : parent.getChildren()) {
                // 包的别名设置
                if ("package".equals(child.getName())) {
                    String typeAliasPackage = child.getStringAttribute("name");
                    configuration.getTypeAliasRegistry().registerAliases(typeAliasPackage);
                    // 具体类的别名
                } else {
                    String alias = child.getStringAttribute("alias");
                    String type = child.getStringAttribute("type");
                    try {
                        Class<?> clazz = Resources.classForName(type);
                        if (alias == null) {
                            typeAliasRegistry.registerAlias(clazz);
                        } else {
                            typeAliasRegistry.registerAlias(alias, clazz);
                        }
                    } catch (ClassNotFoundException e) {
                        throw new BuilderException("Error registering typeAlias for '" + alias + "'. Cause: " + e, e);
                    }
                }
            }
        }
    }

    private void pluginElement(XNode parent) throws Exception {
        if (parent != null) {
            // 遍历plugins下所有子节点
            for (XNode child : parent.getChildren()) {
                // 获取节点plugin中的interceptor属性
                String interceptor = child.getStringAttribute("interceptor");
                // 解析plugin下的所有子节点property，
                Properties properties = child.getChildrenAsProperties();

                // 将interceptor指定的名称解析为Interceptor类型
                Interceptor interceptorInstance = (Interceptor) resolveClass(interceptor).newInstance();
                interceptorInstance.setProperties(properties);
                // 将插件拦截器加入configuration对象的 interceptorChain集合中
                configuration.addInterceptor(interceptorInstance);
            }
        }
    }

    private void objectFactoryElement(XNode context) throws Exception {
        if (context != null) {
            String type = context.getStringAttribute("type");
            Properties properties = context.getChildrenAsProperties();
            ObjectFactory factory = (ObjectFactory) resolveClass(type).newInstance();
            factory.setProperties(properties);
            // 设置自定义对象工厂到configuration属性中
            configuration.setObjectFactory(factory);
        }
    }

    private void objectWrapperFactoryElement(XNode context) throws Exception {
        if (context != null) {
            String type = context.getStringAttribute("type");
            // 通过反射创建实例
            ObjectWrapperFactory factory = (ObjectWrapperFactory) resolveClass(type).newInstance();
            // 将创建的实例对象设置为 configuration 的 objectWrapperFactory 属性
            configuration.setObjectWrapperFactory(factory);
        }
    }

    private void reflectorFactoryElement(XNode context) throws Exception {
        if (context != null) {
            String type = context.getStringAttribute("type");
            // 通过反射创建实例
            ReflectorFactory factory = (ReflectorFactory) resolveClass(type).newInstance();
            // 将创建的实例对象设置为 configuration 的 reflectorFactory 属性
            configuration.setReflectorFactory(factory);
        }
    }

    private void propertiesElement(XNode context) throws Exception {
        if (context != null) {
            // 加载properties节点的子property节点
            Properties defaults = context.getChildrenAsProperties();
            // 获取resource属性
            String resource = context.getStringAttribute("resource");
            // 获取URL属性
            String url = context.getStringAttribute("url");
            // 不能同时包含resource、url属性
            if (resource != null && url != null) {
                throw new BuilderException("The properties element cannot specify both a URL and a resource based property file reference.  Please specify one or the other.");
            }
            if (resource != null) {
                // 解析resource指定文件的内容加入properties节点中
                defaults.putAll(Resources.getResourceAsProperties(resource));
            } else if (url != null) {
                // 解析url指定文件的内容加入properties节点中
                defaults.putAll(Resources.getUrlAsProperties(url));
            }
            // 加入硬编码设置的属性
            Properties vars = configuration.getVariables();
            if (vars != null) {
                defaults.putAll(vars);
            }
            parser.setVariables(defaults);
            configuration.setVariables(defaults);
        }
    }

    private void settingsElement(Properties props) throws Exception {
        // 设置 MyBatis 应如何自动映射列到字段或属性，NONE 表示取消自动映射；PARTIAL 只会自动映射没有定义嵌套结果集映射的结果集。 FULL 会自动映射任意复杂的结果集（无论是否嵌套）。
        configuration.setAutoMappingBehavior(AutoMappingBehavior.valueOf(props.getProperty("autoMappingBehavior", "PARTIAL")));
        /**
         * 指定发现自动映射目标未知列（或者未知属性类型）的行为。
         * NONE: 不做任何反应、WARNING: 输出提醒日志 ('org.apache.ibatis.session.AutoMappingUnknownColumnBehavior' 的日志等级必须设置为 WARN)、FAILING: 映射失败 (抛出 SqlSessionException)
         */
        configuration.setAutoMappingUnknownColumnBehavior(AutoMappingUnknownColumnBehavior.valueOf(props.getProperty("autoMappingUnknownColumnBehavior", "NONE")));
        // 设置全局地开启或关闭缓存属性
        configuration.setCacheEnabled(booleanValueOf(props.getProperty("cacheEnabled"), true));
        // 设置 Mybatis 创建具有延迟加载能力的对象所用到的代理工具。	CGLIB | JAVASSIST
        configuration.setProxyFactory((ProxyFactory) createInstance(props.getProperty("proxyFactory")));
        // 设置延迟加载的全局开关。当开启时，所有关联对象都会延迟加载。 特定关联关系中可通过设置 fetchType 属性来覆盖该项的开关状态。
        configuration.setLazyLoadingEnabled(booleanValueOf(props.getProperty("lazyLoadingEnabled"), false));
        // 当开启时，任何方法的调用都会加载该对象的所有属性。 否则，每个属性会按需加载
        configuration.setAggressiveLazyLoading(booleanValueOf(props.getProperty("aggressiveLazyLoading"), false));
        // 设置是否允许单一语句返回多结果集（需要驱动支持）
        configuration.setMultipleResultSetsEnabled(booleanValueOf(props.getProperty("multipleResultSetsEnabled"), true));
        // 设置是否使用列标签代替列名
        configuration.setUseColumnLabel(booleanValueOf(props.getProperty("useColumnLabel"), true));
        // 设置允许 JDBC 支持自动生成主键，需要驱动支持。 如果设置为 true 则这个设置强制使用自动生成主键，尽管一些驱动不能支持但仍可正常工作
        configuration.setUseGeneratedKeys(booleanValueOf(props.getProperty("useGeneratedKeys"), false));
        /**
         * 配置默认的执行器。
         * SIMPLE 就是普通的执行器；
         * REUSE 执行器会重用预处理语句（prepared statements）；
         * BATCH 执行器将重用语句并执行批量更新。
         */
        configuration.setDefaultExecutorType(ExecutorType.valueOf(props.getProperty("defaultExecutorType", "SIMPLE")));
        // 设置超时时间，它决定驱动等待数据库响应的秒数
        configuration.setDefaultStatementTimeout(integerValueOf(props.getProperty("defaultStatementTimeout"), null));
        // 为驱动的结果集获取数量（fetchSize）设置一个提示值。此参数只可以在查询设置中被覆盖。
        configuration.setDefaultFetchSize(integerValueOf(props.getProperty("defaultFetchSize"), null));
        // 设置是否开启自动驼峰命名规则（camel case）映射，即从经典数据库列名 A_COLUMN 到经典 Java 属性名 aColumn 的类似映射。
        configuration.setMapUnderscoreToCamelCase(booleanValueOf(props.getProperty("mapUnderscoreToCamelCase"), false));
        // 设置是否允许在嵌套语句中使用分页（RowBounds）。如果允许使用则设置为 false
        configuration.setSafeRowBoundsEnabled(booleanValueOf(props.getProperty("safeRowBoundsEnabled"), false));
        /**
         * MyBatis 利用本地缓存机制（Local Cache）防止循环引用（circular references）和加速重复嵌套查询。
         * 默认值为 SESSION，这种情况下会缓存一个会话中执行的所有查询。
         * 若设置值为 STATEMENT，本地会话仅用在语句执行上，对相同 SqlSession 的不同调用将不会共享数据。
         */
        configuration.setLocalCacheScope(LocalCacheScope.valueOf(props.getProperty("localCacheScope", "SESSION")));
        // 设置当没有为参数提供特定的 JDBC 类型时，为空值指定 JDBC 类型。 某些驱动需要指定列的 JDBC 类型，多数情况直接用一般类型即可，比如 NULL、VARCHAR 或 OTHER。
        configuration.setJdbcTypeForNull(JdbcType.valueOf(props.getProperty("jdbcTypeForNull", "OTHER")));
        // 设置指定哪个对象的方法触发一次延迟加载。 用逗号分隔的方法列表。
        configuration.setLazyLoadTriggerMethods(stringSetValueOf(props.getProperty("lazyLoadTriggerMethods"), "equals,clone,hashCode,toString"));
        // 允许在嵌套语句中使用分页（ResultHandler）。如果允许使用则设置为 false
        configuration.setSafeResultHandlerEnabled(booleanValueOf(props.getProperty("safeResultHandlerEnabled"), true));
        // 指定动态 SQL 生成的默认语言
        configuration.setDefaultScriptingLanguage(resolveClass(props.getProperty("defaultScriptingLanguage")));
        @SuppressWarnings("unchecked")
        Class<? extends TypeHandler> typeHandler = (Class<? extends TypeHandler>)resolveClass(props.getProperty("defaultEnumTypeHandler"));
        // 指定 Enum 使用的默认 TypeHandler
        configuration.setDefaultEnumTypeHandler(typeHandler);
        // 指定当结果集中值为 null 的时候是否调用映射对象的 setter（map 对象时为 put）方法，这在依赖于 Map.keySet() 或 null 值初始化的时候比较有用。注意基本类型（int、boolean 等）是不能设置成 null 的。
        configuration.setCallSettersOnNulls(booleanValueOf(props.getProperty("callSettersOnNulls"), false));
        // 设置允许使用方法签名中的名称作为语句参数名称
        configuration.setUseActualParamName(booleanValueOf(props.getProperty("useActualParamName"), true));
        // 当返回行的所有列都是空时，MyBatis默认返回 null。 当开启这个设置时，MyBatis会返回一个空实例。
        configuration.setReturnInstanceForEmptyRow(booleanValueOf(props.getProperty("returnInstanceForEmptyRow"), false));
        // 指定 MyBatis 增加到日志名称的前缀。
        configuration.setLogPrefix(props.getProperty("logPrefix"));
        @SuppressWarnings("unchecked")
        Class<? extends Log> logImpl = (Class<? extends Log>)resolveClass(props.getProperty("logImpl"));
        // 指定 MyBatis 所用日志的具体实现，未指定时将自动查找。	SLF4J | LOG4J | LOG4J2 | JDK_LOGGING | COMMONS_LOGGING | STDOUT_LOGGING | NO_LOGGING
        configuration.setLogImpl(logImpl);
        // 指定一个提供 Configuration 实例的类。 这个被返回的 Configuration 实例用来加载被反序列化对象的延迟加载属性值。 这个类必须包含一个签名为static Configuration getConfiguration() 的方法。
        configuration.setConfigurationFactory(resolveClass(props.getProperty("configurationFactory")));
    }

    private void environmentsElement(XNode context) throws Exception {
        if (context != null) {
            // environment通过构造方法传入
            if (environment == null) {
                // 默认使用的环境 ID
                environment = context.getStringAttribute("default");
            }
            for (XNode child : context.getChildren()) {
                // 每个 environment 元素定义的环境 ID
                String id = child.getStringAttribute("id");
                // 如果指定的环境
                if (isSpecifiedEnvironment(id)) {
                    // 获取事务管理器的配置
                    TransactionFactory txFactory = transactionManagerElement(child.evalNode("transactionManager"));
                    // 获取数据源的配置
                    DataSourceFactory dsFactory = dataSourceElement(child.evalNode("dataSource"));
                    DataSource dataSource = dsFactory.getDataSource();
                    Environment.Builder environmentBuilder = new Environment.Builder(id)
                            .transactionFactory(txFactory)
                            .dataSource(dataSource);
                    configuration.setEnvironment(environmentBuilder.build());
                }
            }
        }
    }

    private void databaseIdProviderElement(XNode context) throws Exception {
        DatabaseIdProvider databaseIdProvider = null;
        if (context != null) {
            String type = context.getStringAttribute("type");
            // awful patch to keep backward compatibility
            if ("VENDOR".equals(type)) {
                type = "DB_VENDOR";
            }
            Properties properties = context.getChildrenAsProperties();
            databaseIdProvider = (DatabaseIdProvider) resolveClass(type).newInstance();
            databaseIdProvider.setProperties(properties);
        }
        Environment environment = configuration.getEnvironment();
        if (environment != null && databaseIdProvider != null) {
            String databaseId = databaseIdProvider.getDatabaseId(environment.getDataSource());
            configuration.setDatabaseId(databaseId);
        }
    }

    private TransactionFactory transactionManagerElement(XNode context) throws Exception {
        if (context != null) {
            // 在 MyBatis 中有两种类型的事务管理器（也就是 type=”[JDBC|MANAGED]”）：
            // JDBC – 这个配置就是直接使用了 JDBC 的提交和回滚设置，它依赖于从数据源得到的连接来管理事务作用域。
            // MANAGED – 这个配置几乎没做什么。它从来不提交或回滚一个连接，而是让容器来管理事务的整个生命周期（比如 JEE 应用服务器的上下文）。
            // 默认情况下它会关闭连接，然而一些容器并不希望这样，因此需要将 closeConnection 属性设置为 false 来阻止它默认的关闭行为
            String type = context.getStringAttribute("type");
            Properties props = context.getChildrenAsProperties();
            TransactionFactory factory = (TransactionFactory) resolveClass(type).newInstance();
            factory.setProperties(props);
            return factory;
        }
        throw new BuilderException("Environment declaration requires a TransactionFactory.");
    }

    private DataSourceFactory dataSourceElement(XNode context) throws Exception {
        if (context != null) {
            // 获取数据源类型：类型，UNPOOLED|POOLED|JNDI
            // UNPOOLED– 这个数据源的实现只是每次被请求时打开和关闭连接
            // POOLED– 这种数据源的实现利用“池”的概念将 JDBC 连接对象组织起来，避免了创建新的连接实例时所必需的初始化和认证时间。
            // JNDI – 这个数据源的实现是为了能在如 EJB 或应用服务器这类容器中使用，容器可以集中或在外部配置数据源，然后放置一个 JNDI 上下文的引用。
            String type = context.getStringAttribute("type");
            // 获取特定数据类型的属性
            Properties props = context.getChildrenAsProperties();
            // 获取别名，通过反射创建实例
            DataSourceFactory factory = (DataSourceFactory) resolveClass(type).newInstance();
            factory.setProperties(props);
            return factory;
        }
        throw new BuilderException("Environment declaration requires a DataSourceFactory.");
    }

    private void typeHandlerElement(XNode parent) throws Exception {
        if (parent != null) {
            for (XNode child : parent.getChildren()) {
                // 包类型解析
                if ("package".equals(child.getName())) {
                    String typeHandlerPackage = child.getStringAttribute("name");
                    // 注册类型解析器
                    typeHandlerRegistry.register(typeHandlerPackage);
                } else {
                    // MyBatis 可以得知该类型处理器处理的 Java 类型
                    // 在类型处理器的配置元素（typeHandler 元素）上增加一个 javaType 属性（比如：javaType="String"）；
                    // 在类型处理器的类上（TypeHandler class）增加一个 @MappedTypes 注解来指定与其关联的 Java 类型列表。 如果在 javaType 属性中也同时指定，则注解方式将被忽略。
                    String javaTypeName = child.getStringAttribute("javaType");

                    // 指定被关联的 JDBC 类型：
                    // 在类型处理器的配置元素上增加一个 jdbcType 属性（比如：jdbcType="VARCHAR"）；
                    // 在类型处理器的类上增加一个 @MappedJdbcTypes 注解来指定与其关联的 JDBC 类型列表。 如果在 jdbcType 属性中也同时指定，则注解方式将被忽略。
                    String jdbcTypeName = child.getStringAttribute("jdbcType");
                    String handlerTypeName = child.getStringAttribute("handler");
                    Class<?> javaTypeClass = resolveClass(javaTypeName);
                    JdbcType jdbcType = resolveJdbcType(jdbcTypeName);
                    Class<?> typeHandlerClass = resolveClass(handlerTypeName);
                    if (javaTypeClass != null) {
                        if (jdbcType == null) {
                            // 注册类型解析器
                            typeHandlerRegistry.register(javaTypeClass, typeHandlerClass);
                        } else {
                            // 注册类型解析器
                            typeHandlerRegistry.register(javaTypeClass, jdbcType, typeHandlerClass);
                        }
                    } else {
                        // 注册类型解析器
                        typeHandlerRegistry.register(typeHandlerClass);
                    }
                }
            }
        }
    }

    /**
     * 如果要同时使用package自动扫描和通过mapper明确指定要加载的mapper，则必须先声明mapper，然后声明package，否则DTD校验会失败。
     */
    private void mapperElement(XNode parent) throws Exception {
        if (parent != null) {
            for (XNode child : parent.getChildren()) {
                // 如果要同时使用package自动扫描和通过mapper明确指定要加载的mapper，一定要确保package自动扫描的范围不包含明确指定的mapper，
                // 否则在通过package扫描的interface的时候，尝试加载对应xml文件的loadXmlResource()的逻辑中出现判重出错，
                // 报org.apache.ibatis.binding.BindingException异常，即使xml文件中包含的内容和mapper接口中包含的语句不重复也会出错，
                // 包括加载mapper接口时自动加载的xml mapper也一样会出错。
                /**
                 * <mappers>
                 *   <package name="org.mybatis.builder"/>
                 * </mappers>
                 */
                if ("package".equals(child.getName())) {
                    String mapperPackage = child.getStringAttribute("name");

                    // 通过package自动搜索加载的方式
                    configuration.addMappers(mapperPackage);
                } else {
                    String resource = child.getStringAttribute("resource");
                    String url = child.getStringAttribute("url");
                    String mapperClass = child.getStringAttribute("class");
                    /**
                     * <mappers>
                     *   <mapper resource="org/mybatis/builder/AuthorMapper.xml"/>
                     * </mappers>
                     */
                    if (resource != null && url == null && mapperClass == null) {
                        ErrorContext.instance().resource(resource);
                        InputStream inputStream = Resources.getResourceAsStream(resource);
                        XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments());
                        mapperParser.parse();

                        /**
                         * <mappers>
                         *   <mapper url="file:///var/mappers/AuthorMapper.xml"/>
                         * </mappers>
                         */
                    } else if (resource == null && url != null && mapperClass == null) {
                        ErrorContext.instance().resource(url);
                        InputStream inputStream = Resources.getUrlAsStream(url);
                        XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, url, configuration.getSqlFragments());
                        mapperParser.parse();

                        /**
                         * <mappers>
                         *   <mapper class="org.mybatis.builder.AuthorMapper"/>
                         * </mappers>
                         */
                    } else if (resource == null && url == null && mapperClass != null) {
                        Class<?> mapperInterface = Resources.classForName(mapperClass);
                        configuration.addMapper(mapperInterface);
                    } else {
                        throw new BuilderException("A mapper element may only specify a url, resource or class, but not more than one.");
                    }
                }
            }
        }
    }

    private boolean isSpecifiedEnvironment(String id) {
        if (environment == null) {
            throw new BuilderException("No environment specified.");
        } else if (id == null) {
            throw new BuilderException("Environment requires an id attribute.");
        } else if (environment.equals(id)) {
            return true;
        }
        return false;
    }

}
