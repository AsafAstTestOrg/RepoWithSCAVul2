package com.checkmarx.controller;

import com.checkmarx.dto.github.OAuthTokenDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

@Slf4j
@RestController
public class DataSourceController {

    @Autowired
    RestTemplate restTemplate;

    public Boolean saveToken(OAuthTokenDto accessToken) {
        //TODO
        return true;
    }
}
