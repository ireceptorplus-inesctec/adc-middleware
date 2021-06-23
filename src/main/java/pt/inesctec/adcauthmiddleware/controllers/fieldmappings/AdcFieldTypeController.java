package pt.inesctec.adcauthmiddleware.controllers.fieldmappings;

import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import pt.inesctec.adcauthmiddleware.controllers.ResourceController;
import pt.inesctec.adcauthmiddleware.db.dto.AdcFieldTypeDto;
import pt.inesctec.adcauthmiddleware.db.models.AdcFieldType;
import pt.inesctec.adcauthmiddleware.db.repository.AdcFieldTypeRepository;
import pt.inesctec.adcauthmiddleware.uma.dto.internal.TokenIntrospection;
import pt.inesctec.adcauthmiddleware.utils.SpringUtils;

import javax.servlet.http.HttpServletRequest;

@RestController
public class AdcFieldTypeController extends ResourceController {
    private static final org.slf4j.Logger Logger = LoggerFactory.getLogger(ResourceController.class);

    @Autowired
    private AdcFieldTypeRepository adcFieldTypeRepository;

    /**
     * Adc Field Types.
     *
     * @return JSON list of Adc Classes
     * @throws Exception for connection failures, authentication failure
     */
    @RequestMapping(
            value = "/class",
            method = RequestMethod.GET,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<AdcFieldTypeDto>> classList(
            HttpServletRequest request
    ) throws Exception {
        String bearer = SpringUtils.getBearer(request);
        TokenIntrospection introspection = umaClient.introspectToken(bearer, false);
        if (!introspection.isActive())
            throw new Exception("Access token is not active");
        List<AdcFieldType> classes = adcFieldTypeRepository.findAll();
        List<AdcFieldTypeDto> classList = classes.stream()
                .map(AdcFieldTypeDto::new)
                .collect(Collectors.toList());
        return new ResponseEntity<>(classList, HttpStatus.OK);
    }
}
