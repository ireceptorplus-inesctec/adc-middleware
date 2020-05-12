package pt.inesctec.adcauthmiddleware.adc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import org.springframework.stereotype.Component;
import pt.inesctec.adcauthmiddleware.adc.models.AdcSearchRequest;
import pt.inesctec.adcauthmiddleware.adc.models.RearrangementIds;
import pt.inesctec.adcauthmiddleware.adc.models.RepertoireIds;
import pt.inesctec.adcauthmiddleware.adc.models.internal.AdcIdsResponse;
import pt.inesctec.adcauthmiddleware.config.AdcConfiguration;
import pt.inesctec.adcauthmiddleware.http.HttpFacade;
import pt.inesctec.adcauthmiddleware.http.HttpRequestBuilderFacade;
import pt.inesctec.adcauthmiddleware.utils.Utils;

@Component
public class AdcClient {

  private final AdcConfiguration adcConfig;

  public AdcClient(AdcConfiguration adcConfig) {
    this.adcConfig = adcConfig;
  }

  public InputStream getResource(String path) throws IOException, InterruptedException {
    final URI uri = this.getResourceServerPath(path);

    var request = new HttpRequestBuilderFacade().getJson(uri).build();

    return HttpFacade.makeExpectJsonAsStreamRequest(request);
  }

  public InputStream getRepertoireAsStream(String repertoireId)
      throws IOException, InterruptedException {
    final URI uri = this.getResourceServerPath("repertoire", repertoireId);

    var request = new HttpRequestBuilderFacade().getJson(uri).build();

    return HttpFacade.makeExpectJsonAsStreamRequest(request);
  }

  public InputStream getRearrangementAsStream(String rearrangementId)
      throws IOException, InterruptedException {
    final URI uri = this.getResourceServerPath("rearrangement", rearrangementId);

    var request = new HttpRequestBuilderFacade().getJson(uri).build();

    return HttpFacade.makeExpectJsonAsStreamRequest(request);
  }

  public RearrangementIds getRearrangement(String rearrangementId) throws Exception {
    final URI uri = this.getResourceServerPath("rearrangement", rearrangementId);
    var request = new HttpRequestBuilderFacade().getJson(uri).build();
    var rearrangements =
        HttpFacade.makeExpectJsonRequest(request, AdcIdsResponse.class).getRearrangements();

    listPostConditions(rearrangements);

    if (rearrangements.size() != 1) {
      throw new Exception(
          String.format(
              "Illegal response for rearrangement %s received, expected 1 rearrangement but got %d",
              rearrangementId, rearrangements.size()));
    }

    var rearrangement = rearrangements.get(0);
    Utils.assertNotNull(rearrangement.getRearrangementId());
    return rearrangement;
  }

  public InputStream searchRepertoiresAsStream(AdcSearchRequest adcRequest) throws Exception {
    Preconditions.checkArgument(adcRequest.isJsonFormat());

    var request = this.buildSearchRequest("repertoire", adcRequest);
    return HttpFacade.makeExpectJsonAsStreamRequest(request);
  }

  public InputStream searchRearrangementsAsStream(AdcSearchRequest adcRequest) throws Exception {
    Preconditions.checkArgument(adcRequest.isJsonFormat());

    var request = this.buildSearchRequest("rearrangement", adcRequest);
    return HttpFacade.makeExpectJsonAsStreamRequest(request);
  }

  public List<RepertoireIds> getRepertoireIds(AdcSearchRequest adcRequest) throws Exception {
    Preconditions.checkArgument(adcRequest.getFacets() == null);
    Preconditions.checkArgument(adcRequest.isJsonFormat());

    var request = this.buildSearchRequest("repertoire", adcRequest);
    var repertoires =
        HttpFacade.makeExpectJsonRequest(request, AdcIdsResponse.class).getRepertoires();

    return listPostConditions(repertoires);
  }

  public List<RearrangementIds> getRearrangementIds(AdcSearchRequest adcRequest) throws Exception {
    Preconditions.checkArgument(adcRequest.getFacets() == null);
    Preconditions.checkArgument(adcRequest.isJsonFormat());

    var request = this.buildSearchRequest("rearrangement", adcRequest);
    var rearrangements =
        HttpFacade.makeExpectJsonRequest(request, AdcIdsResponse.class).getRearrangements();

    return listPostConditions(rearrangements);
  }

  private HttpRequest buildSearchRequest(String path, AdcSearchRequest adcSearchRequest)
      throws JsonProcessingException {
    final URI uri = this.getResourceServerPath(path);
    return new HttpRequestBuilderFacade().postJson(uri, adcSearchRequest).expectJson().build();
  }

  private URI getResourceServerPath(String... parts) {
    final String basePath = adcConfig.getResourceServerUrl();
    return Utils.buildUrl(basePath, parts);
  }

  private static <T> List<T> listPostConditions(List<T> resources) throws Exception {
    Utils.assertNotNull(resources);
    Utils.jaxValidateList(resources);

    return resources;
  }
}
