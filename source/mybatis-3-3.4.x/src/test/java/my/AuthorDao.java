package my;

import java.util.List;

public interface AuthorDao {
    int insertMany(List<Author> authors);
}
