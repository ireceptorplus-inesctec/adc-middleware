package pt.inesctec.adcauthmiddleware.uma.dto.internal;

import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Models a UMA PAT token, used by the client internaly.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AccessToken {

    @JsonProperty("access_token")
    @NotNull
    private String accessToken;

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }
}
