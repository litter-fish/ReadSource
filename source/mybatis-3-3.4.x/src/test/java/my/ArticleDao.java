package my;

import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface ArticleDao {
    Article findOne(@Param("id") int id);
    Author findAuthor(@Param("id") int authorId);
}
