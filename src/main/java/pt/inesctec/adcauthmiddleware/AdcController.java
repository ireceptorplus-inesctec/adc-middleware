package pt.inesctec.adcauthmiddleware;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import pt.inesctec.adcauthmiddleware.adc.AdcClient;
import pt.inesctec.adcauthmiddleware.adc.AdcUtils;
import pt.inesctec.adcauthmiddleware.adc.models.AdcSearchRequest;
import pt.inesctec.adcauthmiddleware.db.DbRepository;
import pt.inesctec.adcauthmiddleware.uma.UmaClient;
import pt.inesctec.adcauthmiddleware.uma.UmaFlow;
import pt.inesctec.adcauthmiddleware.uma.exceptions.TicketException;
import pt.inesctec.adcauthmiddleware.uma.exceptions.UmaFlowException;
import pt.inesctec.adcauthmiddleware.uma.models.UmaResource;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@RestController
public class AdcController {
  private static org.slf4j.Logger Logger = LoggerFactory.getLogger(AdcController.class);

  @Autowired private AdcClient adcClient;
  @Autowired private DbRepository dbRepository;
  @Autowired private UmaFlow umaFlow;
  @Autowired private UmaClient umaClient;

  private static ResponseEntity<HttpError> buildError(HttpStatus status, String msg) {
    return new ResponseEntity<>(new HttpError(status, msg), status);
  }

  private static ResponseEntity<HttpError> buildError(HttpStatus status, String msg, Map<String, String> headers) {
    var responseHeaders = new HttpHeaders();
    headers.forEach(responseHeaders::set);

    return new ResponseEntity<>(new HttpError(status, msg), responseHeaders, status);
  }

  @Autowired
  public AdcController(DbRepository dbRepository) throws Exception {
    //    cacheRepository.synchronize();
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<HttpError> badInputHandler(HttpMessageNotReadableException e) {
    Logger.info("User input JSON error: {}", e.getMessage());
    var msg = Utils.getNestedExceptionMessage(e); // TODO hide internals from response
    return AdcController.buildError(HttpStatus.BAD_REQUEST, "Invalid JSON: " + msg);
  }

  @ExceptionHandler(TicketException.class)
  public ResponseEntity<HttpError> ticketHandler(TicketException e) {
    var headers = ImmutableMap.of(HttpHeaders.WWW_AUTHENTICATE, e.buildAuthenticateHeader());
    return AdcController.buildError(HttpStatus.UNAUTHORIZED, "UMA permissions ticket emitted", headers);
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<HttpError> statusException(ResponseStatusException e) {
    Logger.debug("Stacktrace: ", e);
    return AdcController.buildError(e.getStatus(), e.getReason());
  }

  @ResponseStatus(value = HttpStatus.UNAUTHORIZED)
  @ExceptionHandler(UmaFlowException.class)
  public HttpError umaFlowHandler(Exception e) {
    Logger.info("Uma flow access error {}", e.getMessage());
    Logger.debug("Stacktrace: ", e);

    return new HttpError(HttpStatus.UNAUTHORIZED);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<HttpError> internalErrorHandler(Exception e) {
    Logger.error("Internal error occurred: ", e);
    return AdcController.buildError(HttpStatus.UNAUTHORIZED, null);
  }

  @RequestMapping(
      value = "/repertoire/{repertoireId}",
      method = RequestMethod.GET,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public String repertoire(HttpServletRequest request, @PathVariable String repertoireId)
      throws Exception {
    var umaId = this.dbRepository.getRepertoireUmaId(repertoireId);
    exactUmaFlow(
        request,
        umaId,
        "non-existing repertoire in cache " + repertoireId,
        AdcUtils.SEQUENCE_UMA_SCOPE);

    return this.adcClient.getRepertoireAsString(repertoireId);
  }

  @RequestMapping(
      value = "/rearrangement/{rearrangementId}",
      method = RequestMethod.GET,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public String rearrangement(HttpServletRequest request, @PathVariable String rearrangementId)
      throws Exception {
    var umaId = this.dbRepository.getRearrangementUmaId(rearrangementId);
    exactUmaFlow(
        request,
        umaId,
        "non-existing rearrangement in cache " + rearrangementId,
        AdcUtils.SEQUENCE_UMA_SCOPE);

    return this.adcClient.getRearrangementAsString(rearrangementId);
  }

  @RequestMapping(
      value = "/repertoire",
      method = RequestMethod.GET,
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public Object repertoire_search(
      HttpServletRequest request, @RequestBody AdcSearchRequest adcSearch) throws Exception {
    AdcController.validateAdcSearch(adcSearch);

    var bearer = AdcController.getBearer(request);
    if (bearer == null) {
      var idsQuery = adcSearch.queryClone().addFields(AdcUtils.REPERTOIRE_STUDY_ID_FIELD);
      var umaResources =
          this.adcClient.getRepertoireIds(idsQuery)
              .stream()
              .map(e -> this.dbRepository.getStudyUmaId(e.getStudyId()))
              .filter(Objects::nonNull)
              .collect(Collectors.toSet())
              .stream()
              .map(id -> new UmaResource(id, AdcUtils.SEQUENCE_UMA_SCOPE))
              .toArray(UmaResource[]::new);

      this.umaFlow.noRptToken(umaResources); // will throw
    }

    var tokenResources = this.umaClient.introspectToken(bearer);

    return tokenResources;
  }

  private static void validateAdcSearch(AdcSearchRequest adcSearch) {
    if (adcSearch.getFields() != null && adcSearch.getFacets() != null) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Can't use 'fields' and 'facets' at the same time in request");
    }

    if (! adcSearch.isJsonFormat() || adcSearch.getFacets() != null ) {
      Logger.error("Not implemented");
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Not implemented");
    }
  }

  // TODO add security
  @RequestMapping(value = "/synchronize", method = RequestMethod.POST)
  public void synchronize() throws Exception {
    this.dbRepository.synchronize();
  }

  private void exactUmaFlow(
      HttpServletRequest request, String umaId, String errorMsg, String... umaScopes)
      throws Exception {
    Preconditions.checkArgument(umaScopes.length > 0);

    if (umaId == null) {
      Logger.info("User tried accessing non-existing resource {}", errorMsg);
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
    }

    var bearer = AdcController.getBearer(request);
    var umaResource = new UmaResource(umaId, umaScopes); // repertoire is access level 3
    this.umaFlow.exactMatchFlow(bearer, umaResource);
  }

  private static String getBearer(HttpServletRequest request) {
    var auth = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (auth == null) {
      return null;
    }

    if (!auth.startsWith("Bearer ")) {
      return null;
    }

    return auth.replace("Bearer ", "");
  }
}
