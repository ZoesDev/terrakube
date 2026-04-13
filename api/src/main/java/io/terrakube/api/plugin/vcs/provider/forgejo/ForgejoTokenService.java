package io.terrakube.api.plugin.vcs.provider.forgejo;

import io.terrakube.api.plugin.vcs.provider.GetAccessToken;
import io.terrakube.api.plugin.vcs.provider.exception.TokenException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Slf4j
@Service
public class ForgejoTokenService implements GetAccessToken<ForgejoToken> {

    @Override
    public ForgejoToken getAccessToken(String clientId, String clientSecret, String tempCode, String callback,
            String endpoint) throws TokenException {
        if (endpoint == null || endpoint.isEmpty()) {
            throw new TokenException("400", "Forgejo/Gitea requires a custom endpoint (self-hosted URL)");
        }

        WebClient client = WebClient.builder()
                .baseUrl(endpoint)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create().proxyWithSystemProperties()))
                .build();

        log.info("Exchanging OAuth code for Forgejo access token at {}", endpoint);

        ForgejoToken token = client.post()
                .uri(uriBuilder -> uriBuilder.path("/login/oauth/access_token")
                        .queryParam("client_id", clientId)
                        .queryParam("client_secret", clientSecret)
                        .queryParam("code", tempCode)
                        .queryParam("grant_type", "authorization_code")
                        .build())
                .retrieve()
                .bodyToMono(ForgejoToken.class)
                .block();

        if (token != null && token.getAccess_token() != null) {
            return token;
        }
        throw new TokenException("500", "Unable to obtain Forgejo/Gitea access token");
    }
}
