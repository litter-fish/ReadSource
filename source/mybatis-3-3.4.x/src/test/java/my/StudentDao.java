package my;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.session.RowBounds;

import java.util.List;

public interface StudentDao {
    List<Student> findByPaging(@Param("id") Integer id, RowBounds rb);
}
