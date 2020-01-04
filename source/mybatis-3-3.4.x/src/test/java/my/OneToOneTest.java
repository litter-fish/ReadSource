package my;

import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class OneToOneTest {
    private SqlSessionFactory sqlSessionFactory;

    @Before
    public void prepare() throws IOException {
        String resource = "./my/mybatis-one-to-one-config.xml";
        InputStream inputStream = Resources.getResourceAsStream(resource);
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(inputStream);
        inputStream.close();
    }

    @Test
    public void testOne2One() {
        SqlSession session = sqlSessionFactory.openSession();
        try {
            ArticleDao articleDao = session.getMapper(ArticleDao.class);
            Article article = articleDao.findOne(1);

            System.out.println("\narticles info:");
            System.out.println(article);

            System.out.println("\n延迟加载 author 字段：");
            // 通过 getter 方法触发延迟加载
            Author author = article.getAuthor();
            System.out.println("\narticles info:");
            System.out.println(article);
            System.out.println("\nauthor info:");
            System.out.println(author);
        } finally {
            session.close();
        }
    }

    @Test
    public void testInsertMany() {
        SqlSession session = sqlSessionFactory.openSession();
        try {
            List<Author> authors = new ArrayList();
            // 添加多个 Author 对象到 authors 中
            authors.add(new Author("11111-1", 20, 0, "1111111"));
            authors.add(new Author("2222-2", 18, 0, "22222222"));

            System.out.println("\nBefore Insert: ");
            authors.forEach(author -> System.out.println("  " + author));
            System.out.println();

            AuthorDao authorDao = session.getMapper(AuthorDao.class);
            authorDao.insertMany(authors);
            session.commit();

            System.out.println("\nAfter Insert: ");
            authors.forEach(author -> System.out.println("  " + author));
        } finally {
            session.close();
        }
    }

}
