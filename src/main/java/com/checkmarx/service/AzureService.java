package com.checkmarx.service;

import com.checkmarx.controller.exception.ScmException;
import com.checkmarx.dto.AccessTokenDto;
import com.checkmarx.dto.BaseDto;
import com.checkmarx.dto.azure.*;
import com.checkmarx.dto.cxflow.CxFlowConfigDto;
import com.checkmarx.dto.datastore.OrgDto;
import com.checkmarx.dto.datastore.OrgReposDto;
import com.checkmarx.dto.datastore.ScmAccessTokenDto;
import com.checkmarx.dto.datastore.ScmDto;
import com.checkmarx.dto.gitlab.WebhookGitLabDto;
import com.checkmarx.dto.web.OrganizationWebDto;
import com.checkmarx.dto.web.RepoWebDto;
import com.checkmarx.utils.Converter;
import com.checkmarx.utils.RestWrapper;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;

import java.util.*;


@Slf4j
@Service("azure")
public class AzureService extends AbstractScmService implements ScmService  {

    private static final String API_VERSION = "6.0";
    
    private static final String BASE_HIGH_LEVEL_API_URL = "https://app.vssps.visualstudio.com";

    private static final String BASE_API_URL = "https://dev.azure.com";
    
    private static final String URL_AUTH_TOKEN = BASE_HIGH_LEVEL_API_URL + "/oauth2/token";

    private static final String URL_GET_USER_ID = BASE_HIGH_LEVEL_API_URL + "/_apis/profile/profiles/me?api-version=" + API_VERSION;
            
    private static final String URL_GET_USER_ACCOUNTS =  BASE_HIGH_LEVEL_API_URL + "/_apis/accounts?api-version=" + API_VERSION + "&memberId=%s";

    private static final String URL_GET_ALL_PROJECTS = BASE_API_URL +  "/%s/_apis/projects?api-version=" + API_VERSION;
    
    private static final String URL_GET_REPOS =  BASE_API_URL +  "/%s/%s/_apis/git/repositories?api-version=" + API_VERSION;

    private static final String BASE_DB_KEY = "azure.com";

    private static final String SCOPES ="vso.code_full vso.code_status vso.project_manage vso.threads_full vso.work_full";

    private static final String URL_GET_WEBHOOKS =    BASE_API_URL + "/%s/_apis/hooks/subscriptions?api-version=" + API_VERSION;

    private static final String URL_DELETE_WEBHOOK =  BASE_API_URL + "/%s/_apis/hooks/subscriptions/%s?api-version=" + API_VERSION;

    private static final String URL_CREATE_WEBHOOK =  BASE_API_URL + "/%s/_apis/hooks/subscriptions?api-version=" + API_VERSION;
    

    @Value("${azure.redirect.url}")
    private String azureRedirectUrl;

    /**
     * generateAccessToken method using OAuth code, client id and client secret generates access
     * token via GitHub api
     *
     * @param oAuthCode given from FE application after first-step OAuth implementation passed
     *                  successfully, taken from request param "code", using it to create access token
     * @return Access token given from GitHub
     */
    protected AccessTokenDto generateAccessToken(String oAuthCode) {
        ScmDto scmDto = dataStoreService.getScm(getBaseDbKey());
        String path = AzureService.URL_AUTH_TOKEN;
        ResponseEntity<AccessTokenDto> response = generateAccessToken(restWrapper, path, null, getBodyAccessToken(oAuthCode, scmDto));
        return response.getBody();
    }

    
    public ResponseEntity<AccessTokenDto> generateAccessToken(RestWrapper restWrapper, String path, Map<String, String> headers, Object body) {
        ResponseEntity<AccessTokenDto> response = sendAccessTokenRequest(restWrapper, path, headers, body);

        if(!verifyAccessToken(response.getBody())){
            log.error(RestWrapper.GENERATE_ACCESS_TOKEN_FAILURE);
            throw new ScmException(RestWrapper.GENERATE_ACCESS_TOKEN_FAILURE);
        }
        return response;
    }

    protected ResponseEntity<AccessTokenDto> sendAccessTokenRequest(RestWrapper restWrapper, String path, Map<String, String> headers, Object body) {
        return (ResponseEntity<AccessTokenDto>) restWrapper.sendUrlEncodedPostRequest(path, HttpMethod.POST,
                (MultiValueMap<String, String>)body, headers,
                AccessTokenDto.class);
    }


    protected Object getBodyAccessToken(String oAuthCode, ScmDto scmDto) {

        MultiValueMap<String, String> map= new LinkedMultiValueMap<>();

        map.put("client_assertion_type", Collections.singletonList("urn:ietf:params:oauth:client-assertion-type:jwt-bearer"));
        map.put("client_assertion",  Collections.singletonList(scmDto.getClientSecret()));
        map.put("grant_type",  Collections.singletonList("urn:ietf:params:oauth:grant-type:jwt-bearer"));
        map.put("assertion", Collections.singletonList(oAuthCode));
        map.put("redirect_uri", Collections.singletonList(azureRedirectUrl));
        return map;
    }

    
    @Override
    public String getScopes() {
        return SCOPES;
    }

    public String getBaseDbKey() {
        return BASE_DB_KEY;
    }

    @Override
    public List<OrganizationWebDto> getOrganizations(@NonNull String authCode) {
        AccessTokenDto accessToken = generateAccessToken(authCode);
        log.info("Access token generated successfully");

        ResponseEntity<BaseDto> responseId =
                restWrapper.sendBearerAuthRequest(URL_GET_USER_ID, HttpMethod.GET, null, null,
                        BaseDto.class, accessToken.getAccessToken());
        BaseDto userId = Objects.requireNonNull(responseId.getBody());

        String urlAccounts = String.format(URL_GET_USER_ACCOUNTS, userId.getId());
        
        ResponseEntity<AzureUserOrganizationsDto> response =
                restWrapper.sendBearerAuthRequest(urlAccounts, HttpMethod.GET, null, null,
                        AzureUserOrganizationsDto.class, accessToken.getAccessToken());

        AzureUserOrganizationsDto azureUserOrganizationsDto = Objects.requireNonNull(response.getBody());
        
         List<OrgDto> orgDtos = Converter.convertToListOrg(accessToken.getAccessToken(),
                                                      azureUserOrganizationsDto.getOrganizations(), getBaseDbKey());
        dataStoreService.storeOrgs(orgDtos);
        return Converter.convertToListOrgWebDtos(azureUserOrganizationsDto.getOrganizations());
    }
    
    @Override
    public List<RepoWebDto> getScmOrgRepos(@NonNull String orgId) {
        ScmAccessTokenDto scmAccessTokenDto = dataStoreService.getSCMOrgToken(getBaseDbKey(), orgId);

        String urlProjectsApi = String.format(URL_GET_ALL_PROJECTS, orgId);
        ResponseEntity<AzureProjectsDto> responseProjects =  restWrapper
                .sendBearerAuthRequest(urlProjectsApi, HttpMethod.GET,
                        null, null,
                        AzureProjectsDto.class, scmAccessTokenDto.getAccessToken());

        AzureProjectsDto azureProjectsIds = Objects.requireNonNull(responseProjects.getBody());

        ArrayList<RepoAzureDto>  projectsAndReposHooks = new ArrayList<>();

        List<AzureWebhookDto> orgHooks = getOrganizationCxFlowHooks(orgId, scmAccessTokenDto.getAccessToken());

        for (int i=0; i<azureProjectsIds.getCount() && azureProjectsIds.getProjectIds()!=null ; i++) {

            String projectId = azureProjectsIds.getProjectIds().get(i).getId();

            Map<String, String> repoHooks = getHooksOnRepoLevel(orgHooks, projectId);
            RepoListAzureDto projectRepos = getProjectRepos(orgId, scmAccessTokenDto, projectId);

            if(projectRepos.getCount()>0 && projectRepos.getRepos()!=null) {
                setWebhookFlag(repoHooks, projectRepos, projectId);
                projectsAndReposHooks.addAll(projectRepos.getRepos());
            }
            
        }
        OrgReposDto orgReposDto = Converter.convertToOrgRepoDto(scmAccessTokenDto, projectsAndReposHooks);
        dataStoreService.updateScmOrgRepo(orgReposDto);
        return Converter.convertToListRepoWebDto(projectsAndReposHooks);
    }
    

    private Map<String, String> getHooksOnRepoLevel(List<AzureWebhookDto> organizationHooks,
                                                    String projectId) {

        Map<String, String> repoHooks = new HashMap<>();
        
        organizationHooks.stream().forEach(projectHook -> {
            if(projectHook.getProjectId().equals(projectId) && !StringUtils.isEmpty(projectHook.getRepositoryId())){
                //hook on repo level
                //project level hooks - will be skipped
                repoHooks.put(projectHook.getRepositoryId(), projectHook.getHookId());
            }
        });
        
        return repoHooks;
       
    }

    private RepoListAzureDto getProjectRepos(@NonNull String orgId, ScmAccessTokenDto scmAccessTokenDto, String projectId) {
        String urlReposApi = String.format(URL_GET_REPOS, orgId, projectId);

        ResponseEntity<RepoListAzureDto> response = restWrapper
                .sendBearerAuthRequest(urlReposApi, HttpMethod.GET,
                        null, null,
                        RepoListAzureDto.class, scmAccessTokenDto.getAccessToken());
        return Objects.requireNonNull(response.getBody());
    }

    private void setWebhookFlag(Map<String, String> cxFlowHooks, RepoListAzureDto repoAzureDtos, String projectId) {
        for (RepoAzureDto repository : repoAzureDtos.getRepos()) {
            
            if(cxFlowHooks.containsKey(repository.getId())){
                repository.setWebHookEnabled(true);
                repository.setWebhookId(cxFlowHooks.get(repository.getId()));
            } else {
                repository.setWebHookEnabled(false);
            }
            repository.setId(projectId + BaseDto.SEPARATOR + repository.getId());
        }
    }


    @Override
    public String createWebhook(@NonNull String orgId, @NonNull String projectAndRepoIds ) {
        ScmAccessTokenDto scmAccessTokenDto = dataStoreService.getSCMOrgToken(getBaseDbKey(), orgId);
        String path = String.format(URL_CREATE_WEBHOOK, orgId, cxFlowWebHook, scmAccessTokenDto.getAccessToken()) ;

        List<String> listProjectAndRepo = getProjectAndRepoIds(projectAndRepoIds);
        
        String projectId = listProjectAndRepo.get(0);
        String repoId = listProjectAndRepo.get(1);
        
        BaseDto hookDtoHook1 = createHook(projectId, repoId, scmAccessTokenDto, path, AzureEvent.CREATE_PULL_REQEUST );
        BaseDto hookDtoHook2 = createHook(projectId, repoId, scmAccessTokenDto, path, AzureEvent.UPDATE_PULL_REQEUST);
        BaseDto hookDtoHook3 = createHook(projectId, repoId, scmAccessTokenDto, path, AzureEvent.PUSH);
        BaseDto hookDto = hookDtoHook1.merge(hookDtoHook2).merge(hookDtoHook3);
        dataStoreService.updateWebhook(repoId, scmAccessTokenDto, hookDto.getId(), true);
        return hookDto.getId();
    }

    private List<String> getProjectAndRepoIds(@NonNull String projectAndRepoId) {
        List<String> listProjectAndRepo = BaseDto.splitId(projectAndRepoId);

        if(listProjectAndRepo.size()!= 2){
            throw new ScmException("Invalid input to createWebhook. The input should consist of project and repository Ids");
        }
        return listProjectAndRepo;
    }

    private BaseDto createHook(@NonNull String projectId, @NonNull String repoId, ScmAccessTokenDto scmAccessTokenDto, String path, AzureEvent event)  {
        AzureWebhookDto hookData = generateHookData(scmAccessTokenDto.getAccessToken(),repoId,projectId, event);
        ResponseEntity<BaseDto> response =  restWrapper
                .sendBearerAuthRequest(path, HttpMethod.POST,
                        hookData, null,
                        BaseDto.class,
                        scmAccessTokenDto.getAccessToken());

        BaseDto hookDto = Objects.requireNonNull(response.getBody());
        validateResponse(hookDto);
        return hookDto;
    }

    private void validateResponse(BaseDto webhookGitLabDto) {
        if(webhookGitLabDto == null || StringUtils.isEmpty(webhookGitLabDto.getId())){
            log.error(RestWrapper.WEBHOOK_CREATE_FAILURE);
            throw new ScmException(RestWrapper.WEBHOOK_CREATE_FAILURE);
        }
    }

    @Override
    public void deleteWebhook(@NonNull String orgId, @NonNull String repoId,
                              @NonNull String webhookId) {

        ScmAccessTokenDto scmAccessTokenDto = dataStoreService.getSCMOrgToken(getBaseDbKey(), orgId);
        
        List<String> webhookIds = BaseDto.splitId(webhookId);

        for (String currWebhookId:webhookIds) {
            deleteWebhook(orgId, currWebhookId, scmAccessTokenDto);
        }
        dataStoreService.updateWebhook(repoId, scmAccessTokenDto, null, false);
    }

    private void deleteWebhook(@NonNull String orgId, @NonNull String webhookId, ScmAccessTokenDto scmAccessTokenDto) {
        String path = String.format(URL_DELETE_WEBHOOK, orgId, webhookId);

        try {
            restWrapper.sendBearerAuthRequest(path, HttpMethod.DELETE, null, null,
                    WebhookGitLabDto.class,
                    scmAccessTokenDto.getAccessToken());
        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode().equals(HttpStatus.NOT_FOUND)) {
                log.error("Webhook not found: {}", ex.getMessage());
                throw new ScmException(RestWrapper.WEBHOOK_DELETE_FAILURE);
            }
            throw new ScmException(RestWrapper.GENERAL_RUNTIME_EXCEPTION);
        }
    }


    @Override
    public CxFlowConfigDto getCxFlowConfiguration(@NonNull String orgId) {
        //TODO
        return null;
    }

    private List<AzureWebhookDto> getOrganizationCxFlowHooks(@NonNull String orgId,
                                                                                 @NonNull String accessToken){
        String path = String.format(URL_GET_WEBHOOKS, orgId);  
        
        ResponseEntity<WebhookListAzureDto> response =  restWrapper.sendBearerAuthRequest(path, HttpMethod.GET,
                null, null,
                WebhookListAzureDto.class, accessToken);
        
        WebhookListAzureDto allHooks =(response.getBody());

        List<AzureWebhookDto> cxFlowHooks = new LinkedList<>();
        for (int i=0; i< allHooks.getCount() && allHooks.getWebhooks()!= null; i++) {

            AzureWebhookDto webhookDto = allHooks.getWebhooks().get(i);
            if (webhookDto != null  &&  webhookDto.getConsumerInputs()!=null && webhookDto.getConsumerInputs().getUrl().contains(cxFlowWebHook) && webhookDto.isPushOrPull())
                cxFlowHooks.add(webhookDto);
        }
        return cxFlowHooks;
    }

    private AzureWebhookDto generateHookData(String token, String repoId, String projectId, AzureEvent event)  {
        //String auth = Base64.getEncoder().encodeToString(token.getBytes());
       
        String targetAppUrl =  String.format(cxFlowWebHook, event.getHookUrl());
                
        AzureWebhookDto.ConsumerInputs consumerInputs = AzureWebhookDto.ConsumerInputs.builder()
                //.httpHeaders("Authorization: Bearer ".concat(auth))
                .url(targetAppUrl)
                .build();
        PublisherInputs publisherInput = PublisherInputs.builder()
                .projectId(projectId)
                .repository(repoId)
                .build();

        return AzureWebhookDto.builder()
                .consumerActionId("httpRequest")
                .consumerId("webHooks")
                .consumerInputs(consumerInputs)
                .eventType(event.getType())
                .publisherId("tfs")
                .publisherInputs(publisherInput)
                .resourceVersion("1.0")
                .scope(1)
                .build();
    }
}
