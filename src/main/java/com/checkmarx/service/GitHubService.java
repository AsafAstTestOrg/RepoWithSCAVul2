package com.checkmarx.service;

import com.checkmarx.controller.exception.ScmException;
import com.checkmarx.dto.BaseDto;
import com.checkmarx.dto.IWebhookDto;
import com.checkmarx.dto.cxflow.CxFlowConfigDto;
import com.checkmarx.dto.datastore.*;
import com.checkmarx.dto.github.*;
import com.checkmarx.dto.web.OrganizationWebDto;
import com.checkmarx.dto.web.RepoWebDto;
import com.checkmarx.utils.AccessTokenManager;
import com.checkmarx.utils.RestWrapper;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service("github")
public class GitHubService extends AbstractScmService implements ScmService {
    
    private static final String URL_GENERATE_TOKEN = "https://github.com/login/oauth/access_token" +
            "?client_id=%s&client_secret=%s&code=%s";

    private static final String GITHUB_BASE_URL = "https://api.github.com";
    private static final String URL_GET_ORGANIZATIONS = GITHUB_BASE_URL + "/user/orgs";

    public static final String URL_GET_REPOS = "https://api.github" +
            ".com/orgs/%s/repos?type=all&per_page=100";
    
    private static final String URL_WEBHOOK_OPERATION = GITHUB_BASE_URL + "/repos/%s/%s/hooks";
    
    private static final String URL_DELETE_WEBHOOK = GITHUB_BASE_URL + "/repos/%s/%s/hooks/%s";

    private static final String URL_VALIDATE_TOKEN = GITHUB_BASE_URL + "/user";

    private static final String GIT_HUB_DB_KEY = "github.com";
    
    private static final String SCOPES = "repo,admin:repo_hook,read:org,read:user";

    private static final String INVALID_TOKEN = "Github token validation failure";

    public GitHubService(RestWrapper restWrapper, DataService dataStoreService, AccessTokenService tokenService) {
        super(restWrapper, dataStoreService, tokenService);
    }

    @Override
    public List<OrganizationWebDto> getOrganizations(@NonNull String authCode) {
        AccessTokenGithubDto tokenFromGitHubApi = generateAccessToken(authCode);
        TokenInfoDto tokenForSaving = toStandardTokenDto(tokenFromGitHubApi);
        long tokenId = tokenService.createTokenInfo(tokenForSaving);
        return getAndStoreOrganizations(tokenForSaving.getAccessToken(), tokenId);
    }

    @Override
    public List<RepoWebDto> getScmOrgRepos(@NonNull String orgId) {
        TokenInfoDto tokenInfo = tokenService.getTokenInfo(getBaseDbKey(), orgId);
        String accessToken = tokenInfo.getAccessToken();

        RepoGithubDto[] reposFromGitHub = getReposFromGitHub(orgId, accessToken);
        OrgReposDto reposForDataStore = getReposForDataStore(accessToken, reposFromGitHub, orgId);
        dataStoreService.updateScmOrgRepo(reposForDataStore);

        return getReposForWebClient(reposForDataStore);
    }

    @Override
    public BaseDto createWebhook(@NonNull String orgId, @NonNull String repoId) {
        TokenInfoDto tokenInfo = tokenService.getTokenInfo(getBaseDbKey(), orgId);

        IWebhookDto newWebhook = createWebhookInScm(orgId, repoId, tokenInfo.getAccessToken());
        validateWebhookDto(newWebhook);

        storeNewWebhook(orgId, repoId, newWebhook);

        return new BaseDto(newWebhook.getId());
    }

    @Override
    public void deleteWebhook(@NonNull String orgId, @NonNull String repoId,
                              @NonNull String deleteUrl) {
        String path = String.format(URL_DELETE_WEBHOOK, orgId, repoId, deleteUrl);
        super.deleteWebhook(orgId, repoId, path, WebhookGithubDto.class);
    }

    @Override
    public CxFlowConfigDto getCxFlowConfiguration(@NonNull String orgId) {
        AccessTokenManager accessTokenWrapper = new AccessTokenManager(getBaseDbKey(), orgId, dataStoreService);

        CxFlowConfigDto cxFlowConfigDto = getOrganizationSettings(orgId, accessTokenWrapper.getAccessTokenStr());
        validateCxFlowConfig(cxFlowConfigDto);
        return cxFlowConfigDto;
    }

    private IWebhookDto createWebhookInScm(String orgId, String repoId, String accessToken) {
        String path = String.format(URL_WEBHOOK_OPERATION, orgId, repoId);
        WebhookGithubDto webhookGithubDto = initWebhook();
        ResponseEntity<WebhookGithubDto> response = restWrapper
                .sendBearerAuthRequest(path, HttpMethod.POST,
                        webhookGithubDto, null,
                        WebhookGithubDto.class,
                        accessToken);
        return Objects.requireNonNull(response.getBody());
    }

    private OrgReposDto getReposForDataStore(String accessToken, RepoGithubDto[] reposFromGitHub, String orgId) {
        List<RepoDto> repos = Arrays.stream(reposFromGitHub)
                .map(toRepoForDataStore(orgId, accessToken))
                .collect(Collectors.toList());

        return OrgReposDto.builder()
                .orgIdentity(orgId)
                .scmUrl(getBaseDbKey())
                .repoList(repos)
                .build();
    }

    private Function<RepoGithubDto, RepoDto> toRepoForDataStore(String orgId, String accessToken) {
        return (RepoGithubDto repo) -> {
            RepoDto repoForDataStore = new RepoDto();
            repoForDataStore.setRepoIdentity(repo.getId());
            repoForDataStore.setName(repo.getName());

            try {
                WebhookGithubDto webhook = getRepositoryCxFlowWebhook(orgId, repo.getName(), accessToken);
                setWebhookRelatedFields(webhook, repoForDataStore);
            } catch (HttpClientErrorException ex) {
                if (ex.getStatusCode().equals(HttpStatus.NOT_FOUND)) {
                    log.info("User can't access repository '{}' webhook settings",
                            repo.getName());
                }
            }

            return repoForDataStore;
        };
    }

    private RepoGithubDto[] getReposFromGitHub(String orgId, String accessToken) {
        String url = String.format(URL_GET_REPOS, orgId);

        ResponseEntity<RepoGithubDto[]> response = restWrapper.sendBearerAuthRequest(
                url, HttpMethod.GET, null, null, RepoGithubDto[].class, accessToken);

        return Objects.requireNonNull(response.getBody());
    }

    private List<OrganizationWebDto> getAndStoreOrganizations(String accessToken, long tokenId) {
        List<OrganizationGithubDto> orgs = getUserOrgs(accessToken);
        List<OrgDto2> dataStoreOrgs = toDataStoreOrganizations(orgs, tokenId);
        dataStoreService.storeOrgs2(getBaseDbKey(), dataStoreOrgs);

        return toOrganizationsForWebClient(orgs);
    }

    private static List<OrganizationWebDto> toOrganizationsForWebClient(List<OrganizationGithubDto> gitHubOrgs) {
        return gitHubOrgs.stream()
                .map(gitHubOrg -> OrganizationWebDto.builder()
                        .id(gitHubOrg.getId())
                        .name(gitHubOrg.getName())
                        .build())
                .collect(Collectors.toList());
    }

    private List<OrgDto2> toDataStoreOrganizations(List<OrganizationGithubDto> gitHubOrgs, long tokenId) {
        return gitHubOrgs.stream()
                .map(gitHubOrg -> OrgDto2.builder()
                        .orgIdentity(gitHubOrg.getId())
                        .tokenId(tokenId)
                        .build())
                .collect(Collectors.toList());
    }

    private List<OrganizationGithubDto> getUserOrgs(String accessToken) {
        ResponseEntity<OrganizationGithubDto[]> response = restWrapper.sendBearerAuthRequest(URL_GET_ORGANIZATIONS,
                HttpMethod.GET, null, null, OrganizationGithubDto[].class, accessToken);
        return Arrays.asList(Objects.requireNonNull(response.getBody()));
    }

    private static TokenInfoDto toStandardTokenDto(AccessTokenGithubDto gitHubSpecificDto) {
        return TokenInfoDto.builder()
                .accessToken(gitHubSpecificDto.getAccessToken())
                .build();
    }

    private void validateCxFlowConfig(CxFlowConfigDto cxFlowConfigDto) {
        if(StringUtils.isAnyEmpty(cxFlowConfigDto.getScmAccessToken(), cxFlowConfigDto.getTeam(),
                                  cxFlowConfigDto.getCxgoToken())) {
            log.error("CxFlow configuration settings validation failure, missing data");
            throw new ScmException("CxFlow configuration settings validation failure, missing data");
        }
        try {
            restWrapper.sendBearerAuthRequest(URL_VALIDATE_TOKEN, HttpMethod.GET, null, null,
                                              CxFlowConfigDto.class,
                                              cxFlowConfigDto.getScmAccessToken());
        } catch (HttpClientErrorException ex) {
            log.error("{}: {}", INVALID_TOKEN, ex.getMessage());
            throw new ScmException(INVALID_TOKEN);
        }
        log.info("Github token validation passed successfully!");
    }

    private WebhookGithubDto initWebhook() {
        return  WebhookGithubDto.builder()
                .name("web")
                .config(WebhookGithubDto.Config.builder().contentType("json").url(getCxFlowUrl()).insecureSsl("0").secret("1234").build())
                .events(GithubEvent.getAllEventsList())
                .active(true)
                .build();
    }

    private WebhookGithubDto getRepositoryCxFlowWebhook(@NonNull String orgName, @NonNull String repoName,
                                                        @NonNull String accessToken) {
        WebhookGithubDto result = null;
        String path = String.format(URL_WEBHOOK_OPERATION, orgName, repoName);
        try {
            ResponseEntity<WebhookGithubDto[]> response = restWrapper
                    .sendBearerAuthRequest(path, HttpMethod.GET,
                            null, null,
                            WebhookGithubDto[].class, accessToken);

            List<WebhookGithubDto> webhooks = Arrays.asList(Objects.requireNonNull(response.getBody()));

            result = (WebhookGithubDto) getActiveHook(webhooks);
        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode().equals(HttpStatus.NOT_FOUND)) {
                log.info("User can't access repository '{}' webhook settings", repoName);
            }
        }
        return result;
    }

    /**
     * Exchanges a one-time OAuth code for an SCM access token using the SCM API.
     *
     * @param authCode given from FE application after OAuth authorization is passed successfully.
     * @return generated SCM access token.
     */
    private AccessTokenGithubDto generateAccessToken(String authCode) {
        ScmDto scmDto = dataStoreService.getScm(getBaseDbKey());

        String path = buildPathAccessToken(authCode, scmDto);
        AccessTokenGithubDto tokenResponse = sendAccessTokenRequest(path);
        log.info("Access token generated successfully");

        return tokenResponse;
    }

    private AccessTokenGithubDto sendAccessTokenRequest(String path) {
        ResponseEntity<AccessTokenGithubDto> response = restWrapper.sendRequest(path,
                HttpMethod.POST,
                null,
                null,
                AccessTokenGithubDto.class);

        AccessTokenGithubDto accessTokenDto = Objects.requireNonNull(
                response.getBody(), "Missing access token generation response.");

        if(!verifyAccessToken(accessTokenDto)){
            log.error(RestWrapper.GENERATE_ACCESS_TOKEN_FAILURE);
            throw new ScmException(RestWrapper.GENERATE_ACCESS_TOKEN_FAILURE);
        }
        return accessTokenDto;
    }

    private String buildPathAccessToken(String oAuthCode, ScmDto scmDto) {
        return String.format(URL_GENERATE_TOKEN, scmDto.getClientId(),
                             scmDto.getClientSecret(),
                             oAuthCode);
    }


    @Override
    public String getScopes() {
        return SCOPES;
    }

    @Override
    public String getBaseDbKey() {
        return GIT_HUB_DB_KEY;
    }


}
