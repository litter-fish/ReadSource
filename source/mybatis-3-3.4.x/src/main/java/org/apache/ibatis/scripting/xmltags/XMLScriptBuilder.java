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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.scripting.defaults.RawSqlSource;
import org.apache.ibatis.session.Configuration;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * @author Clinton Begin
 */
public class XMLScriptBuilder extends BaseBuilder {

    private final XNode context;
    private boolean isDynamic;
    private final Class<?> parameterType;
    private final Map<String, NodeHandler> nodeHandlerMap = new HashMap<String, NodeHandler>();

    public XMLScriptBuilder(Configuration configuration, XNode context) {
        this(configuration, context, null);
    }

    public XMLScriptBuilder(Configuration configuration, XNode context, Class<?> parameterType) {
        super(configuration);
        this.context = context;
        this.parameterType = parameterType;
        initNodeHandlerMap();
    }


    private void initNodeHandlerMap() {
        nodeHandlerMap.put("trim", new TrimHandler());
        nodeHandlerMap.put("where", new WhereHandler());
        nodeHandlerMap.put("set", new SetHandler());
        nodeHandlerMap.put("foreach", new ForEachHandler());
        nodeHandlerMap.put("if", new IfHandler());
        nodeHandlerMap.put("choose", new ChooseHandler());
        nodeHandlerMap.put("when", new IfHandler());
        nodeHandlerMap.put("otherwise", new OtherwiseHandler());
        nodeHandlerMap.put("bind", new BindHandler());
    }

    public SqlSource parseScriptNode() {
        // 解析动态标签
        MixedSqlNode rootSqlNode = parseDynamicTags(context);
        SqlSource sqlSource = null;
        if (isDynamic) {
            sqlSource = new DynamicSqlSource(configuration, rootSqlNode);
        } else {
            sqlSource = new RawSqlSource(configuration, rootSqlNode, parameterType);
        }
        return sqlSource;
    }

    protected MixedSqlNode parseDynamicTags(XNode node) {
        List<SqlNode> contents = new ArrayList<SqlNode>();
        NodeList children = node.getNode().getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            XNode child = node.newXNode(children.item(i));
            // 文本节点
            if (child.getNode().getNodeType() == Node.CDATA_SECTION_NODE || child.getNode().getNodeType() == Node.TEXT_NODE) {
                String data = child.getStringBody("");
                TextSqlNode textSqlNode = new TextSqlNode(data);
                // 判断文本节点中是否包含了${}，如果包含则为动态文本节点，否则为静态文本节点，静态文本节点在运行时不需要二次处理
                if (textSqlNode.isDynamic()) {
                    contents.add(textSqlNode);
                    isDynamic = true;
                } else {
                    contents.add(new StaticTextSqlNode(data));
                }
            } else if (child.getNode().getNodeType() == Node.ELEMENT_NODE) { // issue #628 标签节点
                String nodeName = child.getNode().getNodeName();

                // 首先根据节点名称获取到对应的节点处理器
                NodeHandler handler = nodeHandlerMap.get(nodeName);
                if (handler == null) {
                    throw new BuilderException("Unknown element <" + nodeName + "> in SQL statement.");
                }

                // 使用对应的节点处理器处理本节点
                handler.handleNode(child, contents);
                isDynamic = true;
            }
        }
        return new MixedSqlNode(contents);
    }

    private interface NodeHandler {
        void handleNode(XNode nodeToHandle, List<SqlNode> targetContents);
    }

    /**
     * <select id="selectBlogsLike" parameterType="BlogQuery" resultType="Blog">
     *   <bind name="pattern" value="'%' + title + '%'" />
     *   SELECT * FROM BLOG
     *   WHERE title LIKE #{pattern}
     * </select>
     */
    private class BindHandler implements NodeHandler {
        public BindHandler() {
            // Prevent Synthetic Access
        }

        @Override
        public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
            // 变量名称
            final String name = nodeToHandle.getStringAttribute("name");
            // OGNL表达式
            final String expression = nodeToHandle.getStringAttribute("value");
            final VarDeclSqlNode node = new VarDeclSqlNode(name, expression);
            targetContents.add(node);
        }
    }

    /**
     * select * from user
     * <trim prefix="WHERE" prefixoverride="AND |OR">
     *     <if test="name != null and name.length()>0"> AND name=#{name}</if>
     *     <if test="gender != null and gender.length()>0"> AND gender=#{gender}</if>
     * </trim>
     * update user
     * <trim prefix="set" suffixoverride="," suffix=" where id = #{id} ">
     *     <if test="name != null and name.length()>0"> name=#{name} , </if>
     *     <if test="gender != null and gender.length()>0"> gender=#{gender} ,  </if>
     * </trim>
     */
    private class TrimHandler implements NodeHandler {
        public TrimHandler() {
            // Prevent Synthetic Access
        }

        @Override
        public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
            MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
            // 包含的子节点解析后SQL文本不为空时要添加的前缀内容
            String prefix = nodeToHandle.getStringAttribute("prefix");
            // 要覆盖的子节点解析后SQL文本前缀内容
            String prefixOverrides = nodeToHandle.getStringAttribute("prefixOverrides");
            // 包含的子节点解析后SQL文本不为空时要添加的后缀内容
            String suffix = nodeToHandle.getStringAttribute("suffix");
            // 要覆盖的子节点解析后SQL文本后缀内容
            String suffixOverrides = nodeToHandle.getStringAttribute("suffixOverrides");
            TrimSqlNode trim = new TrimSqlNode(configuration, mixedSqlNode, prefix, prefixOverrides, suffix, suffixOverrides);
            targetContents.add(trim);
        }
    }

    private class WhereHandler implements NodeHandler {
        public WhereHandler() {
            // Prevent Synthetic Access
        }

        @Override
        public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
            MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
            WhereSqlNode where = new WhereSqlNode(configuration, mixedSqlNode);
            targetContents.add(where);
        }
    }

    /**
     * <update id="updateAuthorIfNecessary">
     *   update Author
     *     <set>
     *       <if test="username != null">username=#{username},</if>
     *       <if test="password != null">password=#{password},</if>
     *       <if test="email != null">email=#{email},</if>
     *       <if test="bio != null">bio=#{bio}</if>
     *     </set>
     *   where id=#{id}
     * </update>
     */
    private class SetHandler implements NodeHandler {
        public SetHandler() {
            // Prevent Synthetic Access
        }

        @Override
        public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
            MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
            SetSqlNode set = new SetSqlNode(configuration, mixedSqlNode);
            targetContents.add(set);
        }
    }

    /**
     * <foreach item="item" index="index" collection="list"
     *     open="(" separator="," close=")">
     *       #{item}
     * </foreach>
     */
    private class ForEachHandler implements NodeHandler {
        public ForEachHandler() {
            // Prevent Synthetic Access
        }

        @Override
        public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
            MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
            String collection = nodeToHandle.getStringAttribute("collection");
            String item = nodeToHandle.getStringAttribute("item");
            String index = nodeToHandle.getStringAttribute("index");
            String open = nodeToHandle.getStringAttribute("open");
            String close = nodeToHandle.getStringAttribute("close");
            String separator = nodeToHandle.getStringAttribute("separator");
            ForEachSqlNode forEachSqlNode = new ForEachSqlNode(configuration, mixedSqlNode, collection, index, item, open, close, separator);
            targetContents.add(forEachSqlNode);
        }
    }

    private class IfHandler implements NodeHandler {
        public IfHandler() {
            // Prevent Synthetic Access
        }

        @Override
        public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
            MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
            // 获取if属性的值，将值设置为IfSqlNode的属性，便于运行时解析
            String test = nodeToHandle.getStringAttribute("test");
            IfSqlNode ifSqlNode = new IfSqlNode(mixedSqlNode, test);
            targetContents.add(ifSqlNode);
        }
    }

    private class OtherwiseHandler implements NodeHandler {
        public OtherwiseHandler() {
            // Prevent Synthetic Access
        }

        @Override
        public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
            MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
            targetContents.add(mixedSqlNode);
        }
    }

    /**
     * <choose>
     *   <when test="title != null">
     *     AND title like #{title}
     *   </when>
     *   <when test="author != null and author.name != null">
     *     AND author_name like #{author.name}
     *   </when>
     *   <otherwise>
     *     AND featured = 1
     *   </otherwise>
     * </choose>
     */
    private class ChooseHandler implements NodeHandler {
        public ChooseHandler() {
            // Prevent Synthetic Access
        }

        @Override
        public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
            List<SqlNode> whenSqlNodes = new ArrayList<SqlNode>();
            List<SqlNode> otherwiseSqlNodes = new ArrayList<SqlNode>();

            // 拆分出when 和 otherwise 节点
            handleWhenOtherwiseNodes(nodeToHandle, whenSqlNodes, otherwiseSqlNodes);
            SqlNode defaultSqlNode = getDefaultSqlNode(otherwiseSqlNodes);
            ChooseSqlNode chooseSqlNode = new ChooseSqlNode(whenSqlNodes, defaultSqlNode);
            targetContents.add(chooseSqlNode);
        }

        private void handleWhenOtherwiseNodes(XNode chooseSqlNode, List<SqlNode> ifSqlNodes, List<SqlNode> defaultSqlNodes) {
            List<XNode> children = chooseSqlNode.getChildren();
            for (XNode child : children) {
                String nodeName = child.getNode().getNodeName();
                NodeHandler handler = nodeHandlerMap.get(nodeName);
                if (handler instanceof IfHandler) {
                    handler.handleNode(child, ifSqlNodes);
                } else if (handler instanceof OtherwiseHandler) {
                    handler.handleNode(child, defaultSqlNodes);
                }
            }
        }

        private SqlNode getDefaultSqlNode(List<SqlNode> defaultSqlNodes) {
            SqlNode defaultSqlNode = null;
            if (defaultSqlNodes.size() == 1) {
                defaultSqlNode = defaultSqlNodes.get(0);
            } else if (defaultSqlNodes.size() > 1) {
                throw new BuilderException("Too many default (otherwise) elements in choose statement.");
            }
            return defaultSqlNode;
        }
    }

}
