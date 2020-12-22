package com.checkmarx.dto.bitbucket;

import com.checkmarx.dto.IDto;
import com.checkmarx.dto.IWebhookDto;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public @Data class WebhookBitbucketDto implements IWebhookDto {

    @JsonProperty("url")
    private String url;
    @JsonProperty("active")
    private boolean active;
    @JsonProperty("uuid")
    private String uuid;
    
    public String getId() {
        return uuid;
    }

    public String getName() {
        return uuid;
    }
}

