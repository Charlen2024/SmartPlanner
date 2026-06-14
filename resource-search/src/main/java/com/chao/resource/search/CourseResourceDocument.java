package com.chao.resource.search;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

@Data
@Document(indexName = "course_resources")
public class CourseResourceDocument {
    @Id
    private Long id;

    @Field(type = FieldType.Text)
    private String topic;

    @Field(type = FieldType.Text)
    private String title;

    @Field(type = FieldType.Keyword)
    private String platform;

    @Field(type = FieldType.Keyword)
    private String sourceUrl;

    @Field(type = FieldType.Text)
    private String contentSummary;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Field(type = FieldType.Dense_Vector, dims = 1536)
    private float[] embedding;

    @Field(type = FieldType.Long)
    private Long createdAtEpochMillis;
}
