package com.checkmarx.dto.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "id",
        "name",
        "webhookId",
        "webhookEnabled"
})
@Builder
@AllArgsConstructor
@NoArgsConstructor
public @Data class RepoWebDto {

    @JsonProperty("id")
    private String id;
    @JsonProperty("name")
    private String name;
    @JsonProperty("webhookId")
    private String webhookId;
    @JsonProperty("webhookEnabled")
    private boolean webhookEnabled;
}
