package pt.inesctec.adcauthmiddleware.controllers;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import pt.inesctec.adcauthmiddleware.HttpException;
import pt.inesctec.adcauthmiddleware.adc.AdcConstants;
import pt.inesctec.adcauthmiddleware.adc.models.AdcException;
import pt.inesctec.adcauthmiddleware.adc.models.AdcSearchRequest;
import pt.inesctec.adcauthmiddleware.config.AppConfig;
import pt.inesctec.adcauthmiddleware.config.csv.FieldClass;
import pt.inesctec.adcauthmiddleware.uma.UmaUtils;
import pt.inesctec.adcauthmiddleware.uma.exceptions.TicketException;
import pt.inesctec.adcauthmiddleware.uma.exceptions.UmaFlowException;
import pt.inesctec.adcauthmiddleware.utils.CollectionsUtils;
import pt.inesctec.adcauthmiddleware.utils.Delayer;

/**
 * class responsible for the protected endpoints.
 */
@RestController
public class AdcAuthController extends AdcController {
    @Autowired
    protected AppConfig appConfig;

    private static final org.slf4j.Logger Logger = LoggerFactory.getLogger(AdcAuthController.class);
    private static final Pattern JsonErrorPattern = Pattern.compile(".*line: (\\d+), column: (\\d+).*");

    @PostConstruct
    public void initialize() {
        Logger.info(String.valueOf(appConfig.getRequestDelaysPoolSize()));
        this.repertoiresDelayer = new Delayer(appConfig.getRequestDelaysPoolSize());
        this.rearrangementsDelayer = new Delayer(appConfig.getRequestDelaysPoolSize());
    }

    /**
     * Handles and logs errors on invalid user JSON body (on POST endpoints) such as invalid syntax or some invalid schema.
     *
     * @param e exception
     * @return error message
     */
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

    /**
     * Used when an application wants to return a specific error code.
     *
     * @param e exception
     * @return error code + message
     */
    @ExceptionHandler(HttpException.class)
    public ResponseEntity<String> httpExceptionForward(HttpException e) {
        Logger.debug("Stacktrace: ", e);
        return SpringUtils.buildResponse(e.statusCode, e.errorMsg, e.contentType.orElse(null));
    }

    /**
     * Not actually a logic error. Returns the UMA permissions ticket to the user.
     *
     * @param e exception
     * @return 401 + permissions ticket
     */
    @ExceptionHandler(TicketException.class)
    public ResponseEntity<String> ticketHandler(TicketException e) {
        var headers = ImmutableMap.of(HttpHeaders.WWW_AUTHENTICATE, e.buildAuthenticateHeader());
        return SpringUtils.buildJsonErrorResponse(
            HttpStatus.UNAUTHORIZED, "UMA permissions ticket emitted", headers);
    }

    /**
     * Logs errors related to the UMA flow (UMA client and UMA flow).
     *
     * @param e Exception
     * @return 401 status code
     */
    @ExceptionHandler(UmaFlowException.class)
    public ResponseEntity<String> umaFlowHandler(Exception e) {
        Logger.info("Uma flow access error {}", e.getMessage());
        Logger.debug("Stacktrace: ", e);

        return SpringUtils.buildJsonErrorResponse(HttpStatus.UNAUTHORIZED, null);
    }

    /**
     * Logs synchronization endpoint user errors.
     *
     * @param e exception
     * @return 401 status code
     */
    @ExceptionHandler(SyncException.class)
    public ResponseEntity<String> synchronizeErrorHandler(SyncException e) {
        Logger.info("Synchronize: {}", e.getMessage());
        return SpringUtils.buildJsonErrorResponse(HttpStatus.UNAUTHORIZED, null);
    }

    /**
     * Logs unhandled internal exceptions. Errors here can indicate a logic error or bug.
     *
     * @param e exception
     * @return 401 status code
     */
    @ExceptionHandler(Throwable.class)
    public ResponseEntity<String> internalErrorHandler(Exception e) {
        Logger.error("Internal error occurred: {}", e.getMessage());
        Logger.debug("Stacktrace: ", e);
        return SpringUtils.buildJsonErrorResponse(HttpStatus.UNAUTHORIZED, null);
    }

    /**
     * Protected by UMA. Individual repertoire. Part of ADC v1.
     *
     * @param request      user request
     * @param repertoireId repertoire ID
     * @return the filtered repertoire
     * @throws Exception if user does not have permissions or some other error occurs
     */
    @RequestMapping(
        value = "/repertoire/{repertoireId}/protected",
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

        var tokenResources = this.umaClient.introspectToken(bearer, true);

        var fieldMapper = UmaUtils.buildFieldMapper(
            tokenResources.getPermissions(), FieldClass.REPERTOIRE, csvConfig
        ).compose(this.dbRepository::getStudyUmaId);

        return responseFilteredJson(
            AdcConstants.REPERTOIRE_STUDY_ID_FIELD,
            AdcConstants.REPERTOIRE_RESPONSE_FILTER_FIELD,
            fieldMapper,
            () -> this.adcClient.getRepertoireAsStream(repertoireId));
    }

    /**
     * Protected by UMA. Individual rearrangement. Part of ADC v1.
     *
     * @param request         user request
     * @param rearrangementId rearrangement ID
     * @return the filtered rearrangement
     * @throws Exception if user does not have permissions or some other error occurs
     */
    @RequestMapping(
        value = "/rearrangement/{rearrangementId}/protected",
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

        var tokenResources = this.umaClient.introspectToken(bearer, true);
        var fieldMapper = UmaUtils.buildFieldMapper(
            tokenResources.getPermissions(), FieldClass.REARRANGEMENT, csvConfig
        ).compose(this.dbRepository::getRepertoireUmaId);

        return responseFilteredJson(
            AdcConstants.REARRANGEMENT_REPERTOIRE_ID_FIELD,
            AdcConstants.REARRANGEMENT_RESPONSE_FILTER_FIELD,
            fieldMapper,
            () -> this.adcClient.getRearrangementAsStream(rearrangementId));
    }

    private ResponseEntity<StreamingResponseBody> repertoireListPublic(AdcSearchRequest adcSearch) throws Exception
    {
        if (adcSearch.isFacetsSearch()) {
            return responseFilteredFacets(
                adcSearch,
                AdcConstants.REPERTOIRE_STUDY_ID_FIELD,
                this.adcClient::searchRepertoiresAsStream,
                Collections.<String>emptyList(),
                false);
        }

        var fieldMapper = adcSearch.setupPublicFieldMapper(
            FieldClass.REPERTOIRE, AdcConstants.REPERTOIRE_STUDY_ID_FIELD, csvConfig
        );

        return responseFilteredJson(
            AdcConstants.REPERTOIRE_STUDY_ID_FIELD,
            AdcConstants.REPERTOIRE_RESPONSE_FILTER_FIELD,
            fieldMapper.compose(this.dbRepository::getStudyUmaId),
            () -> this.adcClient.searchRepertoiresAsStream(adcSearch));
    }

    private ResponseEntity<StreamingResponseBody> repertoireListProtected(
        HttpServletRequest request,
        AdcSearchRequest adcSearch) throws Exception {
        Set<String> umaScopes = adcSearch.getUmaScopes(FieldClass.REPERTOIRE, this.csvConfig);
        Set<String> umaIds = getRepertoireStudyIds(adcSearch);

        var umaResources = this.umaFlow.adcSearch(
            request, umaIds, repertoiresDelayer, umaScopes
        );

        if (adcSearch.isFacetsSearch()) {
            final List<String> resourceIds = UmaUtils.filterFacets(
                umaResources,
                umaScopes,
                (umaId) -> CollectionsUtils.toSet(this.dbRepository.getUmaStudyId(umaId))
            );

            return responseFilteredFacets(
                adcSearch,
                AdcConstants.REPERTOIRE_STUDY_ID_FIELD,
                this.adcClient::searchRepertoiresAsStream,
                resourceIds,
                !umaScopes.isEmpty());
        }

        var fieldMapper = adcSearch.setupFieldMapper(
            FieldClass.REPERTOIRE, AdcConstants.REPERTOIRE_STUDY_ID_FIELD, umaResources, csvConfig
        );

        return responseFilteredJson(
            AdcConstants.REPERTOIRE_STUDY_ID_FIELD,
            AdcConstants.REPERTOIRE_RESPONSE_FILTER_FIELD,
            fieldMapper.compose(this.dbRepository::getStudyUmaId),
            () -> this.adcClient.searchRepertoiresAsStream(adcSearch));
    }

    /**
     * Protected by UMA. Repertoires search. Part of ADC v1.
     * JSON processed in streaming mode. Can return resource public fields if not given access to a resource.
     *
     * @param request user request
     * @return the filtered repertoires stream
     * @throws Exception if some error occurs
     */
    @RequestMapping(
        value = "/repertoire",
        method = RequestMethod.POST,
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StreamingResponseBody> repertoireList(
        @RequestHeader(value = "Content-Protected", defaultValue = "false") Boolean contentProtected,
        HttpServletRequest request,
        @RequestBody AdcSearchRequest adcSearch) throws Exception {
        this.validateAdcSearch(adcSearch, FieldClass.REPERTOIRE, false);

        return contentProtected ? repertoireListProtected(request, adcSearch) : repertoireListPublic(adcSearch);
    }

    private ResponseEntity<StreamingResponseBody> rearrangementListPublic(
        HttpServletRequest request,
        AdcSearchRequest adcSearch) throws Exception {

        if (adcSearch.isFacetsSearch()) {
            return responseFilteredFacets(
                adcSearch,
                AdcConstants.REARRANGEMENT_REPERTOIRE_ID_FIELD,
                this.adcClient::searchRearrangementsAsStream,
                Collections.<String>emptyList(),
                false);
        }

        var fieldMapper = adcSearch.setupPublicFieldMapper(
            FieldClass.REARRANGEMENT, AdcConstants.REARRANGEMENT_REPERTOIRE_ID_FIELD, csvConfig
        );

        if (adcSearch.isJsonFormat()) {
            return responseFilteredJson(
                AdcConstants.REARRANGEMENT_REPERTOIRE_ID_FIELD,
                AdcConstants.REARRANGEMENT_RESPONSE_FILTER_FIELD,
                fieldMapper.compose(this.dbRepository::getRepertoireUmaId),
                () -> this.adcClient.searchRearrangementsAsStream(adcSearch));
        }

        var requestedFieldTypes = getRegularSearchRequestedFieldsAndTypes(adcSearch, FieldClass.REARRANGEMENT);

        return responseFilteredTsv(
            AdcConstants.REARRANGEMENT_REPERTOIRE_ID_FIELD,
            AdcConstants.REARRANGEMENT_RESPONSE_FILTER_FIELD,
            fieldMapper.compose(this.dbRepository::getRepertoireUmaId),
            () -> this.adcClient.searchRearrangementsAsStream(adcSearch),
            requestedFieldTypes);
    }

    private ResponseEntity<StreamingResponseBody> rearrangementListProtected(
        HttpServletRequest request,
        AdcSearchRequest adcSearch) throws Exception {

        Set<String> umaScopes = adcSearch.getUmaScopes(FieldClass.REARRANGEMENT, this.csvConfig);
        Set<String> umaIds = this.getRearrangementsRepertoireIds(adcSearch);

        var umaResources = this.umaFlow.adcSearch(
            request, umaIds, rearrangementsDelayer, umaScopes
        );

        if (adcSearch.isFacetsSearch()) {
            final List<String> resourceIds = UmaUtils.filterFacets(
                umaResources, umaScopes, this.dbRepository::getUmaRepertoireModel
            );

            return responseFilteredFacets(
                adcSearch,
                AdcConstants.REARRANGEMENT_REPERTOIRE_ID_FIELD,
                this.adcClient::searchRearrangementsAsStream,
                resourceIds,
                !umaScopes.isEmpty());
        }


        var fieldMapper = adcSearch.setupFieldMapper(
            FieldClass.REARRANGEMENT,
            AdcConstants.REARRANGEMENT_REPERTOIRE_ID_FIELD,
            umaResources,
            csvConfig
        );

        if (adcSearch.isJsonFormat()) {
            return responseFilteredJson(
                AdcConstants.REARRANGEMENT_REPERTOIRE_ID_FIELD,
                AdcConstants.REARRANGEMENT_RESPONSE_FILTER_FIELD,
                fieldMapper.compose(this.dbRepository::getRepertoireUmaId),
                () -> this.adcClient.searchRearrangementsAsStream(adcSearch));
        }

        var requestedFieldTypes = getRegularSearchRequestedFieldsAndTypes(adcSearch, FieldClass.REARRANGEMENT);

        return responseFilteredTsv(
            AdcConstants.REARRANGEMENT_REPERTOIRE_ID_FIELD,
            AdcConstants.REARRANGEMENT_RESPONSE_FILTER_FIELD,
            fieldMapper.compose(this.dbRepository::getRepertoireUmaId),
            () -> this.adcClient.searchRearrangementsAsStream(adcSearch),
            requestedFieldTypes);
    }

    /**
     * Protected by UMA. Rearrangements search. Part of ADC v1.
     * JSON processed in streaming mode. Can return resource public fields if not given access to a resource.
     *
     * @param request user request
     * @return the filtered rearrangements stream
     * @throws Exception if some error occurs
     */
    @RequestMapping(
        value = "/rearrangement",
        method = RequestMethod.POST,
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StreamingResponseBody> rearrangementList(
        @RequestHeader(value = "Content-Protected", defaultValue = "false") Boolean contentProtected,
        HttpServletRequest request,
        @RequestBody AdcSearchRequest adcSearch) throws Exception {
        validateAdcSearch(adcSearch, FieldClass.REARRANGEMENT, true);

        return contentProtected ? rearrangementListProtected(request, adcSearch) : rearrangementListPublic(request, adcSearch);
    }

    /**
     * The synchronize endpoint. Not part of ADC v1. Protected by password set in the configuration file. Extension of the middleware.
     * Performs state synchronization between the repository and the UMA authorization server and this middleware's DB.
     * Resets the delays pool request times.
     *
     * @param request the user request
     * @return OK on successful synchronization or an error code when a process in the synchronization fails.
     * @throws Exception on user errors such as invalid password or some internal errors.
     */
    @RequestMapping(value = "/synchronize", method = RequestMethod.POST)
    public Map<String, Object> synchronize(HttpServletRequest request) throws Exception {
        String bearer = SpringUtils.getBearer(request);
        if (bearer == null) {
            throw new SyncException("Invalid user credential format");
        }

        var tokenResources = this.umaClient.introspectToken(bearer, false);

        if (!tokenResources.getRoles().contains(this.appConfig.getSynchronizeRole())) {
            throw new SyncException("User not allowed to synchronize resources");
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
}
