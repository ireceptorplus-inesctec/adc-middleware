package pt.inesctec.adcauthmiddleware.db.dto;

import java.util.ArrayList;
import java.util.List;

import pt.inesctec.adcauthmiddleware.db.models.AccessScope;
import pt.inesctec.adcauthmiddleware.db.models.AdcFields;

/**
 * DTO for mapping an AccessScope with their AdcFields.
 */
public class TemplateMappingDto {
    // Contains the AccessScope for this mapping
    private AccessScopeDto scope;
    // Contains the AdcField IDs for the current AccessScope
    private List<Long> fields;

    /**
     * Build a TemplateMappingDTO by providing both the access scope and the list of field for that scope.
     *
     * @param scope Scope to be added to this Mapping
     * @param fields List of ADC Fields to be mapped
     */
    public TemplateMappingDto(AccessScope scope, List<AdcFields> fields) {
        this.scope = new AccessScopeDto(scope);

        for (var field : fields) {
            this.fields.add(field.getId());
        }
    }

    /**
     * Build a TemplateMappingDTO by just providing the access scope. This will create a mapping for a provided scope,
     * along with an empty list of AdcFields that you can fill in later after building this object.
     *
     * @param scope Scope to be added to this Mapping
     */
    public TemplateMappingDto(AccessScope scope) {
        this.scope = new AccessScopeDto(scope);
        this.fields = new ArrayList<>();
    }

    // Manually add an ADC field
    public void addField(long fieldId) {
        this.fields.add(fieldId);
    }

    public List<Long> getFields() {
        return fields;
    }

    public void setFields(List<Long> fields) {
        this.fields = fields;
    }

    public AccessScopeDto getScope() {
        return scope;
    }

    public void setScope(AccessScopeDto scope) {
        this.scope = scope;
    }
}