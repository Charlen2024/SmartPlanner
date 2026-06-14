package com.chao.common.dto;

import lombok.Data;
import java.util.List;

@Data
public class ResourceAdviceResult {
    private String topic;
    private String advice;
    private List<SearchResourceItem> resources;
}
