package pt.inesctec.adcauthmiddleware.adc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Preconditions;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import pt.inesctec.adcauthmiddleware.Utils;
import pt.inesctec.adcauthmiddleware.adc.models.AdcSearchRequest;
import pt.inesctec.adcauthmiddleware.adc.models.RearrangementIds;
import pt.inesctec.adcauthmiddleware.adc.models.RepertoireIds;
import pt.inesctec.adcauthmiddleware.adc.models.internal.AdcIdsResponse;
import pt.inesctec.adcauthmiddleware.config.AdcConfiguration;
import pt.inesctec.adcauthmiddleware.http.HttpFacade;
import pt.inesctec.adcauthmiddleware.http.HttpRequestBuilderFacade;

import java.io.IOException;
import java.net.URI;
import java.util.List;

@Component
public class AdcClient {
  private static org.slf4j.Logger Logger = LoggerFactory.getLogger(AdcClient.class);

  private final AdcConfiguration adcConfig;

  public AdcClient(AdcConfiguration adcConfig) {
    this.adcConfig = adcConfig;
  }

  public String getRepertoireAsString(String repertoireId) throws IOException, InterruptedException {
    final URI uri = this.getResourceServerPath("repertoire", repertoireId);

    var request = new HttpRequestBuilderFacade()
        .getJson(uri)
        .build();

    return HttpFacade.makeExpectJsonStringRequest(request);
  }

  public String getRearrangementAsString(String rearrangementId) throws IOException, InterruptedException {
    final URI uri = this.getResourceServerPath("rearrangement", rearrangementId);

    var request = new HttpRequestBuilderFacade()
        .getJson(uri)
        .build();

    return HttpFacade.makeExpectJsonStringRequest(request);
  }

  public List<RepertoireIds> getRepertoireIds(AdcSearchRequest adcRequest) throws Exception {
    Preconditions.checkArgument(adcRequest.getFacets() == null);
    Preconditions.checkArgument(adcRequest.isJsonFormat());

    final URI uri = this.getResourceServerPath("repertoire");
    var request = new HttpRequestBuilderFacade()
        .postJson(uri, adcRequest)
        .build();

    var repertoires = HttpFacade.makeExpectJsonRequest(request, AdcIdsResponse.class)
          .getRepertoires();

    Utils.assertNotNull(repertoires);
    Utils.jaxValidateList(repertoires);

    return repertoires;
  }

  private URI getResourceServerPath(String... parts) {
    final String basePath = adcConfig.getResourceServerUrl();
    return Utils.buildUrl(basePath, parts);
  }


  public List<RearrangementIds> getRearrangementIds(AdcSearchRequest adcRequest) throws Exception {
    Preconditions.checkArgument(adcRequest.getFacets() == null);
    Preconditions.checkArgument(adcRequest.isJsonFormat());

    final URI uri = this.getResourceServerPath("rearrangement");
    var request = new HttpRequestBuilderFacade()
        .postJson(uri, adcRequest)
        .build();

    var rearrangements = HttpFacade.makeExpectJsonRequest(request, AdcIdsResponse.class)
        .getRearrangements();

    Utils.assertNotNull(rearrangements);
    Utils.jaxValidateList(rearrangements);

    return rearrangements;
  }
}
