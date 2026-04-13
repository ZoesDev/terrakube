package io.terrakube.api.plugin.vcs.provider.forgejo;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ForgejoToken {
    private String access_token;
    private String token_type;
    private String scope;
}
