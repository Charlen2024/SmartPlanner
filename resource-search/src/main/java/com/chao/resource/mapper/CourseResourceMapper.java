package com.chao.resource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chao.resource.entity.CourseResource;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface CourseResourceMapper extends BaseMapper<CourseResource> {
    @Select("""
            SELECT DISTINCT topic
            FROM course_resources
            WHERE topic IS NOT NULL
              AND topic <> ''
              AND (topic LIKE CONCAT('%', #{q}, '%') OR #{q} LIKE CONCAT('%', topic, '%'))
            ORDER BY topic
            LIMIT #{limit}
            """)
    List<String> selectDistinctTopicsLike(@Param("q") String q, @Param("limit") int limit);

    @Select("""
            SELECT topic
            FROM course_resources
            WHERE topic IS NOT NULL
              AND topic <> ''
            GROUP BY topic
            ORDER BY MAX(created_at) DESC
            LIMIT #{limit}
            """)
    List<String> selectRecentTopics(@Param("limit") int limit);
}
