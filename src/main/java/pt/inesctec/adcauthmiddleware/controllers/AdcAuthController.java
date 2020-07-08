package pt.inesctec.adcauthmiddleware.controllers;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServletRequest;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import pt.inesctec.adcauthmiddleware.HttpException;
import pt.inesctec.adcauthmiddleware.adc.AdcClient;
import pt.inesctec.adcauthmiddleware.adc.AdcConstants;
import pt.inesctec.adcauthmiddleware.adc.jsonfilter.ResourceJsonMapper;
import pt.inesctec.adcauthmiddleware.adc.jsonfilter.SimpleJsonFilter;
import pt.inesctec.adcauthmiddleware.adc.models.AdcException;
import pt.inesctec.adcauthmiddleware.adc.models.AdcSearchRequest;
import pt.inesctec.adcauthmiddleware.config.AppConfig;
import pt.inesctec.adcauthmiddleware.config.csv.CsvConfig;
import pt.inesctec.adcauthmiddleware.config.csv.FieldClass;
import pt.inesctec.adcauthmiddleware.db.DbRepository;
import pt.inesctec.adcauthmiddleware.uma.UmaClient;
import pt.inesctec.adcauthmiddleware.uma.UmaFlow;
import pt.inesctec.adcauthmiddleware.uma.exceptions.TicketException;
import pt.inesctec.adcauthmiddleware.uma.exceptions.UmaFlowException;
import pt.inesctec.adcauthmiddleware.uma.models.UmaResource;
import pt.inesctec.adcauthmiddleware.utils.CollectionsUtils;
import pt.inesctec.adcauthmiddleware.utils.Delayer;
import pt.inesctec.adcauthmiddleware.utils.ThrowingFunction;
import pt.inesctec.adcauthmiddleware.utils.ThrowingProducer;

@RestController
public class AdcAuthController {
  private static Set<String> EmptySet = ImmutableSet.of();
  private static List<UmaResource> EmptyResources = ImmutableList.of();
  private static org.slf4j.Logger Logger = LoggerFactory.getLogger(AdcAuthController.class);
  private static final PasswordEncoder PasswordEncoder = new BCryptPasswordEncoder();

  private AppConfig appConfig;
  private final Delayer repertoiresDelayer;
  private final Delayer rearrangementsDelayer;
  @Autowired private AdcClient adcClient;
  @Autowired private DbRepository dbRepository;
  @Autowired private UmaFlow umaFlow;
  @Autowired private UmaClient umaClient;
  @Autowired private CsvConfig csvConfig;

  @Autowired
  public AdcAuthController(AppConfig appConfig) {
    this.appConfig = appConfig;

    this.repertoiresDelayer = new Delayer(appConfig.getRequestDelaysPoolSize());
    this.rearrangementsDelayer = new Delayer(appConfig.getRequestDelaysPoolSize());
  }

  private static final Pattern JsonErrorPattern =
      Pattern.compile(".*line: (\\d+), column: (\\d+).*");

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<String> badInputHandler(HttpMessageNotReadableException e) {
    Logger.info("User input JSON error: {}", e.getMessage());

    // TODO improve returned error MSG
    var msg = "";
    var matcher = JsonErrorPattern.matcher(e.getMessage());
    if (matcher.find()) {
      msg =
          String.format(
              " (malformed or invalid ADC schema) at line %s, column %s",
              matcher.group(1), matcher.group(2));
    }

    var cause = e.getRootCause();
    if (cause instanceof AdcException) {
      msg += ": " + cause.getMessage();
    }

    return SpringUtils.buildJsonErrorResponse(HttpStatus.BAD_REQUEST, "Invalid input JSON" + msg);
  }

  @ExceptionHandler(HttpException.class)
  public ResponseEntity<String> httpExceptionForward(HttpException e) {
    Logger.debug("Stacktrace: ", e);
    return SpringUtils.buildResponse(e.statusCode, e.errorMsg, e.contentType.orElse(null));
  }

  @ExceptionHandler(TicketException.class)
  public ResponseEntity<String> ticketHandler(TicketException e) {
    var headers = ImmutableMap.of(HttpHeaders.WWW_AUTHENTICATE, e.buildAuthenticateHeader());
    return SpringUtils.buildJsonErrorResponse(
        HttpStatus.UNAUTHORIZED, "UMA permissions ticket emitted", headers);
  }

  @ExceptionHandler(UmaFlowException.class)
  public ResponseEntity<String> umaFlowHandler(Exception e) {
    Logger.info("Uma flow access error {}", e.getMessage());
    Logger.debug("Stacktrace: ", e);

    return SpringUtils.buildJsonErrorResponse(HttpStatus.UNAUTHORIZED, null);
  }

  @ExceptionHandler(SyncException.class)
  public ResponseEntity<String> synchronizeErrorHandler(SyncException e) {
    Logger.info("Synchronize: {}", e.getMessage());
    return SpringUtils.buildJsonErrorResponse(HttpStatus.UNAUTHORIZED, null);
  }

  @ExceptionHandler(Throwable.class)
  public ResponseEntity<String> internalErrorHandler(Exception e) {
    Logger.error("Internal error occurred: {}", e.getMessage());
    Logger.info("Stacktrace: ", e);
    return SpringUtils.buildJsonErrorResponse(HttpStatus.UNAUTHORIZED, null);
  }

  @RequestMapping(
      value = "/repertoire/{repertoireId}",
      method = RequestMethod.GET,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<StreamingResponseBody> repertoire(
      HttpServletRequest request, @PathVariable String repertoireId) throws Exception {
    var bearer = SpringUtils.getBearer(request);
    if (bearer == null) {
      var umaId = this.dbRepository.getRepertoireUmaId(repertoireId);
      if (umaId == null) {
        Logger.info("User tried accessing non-existing repertoire with ID {}", repertoireId);
        throw SpringUtils.buildHttpException(HttpStatus.NOT_FOUND, "Not found");
      }

      var umaScopes = this.csvConfig.getUmaScopes(FieldClass.REPERTOIRE);
      throw this.umaFlow.noRptToken(ImmutableList.of(umaId), umaScopes);
    }

    var tokenResources = this.umaClient.introspectToken(bearer);
    var fieldMapper =
        this.buildUmaFieldMapper(tokenResources, FieldClass.REPERTOIRE)
            .compose(this.dbRepository::getStudyUmaId);

    return buildFilteredJsonResponse(
        AdcConstants.REPERTOIRE_STUDY_ID_FIELD,
        AdcConstants.REPERTOIRE_RESPONSE_FILTER_FIELD,
        fieldMapper,
        () -> this.adcClient.getRepertoireAsStream(repertoireId));
  }

  @RequestMapping(
      value = "/rearrangement/{rearrangementId}",
      method = RequestMethod.GET,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<StreamingResponseBody> rearrangement(
      HttpServletRequest request, @PathVariable String rearrangementId) throws Exception {

    var bearer = SpringUtils.getBearer(request);
    if (bearer == null) {
      String umaId = this.dbRepository.getRearrangementUmaId(rearrangementId);
      if (umaId == null) {
        Logger.info("User tried accessing non-existing rearrangement with ID {}", rearrangementId);
        throw SpringUtils.buildHttpException(HttpStatus.NOT_FOUND, "Not found");
      }

      var umaScopes = this.csvConfig.getUmaScopes(FieldClass.REARRANGEMENT);
      throw this.umaFlow.noRptToken(ImmutableList.of(umaId), umaScopes);
    }

    var tokenResources = this.umaClient.introspectToken(bearer);
    var fieldMapper =
        this.buildUmaFieldMapper(tokenResources, FieldClass.REARRANGEMENT)
            .compose(this.dbRepository::getRepertoireUmaId);

    return buildFilteredJsonResponse(
        AdcConstants.REARRANGEMENT_REPERTOIRE_ID_FIELD,
        AdcConstants.REARRANGEMENT_RESPONSE_FILTER_FIELD,
        fieldMapper,
        () -> this.adcClient.getRearrangementAsStream(rearrangementId));
  }

  @RequestMapping(
      value = "/repertoire",
      method = RequestMethod.POST,
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<StreamingResponseBody> repertoireSearch(
      HttpServletRequest request, @RequestBody AdcSearchRequest adcSearch) throws Exception {
    this.validateAdcSearch(adcSearch, FieldClass.REPERTOIRE);

    Set<String> umaScopes = this.getAdcRequestUmaScopes(adcSearch, FieldClass.REPERTOIRE);
    var umaResources =
        this.adcQueryUmaFlow(
            request, adcSearch, this::getRepertoireStudyIds, repertoiresDelayer, umaScopes);

    if (adcSearch.isFacetsSearch()) {
      return facetsRequest(
          adcSearch,
          AdcConstants.REPERTOIRE_STUDY_ID_FIELD,
              umaResources, umaScopes, (umaId) -> CollectionsUtils.toSet(this.dbRepository.getUmaStudyId(umaId)),
              this.adcClient::searchRepertoiresAsStream
      );
    } else {
      var fieldMapper =
          this.adcSearchFlow(
              adcSearch,
                  AdcConstants.REPERTOIRE_STUDY_ID_FIELD, FieldClass.REPERTOIRE,
                  umaResources);

      return buildFilteredJsonResponse(
          AdcConstants.REPERTOIRE_STUDY_ID_FIELD,
          AdcConstants.REPERTOIRE_RESPONSE_FILTER_FIELD,
          fieldMapper.compose(this.dbRepository::getStudyUmaId),
          () -> this.adcClient.searchRepertoiresAsStream(adcSearch));
    }
  }

  @RequestMapping(
      value = "/rearrangement",
      method = RequestMethod.POST,
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<StreamingResponseBody> rearrangementSearch(
      HttpServletRequest request, @RequestBody AdcSearchRequest adcSearch) throws Exception {
    this.validateAdcSearch(adcSearch, FieldClass.REARRANGEMENT);

    Set<String> umaScopes = this.getAdcRequestUmaScopes(adcSearch, FieldClass.REARRANGEMENT);
    var umaResources =
        this.adcQueryUmaFlow(
            request,
            adcSearch,
            this::getRearrangementsRepertoireIds,
            rearrangementsDelayer,
            umaScopes);

    if (adcSearch.isFacetsSearch()) {
      return facetsRequest(
          adcSearch,
          AdcConstants.REARRANGEMENT_REPERTOIRE_ID_FIELD,
              umaResources, umaScopes, this.dbRepository::getUmaRepertoireIds,
              this.adcClient::searchRearrangementsAsStream
      );
    } else {
      var fieldMapper =
          this.adcSearchFlow(
              adcSearch,
                  AdcConstants.REARRANGEMENT_REPERTOIRE_ID_FIELD, FieldClass.REARRANGEMENT,
                  umaResources);

      return buildFilteredJsonResponse(
          AdcConstants.REARRANGEMENT_REPERTOIRE_ID_FIELD,
          AdcConstants.REARRANGEMENT_RESPONSE_FILTER_FIELD,
          fieldMapper.compose(this.dbRepository::getRepertoireUmaId),
          () -> this.adcClient.searchRearrangementsAsStream(adcSearch));
    }
  }

  @RequestMapping(value = "/public_fields", method = RequestMethod.GET)
  public Map<FieldClass, Set<String>> publicFields() {

    var map = new HashMap<FieldClass, Set<String>>();
    for (var adcClass : FieldClass.values()) {
      var fields = this.csvConfig.getPublicFields(adcClass);
      map.put(adcClass, fields);
    }

    return map;
  }

  @RequestMapping(value = "/synchronize", method = RequestMethod.POST)
  public Map<String, Object> synchronize(HttpServletRequest request) throws Exception {
    String bearer = SpringUtils.getBearer(request);
    if (bearer == null) {
      throw new SyncException("Invalid user credential format");
    }

    if (!PasswordEncoder.matches(bearer, appConfig.getSynchronizePasswordHash())) {
      throw new SyncException("Invalid user credential");
    }

    if (!this.dbRepository.synchronize()) {
      throw SpringUtils.buildHttpException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "One or more DB or UMA resources failed to synchronize, check logs");
    }

    this.rearrangementsDelayer.reset();
    this.repertoiresDelayer.reset();

    return SpringUtils.buildStatusMessage(200, null);
  }

  private List<String> getRearrangementsRepertoireIds(AdcSearchRequest idsQuery) throws Exception {
    return this.adcClient.getRearrangementRepertoireIds(idsQuery).stream()
        .map(id -> this.dbRepository.getRepertoireUmaId(id))
        .collect(Collectors.toList());
  }

  private List<String> getRepertoireStudyIds(AdcSearchRequest idsQuery) throws Exception {
    return this.adcClient.getRepertoireStudyIds(idsQuery).stream()
        .map(id -> this.dbRepository.getStudyUmaId(id))
        .collect(Collectors.toList());
  }

  private List<UmaResource> adcQueryUmaFlow(
      HttpServletRequest request,
      AdcSearchRequest adcSearch,
      ThrowingFunction<AdcSearchRequest, Collection<String>, Exception> umaIdsProducer,
      Delayer delayer,
      Set<String> umaScopes)
      throws Exception {
    var startTime = LocalDateTime.now();

    // empty scopes means public access
    if (umaScopes.isEmpty()) {
      return EmptyResources;
    }

    var bearer = SpringUtils.getBearer(request);
    if (bearer != null) {
      return this.umaClient.introspectToken(bearer);
    }

    Collection<String> umaIds = umaIdsProducer.apply(adcSearch);
    delayer.delay(startTime);

    if (umaIds.isEmpty()) {
      // when no resources return, just err
      throw SpringUtils.buildHttpException(HttpStatus.UNAUTHORIZED, null);
    }

    throw this.umaFlow.noRptToken(umaIds, umaScopes);
  }

  private Set<String> getAdcRequestUmaScopes(AdcSearchRequest adcSearch, FieldClass fieldClass) {
    final Set<String> requestedFields =
        adcSearch.isFacetsSearch()
            ? Set.of(adcSearch.getFacets())
            : getRegularSearchRequestedFields(adcSearch, fieldClass);
    final Set<String> filtersFields = adcSearch.getFiltersFields();
    final Set<String> allConsideredFields = Sets.union(requestedFields, filtersFields);

    return this.csvConfig.getUmaScopes(fieldClass, allConsideredFields);
  }

  private ResponseEntity<StreamingResponseBody> facetsRequest(
          AdcSearchRequest adcSearch,
          String resourceId,
          Collection<UmaResource> umaResources,
          Set<String> umaScopes,
          Function<String, Set<String>> umaIdGetter,
          ThrowingFunction<AdcSearchRequest, InputStream, Exception> adcRequest)
      throws Exception {

    boolean filterResponse = false;
    if (!umaScopes.isEmpty()) { // non public facets field
      var resourceIds =
          umaResources.stream()
              .filter(resource -> !Sets.intersection(umaScopes, resource.getScopes()).isEmpty())
              .map(resource -> umaIdGetter.apply(resource.getUmaResourceId()))
              .filter(Objects::nonNull)
              .flatMap(Collection::stream)
              .filter(Objects::nonNull)
              .distinct()
              .collect(Collectors.toList());

      adcSearch.withFieldIn(resourceId, resourceIds);
      filterResponse = resourceIds.isEmpty();
    }

    var is = SpringUtils.catchForwardingError(() -> adcRequest.apply(adcSearch));
    // will only perform whitelist filtering if rpt grants access to nothing, for partial access the
    // backend must perform the filtering
    return SpringUtils.buildJsonStream(
        new SimpleJsonFilter(is, AdcConstants.ADC_FACETS, filterResponse));
  }

  private Function<String, Set<String>> adcSearchFlow(
          AdcSearchRequest adcSearch, // will be modified by reference
          String resourceId,
          FieldClass fieldClass,
          Collection<UmaResource> umaResources) {
    final Set<String> allRequestedFields = getRegularSearchRequestedFields(adcSearch, fieldClass);
    final Set<String> filtersFields = adcSearch.getFiltersFields();

    if (!allRequestedFields.contains(resourceId)) {
      adcSearch.addField(resourceId);
    }

    return this.buildUmaFieldMapper(umaResources, fieldClass)
        .andThen(
            fields -> {
              // don't return resources where the access level does not match the one in the
              // filters, in order to avoid information leaks
              if (Sets.difference(filtersFields, fields).isEmpty()) {
                return fields;
              }

              return EmptySet;
            })
        .andThen(set -> Sets.intersection(set, allRequestedFields));
  }

  private Set<String> getRegularSearchRequestedFields(
      AdcSearchRequest adcSearch, FieldClass fieldClass) {
    final Set<String> adcFields = adcSearch.isFieldsEmpty() ? Set.of() : adcSearch.getFields();
    final Set<String> adcIncludeFields =
        adcSearch.isIncludeFieldsEmpty()
            ? Set.of()
            : this.csvConfig.getFields(fieldClass, adcSearch.getIncludeFields());
    final Set<String> requestedFields = Sets.union(adcFields, adcIncludeFields);
    return new HashSet<>(
        requestedFields.isEmpty()
            ? this.csvConfig.getFields(fieldClass).keySet()
            : requestedFields);
  }

  private void validateAdcSearch(AdcSearchRequest adcSearch, FieldClass fieldClass)
      throws HttpException {

    if (adcSearch.isFacetsSearch() && !this.appConfig.isFacetsEnabled()) {
      throw SpringUtils.buildHttpException(
          HttpStatus.NOT_IMPLEMENTED,
          "Invalid input JSON: 'facets' support for current repository not enabled");
    }

    if (adcSearch.getFilters() != null && !this.appConfig.isAdcFiltersEnabled()) {
      throw SpringUtils.buildHttpException(
          HttpStatus.NOT_IMPLEMENTED,
          "Invalid input JSON: 'filters' support for current repository not enabled");
    }

    var filtersBlacklist = this.appConfig.getFiltersOperatorsBlacklist();
    Set<String> actualFiltersOperators = adcSearch.getFiltersOperators();
    Sets.SetView<String> operatorDiff = Sets.intersection(filtersBlacklist, actualFiltersOperators);
    if (!operatorDiff.isEmpty()) {
      throw SpringUtils.buildHttpException(
          HttpStatus.NOT_IMPLEMENTED,
          "Invalid input JSON: 'filters' operators: "
              + CollectionsUtils.toString(operatorDiff)
              + " are blacklisted");
    }

    var fieldTypes = this.csvConfig.getFields(fieldClass);
    try {
      AdcSearchRequest.validate(adcSearch, fieldTypes);
    } catch (AdcException e) {
      throw SpringUtils.buildHttpException(
          HttpStatus.UNPROCESSABLE_ENTITY, "Invalid input JSON: " + e.getMessage());
    }

    if (!adcSearch.isJsonFormat()) {
      Logger.error("Not implemented");
      throw SpringUtils.buildHttpException(
          HttpStatus.UNPROCESSABLE_ENTITY, "TSV format not supported yet");
    }
  }

  private Function<String, Set<String>> buildUmaFieldMapper(
      Collection<UmaResource> resources, FieldClass fieldClass) { // TODO extract into class
    var validUmaFields =
        resources.stream()
            .collect(
                Collectors.toMap(
                    UmaResource::getUmaResourceId,
                    uma -> this.csvConfig.getFields(fieldClass, uma.getScopes())));

    var publicFields = this.csvConfig.getPublicFields(fieldClass);

    return umaId -> {
      if (umaId == null) {
        Logger.warn(
            "A resource was returned by the repository with no mapping from resource ID to UMA ID. Consider synchronizing.");
        return publicFields;
      }

      var fields = validUmaFields.getOrDefault(umaId, EmptySet);
      return Sets.union(fields, publicFields);
    };
  }

  private static ResponseEntity<StreamingResponseBody> buildFilteredJsonResponse(
      String resourceId,
      String responseFilterField,
      Function<String, Set<String>> fieldMapper,
      ThrowingProducer<InputStream, Exception> adcRequest)
      throws Exception {
    var response = SpringUtils.catchForwardingError(adcRequest);
    var mapper = new ResourceJsonMapper(response, responseFilterField, fieldMapper, resourceId);
    return SpringUtils.buildJsonStream(mapper);
  }
}
