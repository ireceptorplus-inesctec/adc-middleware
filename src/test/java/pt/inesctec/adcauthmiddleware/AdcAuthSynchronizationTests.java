package pt.inesctec.adcauthmiddleware;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pt.inesctec.adcauthmiddleware.adc.AdcConstants;
import pt.inesctec.adcauthmiddleware.utils.ModelFactory;
import pt.inesctec.adcauthmiddleware.utils.TestCollections;
import pt.inesctec.adcauthmiddleware.utils.TestConstants;
import pt.inesctec.adcauthmiddleware.utils.UmaWireMocker;
import pt.inesctec.adcauthmiddleware.utils.WireMocker;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

public class AdcAuthSynchronizationTests extends TestBase {
  private static WireMockServer umaMock = new WireMockRule(options().port(TestConstants.UMA_PORT));

  @BeforeAll
  public static void init() {
    backendMock.start();
    umaMock.start();
  }

  @BeforeEach
  public void reset() {
    backendMock.resetMappings();
    umaMock.resetMappings();
  }

  @Test
  public void testEmptySync() {
    wireSyncRepertoires();

    String accessToken = umaInit();
    UmaWireMocker.wireListResources(umaMock, accessToken);

    synchronize();

    this.assertRepertoireNotFound("1");
  }

  @Test
  public void testCleanSlateSync() {
    var repertoire = ModelFactory.buildRepertoire("1");
    wireSyncRepertoires(repertoire);

    String accessToken = umaInit();
    UmaWireMocker.wireListResources(umaMock, accessToken);
    var umaId = UmaWireMocker.wireCreateResource(umaMock, repertoire, accessToken);

    synchronize();

    assertRepertoireTicketRequest(repertoire, accessToken, umaId);
  }

  @Test
  public void testAdditiveSync() {
    var repertoire1 = ModelFactory.buildRepertoire("1");
    wireSyncRepertoires(repertoire1);
    String accessToken = umaInit();
    UmaWireMocker.wireListResources(umaMock, accessToken);
    var umaId1 = UmaWireMocker.wireCreateResource(umaMock, repertoire1, accessToken);
    synchronize();
    assertRepertoireTicketRequest(repertoire1, accessToken, umaId1);

    umaMock.resetMappings();
    backendMock.resetMappings();

    var repertoire2 = ModelFactory.buildRepertoire("2");
    wireSyncRepertoires(repertoire1, repertoire2);
    accessToken = umaInit();
    UmaWireMocker.wireListResources(umaMock, accessToken, umaId1);
    var name = "name 123";
    UmaWireMocker.wireGetResource(umaMock, umaId1, name, TestConstants.UMA_ALL_SCOPES, accessToken);
    var umaId2 = UmaWireMocker.wireCreateResource(umaMock, repertoire2, accessToken);
    synchronize();
    assertRepertoireTicketRequest(repertoire2, accessToken, umaId2);
    assertRepertoireTicketRequest(repertoire1, accessToken, umaId1);
  }

  private void assertRepertoireNotFound(String repertoireId) {
    this.requests.getJsonMap(
        this.buildMiddlewareUrl(TestConstants.REPERTOIRE_PATH_FRAGMENT, repertoireId), 404);
  }


  private void assertRepertoireTicketRequest(Map<String, Object> repertoire, String accessToken, String umaId) {
    var repertoireId =
        TestCollections.getString(repertoire, AdcConstants.REPERTOIRE_REPERTOIRE_ID_FIELD);
    var ticket =
        UmaWireMocker.wireGetTicket(
            umaMock,
            accessToken,
            ModelFactory.buildUmaResource(
                umaId,
                TestConstants.UMA_ALL_SCOPES)); // repertoires have all the scopes

    this.requests.getJsonUmaTicket(
        this.buildMiddlewareUrl(TestConstants.REPERTOIRE_PATH_FRAGMENT, repertoireId), ticket);
  }

  private void synchronize() {
    this.requests.postEmpty(
        this.buildMiddlewareUrl(TestConstants.SYNCHRONIZE_PATH_FRAGMENT),
        TestConstants.SYNC_PASSWORD,
        200);
  }

  private String umaInit() {
    UmaWireMocker.wireUmaWellKnown(umaMock);
    return UmaWireMocker.wireTokenEndpoint(umaMock);
  }


  private void wireSyncRepertoires(Object ... repertoires) {
    var searchRequest =
        ModelFactory.buildAdcFields(
            AdcConstants.REPERTOIRE_REPERTOIRE_ID_FIELD,
            AdcConstants.REPERTOIRE_STUDY_ID_FIELD,
            AdcConstants.REPERTOIRE_STUDY_TITLE_FIELD);
    var repertoiresResponse = ModelFactory.buildRepertoiresDocumentWithInfo(repertoires);
    WireMocker.wirePostJson(
        backendMock, TestConstants.REPERTOIRE_PATH, 200, repertoiresResponse, searchRequest);
  }

}