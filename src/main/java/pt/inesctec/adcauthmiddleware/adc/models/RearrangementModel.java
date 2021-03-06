package pt.inesctec.adcauthmiddleware.adc.models;

import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import pt.inesctec.adcauthmiddleware.adc.RearrangementConstants;

/**
 * Models a rearrangement response but only with the ID fields.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RearrangementModel {
    @JsonProperty(RearrangementConstants.REPERTOIRE_ID_FIELD)
    @NotNull
    private String repertoireId;

    @JsonProperty(RearrangementConstants.ID_FIELD)
    private String rearrangementId;

    public String getRepertoireId() {
        return repertoireId;
    }

    public void setRepertoireId(String repertoireId) {
        this.repertoireId = repertoireId;
    }

    public String getRearrangementId() {
        return rearrangementId;
    }

    public void setRearrangementId(String rearrangementId) {
        this.rearrangementId = rearrangementId;
    }
}
