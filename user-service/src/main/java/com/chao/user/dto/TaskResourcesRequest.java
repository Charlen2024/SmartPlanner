package com.chao.user.dto;

import java.util.List;

public class TaskResourcesRequest {
    private List<Long> taskIds;
    private Integer topK;
    private Boolean refresh;

    public List<Long> getTaskIds() { return taskIds; }
    public void setTaskIds(List<Long> taskIds) { this.taskIds = taskIds; }
    public Integer getTopK() { return topK; }
    public void setTopK(Integer topK) { this.topK = topK; }
    public Boolean getRefresh() { return refresh; }
    public void setRefresh(Boolean refresh) { this.refresh = refresh; }
}
